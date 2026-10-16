package org.spaceworks.app.account

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.auth.*
import org.spaceworks.app.network.TokenPair

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InstallationSignalTest {
    private class Disk:SessionFile {
        var bytes:ByteArray?=null;var failWrite=false;var failDelete=false
        override fun read()=bytes
        override fun write(bytes:ByteArray){if(failWrite)error("write");this.bytes=bytes}
        override fun delete(){if(failDelete)error("delete");bytes=null}
    }
    private class Cipher:SessionCipher {
        var failDestroy=false
        override fun seal(plain:ByteArray)=plain.copyOf()
        override fun open(sealed:ByteArray)=sealed.copyOf()
        override fun destroy(){if(failDestroy)error("destroy")}
    }
    @Test fun signalPublishesOnlySuccessfulInstallationAndClearNotRotationOrStaleCas()=runTest {
        val store=EncryptedTokenStore(Disk(),Cipher());val seen=mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){store.installationEpoch.collect {seen+=it}}
        store.replace(TokenPair("a","r"));val first=store.snapshot()
        assertTrue(store.rotateIfCurrent(first,TokenPair("b","s")))
        assertFalse(store.rotateIfCurrent(first,null));assertEquals(listOf(0L,1L),seen)
        assertTrue(store.rotateIfCurrent(store.snapshot(),null));assertEquals(listOf(0L,1L,2L),seen)
        store.replace(TokenPair("b","s"));assertEquals(listOf(0L,1L,2L,3L),seen)
        assertEquals(store.snapshot().epoch,store.installationEpoch.value)
    }
    @Test fun failedWriteDoesNotPublishAnInstallationOrReplaceTheAcceptedPair()=runTest {
        val disk=Disk();val store=EncryptedTokenStore(disk,Cipher());store.replace(TokenPair("a","r"))
        val old=store.snapshot();disk.failWrite=true
        assertThrows(IllegalStateException::class.java){store.replace(TokenPair("b","s"))}
        assertEquals(old,store.snapshot());assertEquals(old.epoch,store.installationEpoch.value)
    }
    @Test fun failedClearStillPublishesAfterCacheIsEmptyForBothFailureModes()=runTest {
        for(cipherFailure in listOf(true,false)) {
            val disk=Disk();val cipher=Cipher();val store=EncryptedTokenStore(disk,cipher)
            store.replace(TokenPair("a","r"));val old=store.snapshot()
            val observed=mutableListOf<Pair<Long,Boolean>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                store.installationEpoch.collect {observed+=it to (store.read()==null)}
            }
            if(cipherFailure)cipher.failDestroy=true else disk.failDelete=true
            assertThrows(IllegalStateException::class.java){store.replace(null)}
            assertNull(store.read());assertEquals(old.epoch+1,store.installationEpoch.value)
            assertEquals((old.epoch+1) to true,observed.last())
        }
    }
    @Test fun conditionalFailedClearAlsoInvalidatesEveryObserver() {
        val disk=Disk();val cipher=Cipher();val store=EncryptedTokenStore(disk,cipher)
        store.replace(TokenPair("a","r"));val old=store.snapshot();disk.failDelete=true
        assertThrows(IllegalStateException::class.java){store.rotateIfCurrent(old,null)}
        assertNull(store.read());assertEquals(old.epoch+1,store.installationEpoch.value)
    }
}
