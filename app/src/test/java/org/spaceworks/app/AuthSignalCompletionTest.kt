package org.spaceworks.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.auth.*
import org.spaceworks.app.network.*
import org.spaceworks.generated.model.*

@OptIn(ExperimentalCoroutinesApi::class)
class AuthSignalCompletionTest {
    private fun store():EncryptedTokenStore {
        val disk=object:SessionFile {
            var bytes:ByteArray?=null
            override fun read()=bytes
            override fun write(bytes:ByteArray){this.bytes=bytes.copyOf()}
            override fun delete(){bytes=null}
        }
        val cipher=object:SessionCipher {
            override fun seal(plain:ByteArray)=plain.copyOf()
            override fun open(sealed:ByteArray)=sealed.copyOf()
            override fun destroy()=Unit
        }
        return EncryptedTokenStore(disk,cipher).apply {replace(TokenPair("old","old-r"))}
    }
    private class Identity:AuthGateway {
        val logoutEntered=CompletableDeferred<Unit>()
        val logoutRelease=CompletableDeferred<Unit>()
        var failure:ApiFailure?=null
        override suspend fun config()=PublicConfig(false,100)
        override suspend fun me():AuthUserPayload {
            failure?.let {throw it}
            return AuthUserPayload("member",1,"member","member@example.invalid","Member","",true,"member",false,true,emptyList())
        }
        override suspend fun login(username:String,password:String):DeviceLoginResult=error("Unexpected login")
        override suspend fun enroll(challenge:String):MFAEnrollment=error("Unexpected enrollment")
        override suspend fun verify(challenge:String,code:String,enrollment:Boolean):MFACompleted=error("Unexpected verification")
        override suspend fun changePassword(current:String,replacement:String)=Unit
        override suspend fun logout(){logoutEntered.complete(Unit);logoutRelease.await()}
    }
    @Test fun ownLogoutSignalKeepsCompletionNoticeAndVisibleThrottleDeadline()=runTest {
        val store=store();val api=Identity();var privateData=true
        val holder=AuthStateHolder(api,store,now={0L},onSessionEnded={privateData=false})
        holder.initialize()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){store.installationEpoch.collect {holder.invalidateInstallation(it)}}
        api.failure=ApiFailure(429,"Wait",null,60);holder.foreground();api.failure=null
        assertEquals(60000L,holder.state.value.retryAtMillis)
        val logout=launch {holder.signOut()};api.logoutEntered.await()
        try {
            assertNull(holder.state.value.user);assertFalse(privateData);assertTrue(holder.state.value.busy)
            assertNotNull(store.read())
        } finally {api.logoutRelease.complete(Unit)}
        logout.join();runCurrent()
        assertNull(store.read());assertNull(holder.state.value.user);assertFalse(holder.state.value.busy)
        assertEquals("Signed out.",holder.state.value.notice)
        assertEquals(60000L,holder.state.value.retryAtMillis)
    }
    @Test fun temporaryPasswordAckClearsPrivateIdentityBeforeDelayedCleanupAndKeepsNotice()=runTest {
        val store=store();val api=Identity();var privateData=true
        val holder=AuthStateHolder(api,store,onSessionEnded={privateData=false});holder.initialize()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){store.installationEpoch.collect {holder.invalidateInstallation(it)}}
        val change=launch {holder.changePassword("old-password","replacement-password","replacement-password")}
        api.logoutEntered.await()
        try {
            assertNull(holder.state.value.user);assertNull(holder.acceptedInstallationEpoch)
            assertFalse(privateData);assertTrue(holder.state.value.busy)
            assertEquals("Password updated. Sign in again.",holder.state.value.notice)
            assertNotNull(store.read())
        } finally {api.logoutRelease.complete(Unit)}
        change.join();runCurrent()
        assertNull(store.read());assertNull(holder.state.value.user);assertFalse(holder.state.value.busy)
        assertEquals("Password updated. Sign in again.",holder.state.value.notice)
    }
}
