package org.spaceworks.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.auth.*
import org.spaceworks.app.network.*
import org.spaceworks.generated.model.*
import java.io.IOException

private const val LOCAL_USER="""{"session_authority":"member","id":1,"username":"member","email":"member@example.invalid","display_name":"Member","phone":"","email_verified":true,"role":"member","is_superuser":false,"must_change_password":false,"makerspaces":[{"id":1,"slug":"fab-lab","role":"member","role_id":1,"role_ids":[1],"role_slugs":["member"],"role_names":["Member"],"role_name":"Member","role_slug":"member","source":"membership","actions":[],"can_configure_machine_types":false,"is_machine_only":false,"can_refer":false,"can_verify":false,"verified_at":null,"referrals_enabled":false}]}"""
private class LocalAccountAuth:AuthGateway {
    var user=Json.decodeFromString<AuthUserPayload>(LOCAL_USER)
    var entered:CompletableDeferred<Unit>?=null
    var release:CompletableDeferred<Unit>?=null
    var failure:Exception?=null
    var logouts=0
    var logins=0
    var loginEntered:CompletableDeferred<Unit>?=null
    var loginRelease:CompletableDeferred<Unit>?=null
    var loginMfa=false
    var passwordEntered:CompletableDeferred<Unit>?=null
    var passwordRelease:CompletableDeferred<Unit>?=null
    var verifyEntered:CompletableDeferred<Unit>?=null
    var verifyRelease:CompletableDeferred<Unit>?=null
    override suspend fun config()=PublicConfig(false,100)
    override suspend fun login(username:String,password:String):DeviceLoginResult {logins++;loginEntered?.complete(Unit);loginRelease?.await();if(loginMfa)return DeviceLoginResult.MFAChallengeResponseValue(MFAChallengeResponse(true,false,"challenge",300));return DeviceLoginResult.DeviceTokenResponseValue(DeviceTokenResponse("new-access","new-refresh",emptyMap(),DeviceGrant(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),"android","org.spaceworks.app","development","active",java.time.OffsetDateTime.parse("2026-10-16T00:00:00Z"),java.time.OffsetDateTime.parse("2026-10-16T00:00:00Z"),java.time.OffsetDateTime.parse("2026-10-16T00:00:00Z"))))}
    override suspend fun enroll(challenge:String):MFAEnrollment=error("unused")
    override suspend fun verify(challenge:String,code:String,enrollment:Boolean):MFACompleted {verifyEntered?.complete(Unit);verifyRelease?.await();return MFACompleted(user,"verified-access","device",refresh="verified-refresh",deviceGrant=mapOf("id" to kotlinx.serialization.json.JsonPrimitive("00000000-0000-0000-0000-000000000001")),recoveryCodes=listOf("private-code"))}
    override suspend fun me():AuthUserPayload {entered?.complete(Unit);release?.await();failure?.let {throw it};return user}
    override suspend fun logout(){logouts++}
    override suspend fun changePassword(current:String,replacement:String){passwordEntered?.complete(Unit);passwordRelease?.await()}
}
@OptIn(ExperimentalCoroutinesApi::class)
class AccountAuthBoundaryTest {
    @Test fun invalidatedPendingLoginCannotInstallTokensOrPublishMfa()=runTest {
        for(mfa in listOf(false,true)) {
            val api=LocalAccountAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))}
            val holder=AuthStateHolder(api,store);holder.initialize()
            api.loginMfa=mfa;api.loginEntered=CompletableDeferred();api.loginRelease=CompletableDeferred()
            val login=launch {holder.signIn("member","secret")};api.loginEntered!!.await()
            store.replace(TokenPair("other-window","other-refresh"));assertTrue(holder.invalidateInstallation(store.snapshot().epoch))
            api.loginRelease!!.complete(Unit);login.join()
            assertNull(holder.state.value.user);assertNull(holder.state.value.mfa);assertTrue(holder.state.value.recoveryCodes.isEmpty())
            assertEquals(TokenPair("other-window","other-refresh"),store.read());assertNull(holder.state.value.error)
        }
    }
    @Test fun firstLoginCannotOverwriteAnotherWindowsInstallationWhilePublicRequestWaits()=runTest {
        for(mfa in listOf(false,true)) {
            val api=LocalAccountAuth();val store=MemoryTokenStore();val holder=AuthStateHolder(api,store);holder.initialize()
            api.loginMfa=mfa;api.loginEntered=CompletableDeferred();api.loginRelease=CompletableDeferred()
            val login=launch {holder.signIn("member","secret")};api.loginEntered!!.await()
            store.replace(TokenPair("other-window","other-refresh"));assertFalse(holder.invalidateInstallation(store.snapshot().epoch))
            api.loginRelease!!.complete(Unit);login.join()
            assertNull(holder.state.value.user);assertNull(holder.state.value.mfa)
            assertEquals(TokenPair("other-window","other-refresh"),store.read())
        }
    }
    @Test fun invalidatedTemporaryPasswordAckCannotLogoutReplacementAccount()=runTest {
        val api=LocalAccountAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))}
        val holder=AuthStateHolder(api,store);holder.initialize()
        api.passwordEntered=CompletableDeferred();api.passwordRelease=CompletableDeferred()
        val change=launch {holder.changePassword("old-password","replacement-password","replacement-password")};api.passwordEntered!!.await()
        store.replace(TokenPair("other-window","other-refresh"));assertTrue(holder.invalidateInstallation(store.snapshot().epoch))
        api.passwordRelease!!.complete(Unit);change.join()
        assertEquals(0,api.logouts);assertEquals(TokenPair("other-window","other-refresh"),store.read())
        assertNull(holder.state.value.user);assertNull(holder.state.value.notice)
    }
    @Test fun invalidatedMfaCompletionCannotInstallTokensOrPublishRecoveryCodes()=runTest {
        val api=LocalAccountAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))}
        val holder=AuthStateHolder(api,store);holder.initialize();api.loginMfa=true;holder.signIn("member","secret")
        api.verifyEntered=CompletableDeferred();api.verifyRelease=CompletableDeferred()
        val verify=launch {holder.verify("123456")};api.verifyEntered!!.await()
        store.replace(TokenPair("other-window","other-refresh"));assertTrue(holder.invalidateInstallation(store.snapshot().epoch))
        api.verifyRelease!!.complete(Unit);verify.join()
        assertNull(holder.state.value.user);assertNull(holder.state.value.mfa);assertTrue(holder.state.value.recoveryCodes.isEmpty())
        assertEquals(TokenPair("other-window","other-refresh"),store.read())
    }
    @Test fun anotherWindowsInstallationImmediatelyClearsPrivateUiWithoutChangingNewCredentials()=runTest {
        for(failure in listOf<Exception?>(null,IOException(),ApiFailure(401,"Old identity",null,null),ApiFailure(429,"Old throttle",null,60))) {
            val api=LocalAccountAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))}
            var privateData=true;var saved:Int?=1;var clears=0
            val holder=AuthStateHolder(api,store,saveSelection={saved=it},onSessionEnded={privateData=false;clears++})
            holder.initialize();privateData=true;val before=clears
            api.entered=CompletableDeferred();api.release=CompletableDeferred();api.failure=failure
            val read=launch {holder.foreground()};api.entered!!.await()
            store.replace(TokenPair("other-window","other-refresh"));saved=99
            assertTrue(holder.invalidateInstallation(store.snapshot().epoch))
            assertNull(holder.state.value.user);assertNull(holder.state.value.selectedId);assertFalse(privateData)
            assertEquals(before+1,clears);assertEquals(99,saved);assertTrue(holder.state.value.busy)
            assertEquals(TokenPair("other-window","other-refresh"),store.read())
            assertFalse(holder.invalidateInstallation(store.snapshot().epoch))
            api.release!!.complete(Unit);read.join()
            assertNull(holder.state.value.user);assertNull(holder.state.value.error);assertNull(holder.state.value.gate)
            assertFalse(holder.state.value.busy);assertEquals(0L,holder.state.value.retryAtMillis)
            assertEquals(TokenPair("other-window","other-refresh"),store.read());assertEquals(0,api.logouts)
        }
    }
    @Test fun installationObserverIgnoresSameEpochRotationAndStaleEmissions()=runTest {
        val api=LocalAccountAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))}
        var clears=0;val holder=AuthStateHolder(api,store,onSessionEnded={clears++});holder.initialize()
        val accepted=holder.acceptedInstallationEpoch!!;val before=clears
        assertTrue(store.rotateIfCurrent(store.snapshot(),TokenPair("rotated","next")))
        assertFalse(holder.invalidateInstallation(accepted));assertNotNull(holder.state.value.user)
        store.replace(TokenPair("other","other-refresh"))
        assertFalse(holder.invalidateInstallation(accepted));assertEquals(before,clears)
        assertTrue(holder.invalidateInstallation(store.snapshot().epoch));assertEquals(before+1,clears)
        assertNull(holder.state.value.user);assertEquals(TokenPair("other","other-refresh"),store.read())
    }
    @Test fun ownPendingLoginAndItsLaterAcceptedIdentitySurviveObserverSignals()=runTest {
        val api=LocalAccountAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))}
        val holder=AuthStateHolder(api,store);holder.initialize();val oldEpoch=store.snapshot().epoch
        api.entered=CompletableDeferred();api.release=CompletableDeferred()
        val login=launch {holder.signIn("other","secret")};api.entered!!.await()
        val newEpoch=store.snapshot().epoch;assertNotEquals(oldEpoch,newEpoch)
        assertNull(holder.acceptedInstallationEpoch)
        assertFalse(holder.invalidateInstallation(newEpoch))
        api.release!!.complete(Unit);login.join()
        assertEquals(newEpoch,holder.acceptedInstallationEpoch);assertNotNull(holder.state.value.user)
        assertFalse(holder.invalidateInstallation(oldEpoch));assertFalse(holder.invalidateInstallation(newEpoch))
        assertEquals(TokenPair("new-access","new-refresh"),store.read())
    }
    @Test fun sharedCoreClearInvalidatesCachedUiWithoutRemoteLogoutOrSelectionWrite()=runTest {
        val api=LocalAccountAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))}
        var clears=0;var writes=0
        val holder=AuthStateHolder(api,store,saveSelection={writes++},onSessionEnded={clears++})
        holder.initialize();val beforeWrites=writes;val beforeClears=clears
        assertTrue(store.rotateIfCurrent(store.snapshot(),null))
        assertTrue(holder.invalidateInstallation(store.snapshot().epoch))
        assertNull(holder.state.value.user);assertNull(holder.acceptedInstallationEpoch)
        assertEquals(beforeClears+1,clears);assertEquals(beforeWrites,writes)
        assertNull(store.read());assertEquals(0,api.logouts)
    }
    @Test fun localEndClearsPrivateStateBeforeSuspensionAndDoesNotLogoutAgain()=runTest {
        val api=LocalAccountAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))}
        var privateData=true;var saved:Int?=1
        val holder=AuthStateHolder(api,store,saveSelection={saved=it},onSessionEnded={privateData=false})
        holder.initialize()
        val ending=async(start=CoroutineStart.UNDISPATCHED){holder.endLocalSession("Security updated. Sign in again.")}
        assertNull(holder.state.value.user);assertNull(holder.state.value.selectedId);assertFalse(privateData);assertNull(saved)
        assertTrue(ending.await());assertNull(store.read());assertEquals(0,api.logouts)
        assertEquals("Security updated. Sign in again.",holder.state.value.notice)
    }
    @Test fun delayedIdentityCannotRestoreLocalEndedUserOrOverwriteNotice()=runTest {
        for(failure in listOf<Exception?>(null,IOException(),ApiFailure(401,"Old request",null,null))) {
            val api=LocalAccountAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))};val holder=AuthStateHolder(api,store)
            holder.initialize();api.entered=CompletableDeferred();api.release=CompletableDeferred();api.failure=failure
            val read=launch {holder.foreground()};api.entered!!.await()
            assertTrue(holder.endLocalSession("Session removed."));assertTrue(holder.state.value.busy);assertNull(holder.state.value.user)
            api.release!!.complete(Unit);read.join()
            assertNull(holder.state.value.user);assertNull(holder.state.value.error);assertNull(holder.state.value.gate)
            assertFalse(holder.state.value.busy);assertEquals("Session removed.",holder.state.value.notice);assertNull(store.read())
        }
    }
    @Test fun staleHolderCannotClearNewAccountTokensSelectionOrCallbacks()=runTest {
        val api=LocalAccountAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))};var cleanup=0;var saved:Int?=1
        val holder=AuthStateHolder(api,store,saveSelection={saved=it},onSessionEnded={cleanup++},onScopeChanged={cleanup++})
        holder.initialize();val before=cleanup;store.replace(TokenPair("new-account","new-refresh"));saved=99
        assertFalse(holder.endLocalSession());assertFalse(holder.dropConfirmedMembership(1))
        assertEquals(before,cleanup);assertEquals(99,saved);assertEquals(TokenPair("new-account","new-refresh"),store.read())
    }
    @Test fun confirmedLeaveClearsSelectedScopeBeforeOfflineRefreshAndStaleEcho()=runTest {
        val api=LocalAccountAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))};var privateData=true;var selectedAtClear:Int?=null
        lateinit var holder:AuthStateHolder
        holder=AuthStateHolder(api,store,onScopeChanged={selectedAtClear=holder.state.value.selectedId;privateData=false})
        holder.initialize();privateData=true
        assertTrue(holder.dropConfirmedMembership(1));assertEquals(1,selectedAtClear);assertFalse(privateData)
        assertNull(holder.state.value.selectedId);assertTrue(holder.state.value.memberships.isEmpty());assertNotNull(holder.state.value.user)
        api.failure=IOException();holder.foreground();assertTrue(holder.state.value.memberships.isEmpty())
        api.failure=null;holder.foreground();assertTrue(holder.state.value.memberships.isEmpty());assertNull(holder.state.value.selectedId)
        assertNotNull(store.read())
    }
    @Test fun delayedIdentityCannotRestoreConfirmedLeftMembershipAndRotationKeepsTombstone()=runTest {
        val api=LocalAccountAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))};val holder=AuthStateHolder(api,store)
        holder.initialize();api.entered=CompletableDeferred();api.release=CompletableDeferred()
        val read=launch {holder.foreground()};api.entered!!.await();assertTrue(holder.dropConfirmedMembership(1))
        assertTrue(store.rotateIfCurrent(store.snapshot(),TokenPair("rotated","rotated-refresh")))
        api.release!!.complete(Unit);read.join();assertTrue(holder.state.value.memberships.isEmpty());assertNull(holder.state.value.selectedId)
        api.release=null;holder.foreground();assertTrue(holder.state.value.memberships.isEmpty())
    }
    @Test fun atomicLocalWipeRetriesSameInstallationRotationButPreservesReplacementLogin()=runTest {
        for(replacement in listOf(false,true)) {
            val api=LocalAccountAuth();val backing=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))}
            var intercepted=false;var callbacksAfterReplacement=0
            val store=object:TokenStore by backing {
                override fun rotateIfCurrent(expected:TokenSnapshot,pair:TokenPair?):Boolean {
                    if(pair==null && !intercepted) {
                        intercepted=true
                        if(replacement)backing.replace(TokenPair("new-account","new-refresh"))
                        else assertTrue(backing.rotateIfCurrent(expected,TokenPair("rotated","rotated-refresh")))
                    }
                    return backing.rotateIfCurrent(expected,pair)
                }
            }
            val holder=AuthStateHolder(api,store,onSessionEnded={if(intercepted)callbacksAfterReplacement++})
            holder.initialize();assertTrue(holder.endLocalSession())
            assertEquals(if(replacement)TokenPair("new-account","new-refresh") else null,store.read())
            assertTrue(intercepted);assertEquals(0,callbacksAfterReplacement);assertEquals(0,api.logouts)
        }
    }
    @Test fun newOperationsWaitForLocalCredentialWipeAndCannotInstallDuringCleanup()=runTest {
        val api=LocalAccountAuth();val backing=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))}
        val entered=CompletableDeferred<Unit>();val release=java.util.concurrent.CountDownLatch(1)
        val store=object:TokenStore by backing {
            override fun rotateIfCurrent(expected:TokenSnapshot,pair:TokenPair?):Boolean {
                if(pair==null) {entered.complete(Unit);check(release.await(10,java.util.concurrent.TimeUnit.SECONDS))}
                return backing.rotateIfCurrent(expected,pair)
            }
        }
        val holder=AuthStateHolder(api,store);holder.initialize()
        val ending=async {holder.endLocalSession()};entered.await()
        try {
            assertTrue(holder.state.value.busy);assertNull(holder.state.value.user)
            holder.signIn("member","secret");assertEquals(0,api.logins)
        } finally {release.countDown()}
        assertTrue(ending.await());assertFalse(holder.state.value.busy);assertNull(store.read())
    }
    @Test fun localEndPreservesVisibleAuthThrottleUntilItsActualDeadline()=runTest {
        var now=0L
        val api=LocalAccountAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))}
        val holder=AuthStateHolder(api,store,now={now});holder.initialize()
        api.failure=ApiFailure(429,"Wait",null,60);holder.foreground()
        assertEquals(60000L,holder.state.value.retryAtMillis)
        assertTrue(holder.endLocalSession("Session removed."));assertEquals(60000L,holder.state.value.retryAtMillis)
        holder.signIn("member","secret");assertEquals(0,api.logins)
        now=60000L;api.failure=null;holder.signIn("member","secret")
        assertEquals(1,api.logins);assertNotNull(holder.state.value.user)
    }
    @Test fun ownAlreadyClearedInstallationEndsPrivateUiEvenDuringDelayedOfflineForeground()=runTest {
        val api=LocalAccountAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))}
        var privateData=true
        val holder=AuthStateHolder(api,store,onSessionEnded={privateData=false});holder.initialize()
        api.entered=CompletableDeferred();api.release=CompletableDeferred();api.failure=IOException()
        val read=launch {holder.foreground()};api.entered!!.await()
        assertTrue(store.rotateIfCurrent(store.snapshot(),null)) // Core refresh refusal already removed the grant.
        assertTrue(holder.endLocalSession("Session ended."));assertNull(holder.state.value.user);assertFalse(privateData)
        assertTrue(holder.state.value.busy);api.release!!.complete(Unit);read.join()
        assertNull(holder.state.value.user);assertNull(holder.state.value.error)
        assertEquals("Session ended.",holder.state.value.notice);assertNull(store.read());assertEquals(0,api.logouts)
    }
    @Test fun acceptedInstallationTracksAcceptedIdentityNotJustCurrentTokenStore()=runTest {
        val api=LocalAccountAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))}
        val holder=AuthStateHolder(api,store);assertNull(holder.acceptedInstallationEpoch)
        holder.initialize();val original=store.snapshot().epoch;assertEquals(original,holder.acceptedInstallationEpoch)
        assertTrue(store.rotateIfCurrent(store.snapshot(),TokenPair("rotated","rotated-refresh")))
        assertEquals(original,holder.acceptedInstallationEpoch)
        store.replace(TokenPair("new-account","new-refresh"));assertNull(holder.acceptedInstallationEpoch)
        holder.foreground();assertNull(holder.acceptedInstallationEpoch)
        holder.signIn("member","secret");assertEquals(store.snapshot().epoch,holder.acceptedInstallationEpoch)
        assertTrue(store.rotateIfCurrent(store.snapshot(),null));assertNull(holder.acceptedInstallationEpoch)
        assertTrue(holder.endLocalSession());assertNull(holder.acceptedInstallationEpoch)
    }
    @Test fun persistedLoginCannotBorrowPreviouslyAcceptedUserDuringFreshIdentityRead()=runTest {
        val api=LocalAccountAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))};val holder=AuthStateHolder(api,store)
        holder.initialize();api.entered=CompletableDeferred();api.release=CompletableDeferred()
        val login=launch {holder.signIn("other","secret")};api.entered!!.await()
        assertNotNull(store.read());assertNotNull(holder.state.value.user)
        assertNull(holder.acceptedInstallationEpoch)
        api.release!!.complete(Unit);login.join();assertEquals(store.snapshot().epoch,holder.acceptedInstallationEpoch)
    }
    @Test fun newSignInClearsOnlyPriorInstallationTombstones()=runTest {
        val api=LocalAccountAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))};val holder=AuthStateHolder(api,store)
        holder.initialize();assertTrue(holder.dropConfirmedMembership(1));assertTrue(holder.endLocalSession())
        holder.signIn("member","secret");assertNotNull(holder.state.value.user);assertEquals(1,holder.state.value.selectedId)
    }
}
