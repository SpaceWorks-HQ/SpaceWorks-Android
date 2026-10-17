package org.spaceworks.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
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
    var logoutGate:CompletableDeferred<Unit>?=null
    var verifyGate:CompletableDeferred<Unit>?=null
    var calls=0
    var native=true
    var grantId="00000000-0000-0000-0000-000000000001"
    var throttle=false
    var changed=false
    var googleEnabled=false
    var googleReady=true
    var socialRefresh:String?="social-refresh"
    override val nativeSocialReady get()=googleReady
    override suspend fun config()=PublicConfig(false,100,socialAuth=if(googleEnabled)mapOf("google" to
        kotlinx.serialization.json.buildJsonObject {put("enabled",kotlinx.serialization.json.JsonPrimitive(true));put("web_client_id",kotlinx.serialization.json.JsonPrimitive("server-web-client"))}) else null)
    override suspend fun google(clientId:String,credential:suspend(String,String)->String):SocialLoginResponse {
        calls++;assertEquals("server-web-client",clientId)
        assertEquals("sdk-token",credential(clientId,"fresh-server-nonce"))
        return SocialLoginResponse("social-access",emptyMap(),"authenticated",socialRefresh,mapOf("id" to kotlinx.serialization.json.JsonPrimitive(grantId)))
    }
    override suspend fun login(username:String,password:String):DeviceLoginResult {
        calls++
        if(throttle)throw ApiFailure(429,"Wait",null,60)
        return DeviceLoginResult.MFAChallengeResponseValue(MFAChallengeResponse(true,false,"challenge",300))
    }
    override suspend fun enroll(challenge:String)=MFAEnrollment("secret","otpauth://demo","data:image/png;base64,")
    override suspend fun verify(challenge:String,code:String,enrollment:Boolean):MFACompleted {
        verifyGate?.await()
        return MFACompleted(who,"access","device",refresh=if(native)"refresh" else null,deviceGrant=if(native)mapOf("id" to kotlinx.serialization.json.JsonPrimitive(grantId)) else null)
    }
    override suspend fun me():AuthUserPayload {identityFailure?.let {throw it};return who}
    override suspend fun logout() {logoutGate?.await()}
    override suspend fun changePassword(current:String,replacement:String){changed=true}
}
class AuthTest {
    @Test fun delayedLogoutInvalidatesPrivateFlowsBeforeAwaitingServerRevocation()=runTest {
        val api=FakeAuth();val store=MemoryTokenStore();var privateScopeAccessible=true
        val holder=AuthStateHolder(api,store,onSessionEnded={privateScopeAccessible=false})
        holder.initialize();holder.signIn("member","pass");holder.verify("123456")
        api.logoutGate=CompletableDeferred()
        val logout=launch {holder.signOut()};runCurrent()
        assertTrue(holder.state.value.busy);assertFalse(privateScopeAccessible)
        assertNull(holder.state.value.user);assertNull(holder.state.value.selectedId)
        assertNotNull(store.read()) // Still needed for the in-flight server revocation.
        api.logoutGate!!.complete(Unit);logout.join()
        assertNull(store.read());assertNull(holder.state.value.user)
    }
    @Test fun privateScopeIsClearedSynchronouslyBeforeSelectionAndRevocationPublish()=runTest {
        val api=FakeAuth()
        val initial=api.who.makerspaces.single()
        api.who=api.who.copy(makerspaces=listOf(initial,initial.copy(id=2,slug="second")))
        lateinit var holder:AuthStateHolder
        val selectionsAtCleanup=mutableListOf<Int?>()
        holder=AuthStateHolder(api,MemoryTokenStore(),onScopeChanged={selectionsAtCleanup+=holder.state.value.selectedId})
        holder.initialize();holder.signIn("member","pass");holder.verify("123456")
        holder.select(1);holder.select(2);holder.select(2)
        assertEquals(listOf(null,1),selectionsAtCleanup)
        api.who=api.who.copy(makerspaces=emptyList());holder.foreground()
        assertEquals(listOf(null,1,2),selectionsAtCleanup)
        assertNull(holder.state.value.selectedId)
    }
    @Test fun staleMakerspaceChoiceAfterForegroundRefreshNeverCrashesOrSelectsRemovedAccess()=runTest {
        val api=FakeAuth();val holder=AuthStateHolder(api,MemoryTokenStore())
        holder.initialize();holder.signIn("member","pass");holder.verify("123456")
        api.who=api.who.copy(makerspaces=emptyList());holder.foreground();holder.select(1)
        assertNull(holder.state.value.selectedId);assertTrue(holder.state.value.memberships.isEmpty())
        assertEquals("Your makerspace access changed. Choose again.",holder.state.value.error)
    }
    @Test fun googleRequiresConfiguredProviderAndNativeTransportBeforeSdk()=runTest {
        val api=FakeAuth();val store=MemoryTokenStore();val holder=AuthStateHolder(api,store)
        var sdkCalls=0
        holder.initialize();holder.googleSignIn {_,_->sdkCalls++;"sdk-token"}
        assertEquals(0,sdkCalls);assertEquals(0,api.calls)
        api.googleEnabled=true;api.googleReady=false;holder.initialize();holder.googleSignIn {_,_->sdkCalls++;"sdk-token"}
        assertEquals(0,sdkCalls);assertEquals(0,api.calls);assertNull(store.read())
    }
    @Test fun googlePublishesNativePairOnlyAfterIdentityAndRejectsBrowserSession()=runTest {
        val api=FakeAuth().apply {googleEnabled=true};val store=MemoryTokenStore();val holder=AuthStateHolder(api,store)
        holder.initialize();holder.googleSignIn {client,nonce->assertEquals("server-web-client",client);assertEquals("fresh-server-nonce",nonce);"sdk-token"}
        assertEquals(TokenPair("social-access","social-refresh"),store.read());assertNotNull(holder.state.value.user)
        holder.signOut();api.socialRefresh=null;holder.googleSignIn {_,_->"sdk-token"}
        assertNull(store.read());assertNull(holder.state.value.user);assertTrue(holder.state.value.error!!.contains("device session"))
    }
    @Test fun cancelledGoogleChooserIsNeutralAndNeverPersistsSession()=runTest {
        val api=FakeAuth().apply {googleEnabled=true};val store=MemoryTokenStore();val holder=AuthStateHolder(api,store)
        holder.initialize();holder.googleSignIn {_,_->throw AuthCancelled()}
        assertNull(store.read());assertNull(holder.state.value.error);assertEquals("Sign-in cancelled.",holder.state.value.notice)
    }
    @Test fun foregroundRevocationShowsSessionGateButOfflineKeepsCurrentIdentity()=runTest {
        val api=FakeAuth();val store=MemoryTokenStore();val holder=AuthStateHolder(api,store)
        holder.initialize();holder.signIn("member","pass");holder.verify("123456")
        api.identityFailure=IOException();holder.foreground();assertNotNull(holder.state.value.user);assertNull(holder.state.value.gate)
        api.identityFailure=ApiFailure(401,"Expired",null,null);holder.foreground()
        assertNull(holder.state.value.user);assertNull(store.read());assertEquals(AuthGate.SESSION_ENDED,holder.state.value.gate)
        holder.dismissGate();assertNull(holder.state.value.gate)
    }
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
    @Test fun malformedNativeGrantNeverPersistsMfaTokens()=runTest {
        val api=FakeAuth().apply {grantId="not-a-grant"};val store=MemoryTokenStore();val holder=AuthStateHolder(api,store)
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
        assertEquals("Your verification session expired. Sign in to request a new one.",holder.state.value.error)
        holder.cancelMfa();api.throttle=true;holder.signIn("member","pass");holder.signIn("member","pass")
        assertEquals(2,api.calls)
        // Establish an owned native session after the deadline; an arbitrary store replacement
        // would belong to another window and must never be erased by this anonymous holder.
        now+=60_001;api.throttle=false;holder.signIn("member","pass");holder.verify("123456")
        assertNotNull(holder.state.value.user);assertNotNull(store.read())
        api.throttle=true;holder.signIn("member","pass");assertTrue(holder.state.value.retryAtMillis>now)
        holder.signOut();assertNull(store.read());assertNull(holder.state.value.user)
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
