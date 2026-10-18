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

private const val EPOCH_USER="""{"session_authority":"member","id":1,"username":"member","email":"member@example.invalid","display_name":"Member","phone":"","email_verified":true,"role":"member","is_superuser":false,"must_change_password":false,"makerspaces":[{"id":1,"slug":"fab-lab","role":"member","role_id":1,"role_ids":[1],"role_slugs":["member"],"role_names":["Member"],"role_name":"Member","role_slug":"member","source":"membership","actions":[],"can_configure_machine_types":false,"is_machine_only":false,"can_refer":false,"can_verify":false,"verified_at":null,"referrals_enabled":false}]}"""
private class EpochAuth:AuthGateway {
    var entered:CompletableDeferred<Unit>?=null
    var release:CompletableDeferred<Unit>?=null
    var failure:Exception?=null
    var logouts=0
    override suspend fun config()=PublicConfig(false,100)
    override suspend fun login(username:String,password:String):DeviceLoginResult=error("unused")
    override suspend fun enroll(challenge:String):MFAEnrollment=error("unused")
    override suspend fun verify(challenge:String,code:String,enrollment:Boolean):MFACompleted=error("unused")
    override suspend fun me():AuthUserPayload {entered?.complete(Unit);release?.await();failure?.let {throw it};return Json.decodeFromString(EPOCH_USER)}
    override suspend fun logout(){logouts++}
}
@OptIn(ExperimentalCoroutinesApi::class)
class AuthEpochTest {
    @Test fun delayedOldIdentity401CannotClearLaterLogin()=runTest {
        val api=EpochAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))};val holder=AuthStateHolder(api,store)
        holder.initialize();api.entered=CompletableDeferred();api.release=CompletableDeferred();api.failure=ApiFailure(401,"Old expired",null,null)
        val read=launch {holder.foreground()};api.entered!!.await();store.replace(null);store.replace(TokenPair("new-account","new-refresh"));api.release!!.complete(Unit);read.join()
        assertEquals(TokenPair("new-account","new-refresh"),store.read());assertNull(holder.state.value.user);assertNull(holder.state.value.selectedId)
    }
    @Test fun delayedOldIdentitySuccessCannotPublishAfterLaterLogin()=runTest {
        val api=EpochAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))};val holder=AuthStateHolder(api,store)
        holder.initialize();api.entered=CompletableDeferred();api.release=CompletableDeferred()
        val read=launch {holder.foreground()};api.entered!!.await();store.replace(TokenPair("new-account","new-refresh"));api.release!!.complete(Unit);read.join()
        assertEquals(TokenPair("new-account","new-refresh"),store.read());assertNull(holder.state.value.user)
    }
    @Test fun staleControllerCannotLogoutNewAccountsGrant()=runTest {
        val api=EpochAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))};val holder=AuthStateHolder(api,store)
        holder.initialize();store.replace(TokenPair("new-account","new-refresh"));holder.signOut()
        assertEquals(0,api.logouts);assertEquals(TokenPair("new-account","new-refresh"),store.read());assertNull(holder.state.value.user)
    }
    @Test fun localPrivateCachesClearSynchronouslyOnSessionEndWithoutUiCollection()=runTest {
        val api=EpochAuth();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))};var privateData="old account data"
        val holder=AuthStateHolder(api,store,onSessionEnded={privateData=""})
        holder.initialize();holder.signOut()
        assertEquals("",privateData);assertNull(store.read());assertNull(holder.state.value.user)
    }

}
