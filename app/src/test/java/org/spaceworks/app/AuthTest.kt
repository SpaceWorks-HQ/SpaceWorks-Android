package org.spaceworks.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.auth.*
import org.spaceworks.app.network.*
import org.spaceworks.generated.model.*
import java.io.IOException

private const val USER="""{"id":1,"username":"member","email":"member@example.invalid","display_name":"Member","phone":"","email_verified":true,"role":"member","is_superuser":false,"must_change_password":false,"makerspaces":[{"id":1,"slug":"fab-lab","role":"member","role_id":1,"role_name":"Member","role_slug":"member","source":"membership","actions":[],"can_configure_machine_types":false,"is_machine_only":false,"can_refer":false,"can_verify":false,"verified_at":null,"referrals_enabled":false}]}"""
private class FakeAuth:AuthGateway {
    var who=Json.decodeFromString<AuthUserPayload>(USER)
    var identityFailure:Exception?=null
    var verifyGate:CompletableDeferred<Unit>?=null
    var calls=0
    var native=true
    var throttle=false
    var changed=false
    override suspend fun config()=PublicConfig(false,100)
    override suspend fun login(username:String,password:String):DeviceLoginResult {
        calls++
        if(throttle)throw ApiFailure(429,"Wait",null,60)
        return DeviceLoginResult.MFAChallengeResponseValue(MFAChallengeResponse(true,false,"challenge",300))
    }
    override suspend fun enroll(challenge:String)=MFAEnrollment("secret","otpauth://demo","data:image/png;base64,")
    override suspend fun verify(challenge:String,code:String,enrollment:Boolean):MFACompleted {
        verifyGate?.await()
        return MFACompleted(who,"access","device",refresh=if(native)"refresh" else null,deviceGrant=if(native)mapOf("id" to kotlinx.serialization.json.JsonPrimitive("device")) else null)
    }
    override suspend fun me():AuthUserPayload {identityFailure?.let {throw it};return who}
    override suspend fun logout() {}
    override suspend fun changePassword(current:String,replacement:String){changed=true}
}
class AuthTest {
    @Test fun nativeMfaPersistsPairAndSelectsOnlyEligibleMembership()=runTest {
        val api=FakeAuth();val store=MemoryTokenStore();val holder=AuthStateHolder(api,store)
        holder.initialize();holder.signIn("member","pass");assertNull(store.read())
        holder.verify("123456")
        assertEquals(TokenPair("access","refresh"),store.read());assertEquals(1,holder.state.value.selectedId)
        assertFalse(holder.state.value.staffAllowed)
    }
    @Test fun webMfaWithoutRefreshFailsClosed()=runTest {
        val api=FakeAuth().apply {native=false};val store=MemoryTokenStore();val holder=AuthStateHolder(api,store)
        holder.initialize();holder.signIn("member","pass");holder.verify("123456")
        assertNull(store.read());assertNull(holder.state.value.user)
        assertTrue(holder.state.value.error!!.contains("device session"))
    }
    @Test fun identityFailureAfterNativeMfaClearsPartiallyEstablishedSession()=runTest {
        val api=FakeAuth().apply {identityFailure=IOException("private transport detail")};val store=MemoryTokenStore();val holder=AuthStateHolder(api,store)
        holder.initialize();holder.signIn("member","pass");holder.verify("123456")
        assertNull(store.read());assertEquals("Couldn't connect. Try again.",holder.state.value.error)
    }
    @Test fun cancellationCannotAcceptLateMfaOrRunConcurrentFinish()=runTest {
        val api=FakeAuth().apply {verifyGate=CompletableDeferred()};val store=MemoryTokenStore();val holder=AuthStateHolder(api,store)
        holder.initialize();holder.signIn("member","pass")
        val verify=launch(start=CoroutineStart.UNDISPATCHED){holder.verify("123456")}
        holder.cancelMfa();holder.finishMfa()
        assertTrue(holder.state.value.busy);assertNull(store.read())
        verify.cancelAndJoin();holder.cancelMfa();api.verifyGate!!.complete(Unit)
        assertNull(store.read());assertNull(holder.state.value.mfa)
    }
    @Test fun expiryAndRateLimitPreventRequestsButAllowLocalSignout()=runTest {
        var now=0L;val api=FakeAuth();val store=MemoryTokenStore();val holder=AuthStateHolder(api,store,now={now})
        holder.initialize();holder.signIn("member","pass");now=301000;holder.verify("123456")
        assertEquals("Your sign-in expired. Start again.",holder.state.value.error)
        holder.cancelMfa();api.throttle=true;holder.signIn("member","pass");holder.signIn("member","pass")
        assertEquals(2,api.calls);store.replace(TokenPair("a","r"));holder.signOut();assertNull(store.read())
    }
    @Test fun superadminIsRefusedAndSessionErased()=runTest {
        val api=FakeAuth().apply {who=who.copy(isSuperuser=true)};val store=MemoryTokenStore();val holder=AuthStateHolder(api,store)
        holder.initialize();holder.signIn("member","pass");holder.verify("123456")
        assertNull(store.read());assertNull(holder.state.value.user);assertEquals("Use the web console",holder.state.value.error)
    }
    @Test fun organizationMembershipNeverAppearsInPicker()=runTest {
        val api=FakeAuth().apply {who=who.copy(makerspaces=who.makerspaces.map {it.copy(source=AuthMembershipSourceEnum.organization)})}
        val holder=AuthStateHolder(api,MemoryTokenStore());holder.initialize();holder.signIn("member","pass");holder.verify("123456")
        assertTrue(holder.state.value.memberships.isEmpty());assertNull(holder.state.value.selectedId)
    }
    @Test fun foregroundSuperadminChangeRemovesOldDashboardIdentity()=runTest {
        val api=FakeAuth();val store=MemoryTokenStore();val holder=AuthStateHolder(api,store)
        holder.initialize();holder.signIn("member","pass");holder.verify("123456");assertNotNull(holder.state.value.user)
        api.who=api.who.copy(isSuperuser=true);holder.foreground()
        assertNull(holder.state.value.user);assertNull(holder.state.value.selectedId);assertNull(store.read())
    }
    @Test fun forcedPasswordChangeChecksConfirmationAndClearsSessionAfterSuccess()=runTest {
        val api=FakeAuth().apply {who=who.copy(mustChangePassword=true)};val store=MemoryTokenStore();val holder=AuthStateHolder(api,store)
        holder.initialize();holder.signIn("member","pass");holder.verify("123456")
        assertTrue(holder.state.value.user!!.mustChangePassword)
        holder.changePassword("old","new-secret","different");assertFalse(api.changed);assertNotNull(store.read())
        holder.changePassword("old","new-secret","new-secret");assertTrue(api.changed);assertNull(store.read());assertNull(holder.state.value.user)
        assertEquals("Password updated. Sign in again.",holder.state.value.notice)
    }
}
