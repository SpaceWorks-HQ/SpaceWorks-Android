package org.spaceworks.app.account

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.auth.*
import org.spaceworks.app.network.TokenPair
import org.spaceworks.generated.model.*

@OptIn(ExperimentalCoroutinesApi::class)
class AccountInstallationIntegrationTest {
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
        var entered:CompletableDeferred<Unit>?=null;var release:CompletableDeferred<Unit>?=null
        var reads=0
        override suspend fun config()=PublicConfig(false,100)
        override suspend fun me():AuthUserPayload {
            reads++;entered?.complete(Unit);release?.await()
            return AuthUserPayload(1,"member","member@example.invalid","Member","",true,"member",false,false,emptyList())
        }
        override suspend fun login(username:String,password:String):DeviceLoginResult=error("Unexpected login")
        override suspend fun enroll(challenge:String):MFAEnrollment=error("Unexpected enrollment")
        override suspend fun verify(challenge:String,code:String,enrollment:Boolean):MFACompleted=error("Unexpected verification")
        override suspend fun logout()=error("Unexpected remote logout")
    }
    @Test fun sharedInstallationSignalClearsBothControllersWithoutTouchingNewTokensOrSharedSelection()=runTest {
        val store=store();val firstApi=Identity();val secondApi=Identity()
        var firstClears=0;var secondClears=0;var selectionWrites=0
        val first=AuthStateHolder(firstApi,store,saveSelection={selectionWrites++},onSessionEnded={firstClears++})
        val second=AuthStateHolder(secondApi,store,saveSelection={selectionWrites++},onSessionEnded={secondClears++})
        first.initialize();second.initialize();val originalWrites=selectionWrites
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){store.installationEpoch.collect {first.invalidateInstallation(it)}}
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){store.installationEpoch.collect {second.invalidateInstallation(it)}}
        store.replace(TokenPair("replacement","replacement-r"))
        assertNull(first.state.value.user);assertNull(second.state.value.user)
        assertEquals(1,firstClears);assertEquals(1,secondClears)
        assertEquals(originalWrites,selectionWrites);assertEquals(TokenPair("replacement","replacement-r"),store.read())
        assertEquals(1,firstApi.reads);assertEquals(1,secondApi.reads)
    }
    @Test fun sameEpochRotationKeepsUiButReplacementDiscardsDelayedForegroundIdentity()=runTest {
        val store=store();val api=Identity();var clears=0
        val holder=AuthStateHolder(api,store,onSessionEnded={clears++});holder.initialize()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){store.installationEpoch.collect {holder.invalidateInstallation(it)}}
        store.rotateIfCurrent(store.snapshot(),TokenPair("rotated","rotated-r"))
        assertNotNull(holder.state.value.user);assertEquals(0,clears)
        api.entered=CompletableDeferred();api.release=CompletableDeferred()
        val read=launch {holder.foreground()};runCurrent();api.entered!!.await()
        store.replace(TokenPair("new-account","new-account-r"));assertNull(holder.state.value.user)
        api.release!!.complete(Unit);read.join()
        assertNull(holder.state.value.user);assertNull(holder.state.value.error)
        assertEquals(1,clears);assertEquals(TokenPair("new-account","new-account-r"),store.read())
    }
}
