package org.spaceworks.app

import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.auth.*
import org.spaceworks.app.network.TokenPair
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class EncryptedTokenStoreTest {
    private class Disk:SessionFile {var bytes:ByteArray?=null;var fail=false;override fun read()=bytes;override fun write(bytes:ByteArray){if(fail)error("Disk full");this.bytes=bytes};override fun delete(){bytes=null}}
    private class Crypto:SessionCipher {
        var key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey()
        override fun seal(plain:ByteArray):ByteArray {val c=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key)};return c.iv+c.doFinal(plain)}
        override fun open(sealed:ByteArray):ByteArray=Cipher.getInstance("AES/GCM/NoPadding").run {init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,sealed.copyOfRange(0,12)));doFinal(sealed.copyOfRange(12,sealed.size))}
        override fun destroy(){key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey()}
    }
    @Test fun oldSnapshotCannotWriteOrClearEncryptedNewLogin() {
        val disk=Disk();val store=EncryptedTokenStore(disk,Crypto());store.replace(TokenPair("old","old-refresh"));val old=store.snapshot()
        store.replace(null);store.replace(TokenPair("new","new-refresh"));val encrypted=disk.bytes!!.copyOf()
        assertFalse(store.rotateIfCurrent(old,TokenPair("stale","stale-refresh")));assertFalse(store.rotateIfCurrent(old,null))
        assertArrayEquals(encrypted,disk.bytes);assertEquals(TokenPair("new","new-refresh"),store.read());assertNotEquals(old.epoch,store.snapshot().epoch)
    }
    @Test fun sameSessionEncryptedRotationKeepsEpochButReinstallationInvalidatesSnapshot() {
        val store=EncryptedTokenStore(Disk(),Crypto());val pair=TokenPair("a","r");store.replace(pair);val old=store.snapshot()
        assertTrue(store.rotateIfCurrent(old,TokenPair("b","s")));assertEquals(old.epoch,store.snapshot().epoch)
        val rotated=store.snapshot();store.replace(TokenPair("b","s"));assertFalse(store.rotateIfCurrent(rotated,null));assertEquals(TokenPair("b","s"),store.read())
    }
    @Test fun failedEncryptedRotationIsConditionallyClearedByCoordinator()=kotlinx.coroutines.runBlocking {
        val disk=Disk();val store=EncryptedTokenStore(disk,Crypto());store.replace(TokenPair("a","r"));val epoch=store.snapshot().epoch;disk.fail=true
        val coordinator=org.spaceworks.app.network.RefreshCoordinator(store){TokenPair("b","s")}
        assertTrue(runCatching {coordinator.refresh("a",epoch)}.isFailure);assertNull(store.read());assertNull(disk.bytes)
    }
    @Test fun ciphertextRoundtripsAndFailedWriteNeverPublishesPartialRotation() {
        val disk=Disk();val crypto=Crypto();val store=EncryptedTokenStore(disk,crypto);val old=TokenPair("access-secret","refresh-secret")
        store.replace(old);assertFalse(disk.bytes!!.decodeToString().contains("secret"));assertEquals(old,EncryptedTokenStore(disk,crypto).read())
        disk.fail=true;assertThrows(IllegalStateException::class.java){store.replace(TokenPair("new-a","new-r"))};assertEquals(old,store.read());assertEquals(old,EncryptedTokenStore(disk,crypto).read())
    }
    @Test fun corruptedAndSignedOutRecordsCannotRestoreTokens() {
        val disk=Disk();val crypto=Crypto();val store=EncryptedTokenStore(disk,crypto);store.replace(TokenPair("a","r"));disk.bytes!![15]=(disk.bytes!![15].toInt() xor 1).toByte()
        assertNull(EncryptedTokenStore(disk,crypto).read());assertNull(disk.bytes)
        store.replace(TokenPair("a","r"));val ciphertext=disk.bytes!!.copyOf();store.replace(null);disk.bytes=ciphertext
        assertNull(EncryptedTokenStore(disk,crypto).read())
    }
    @Test fun signOutClearsCacheAndAttemptsBothStoresWhenKeystoreFails() {
        val disk=Disk();val base=Crypto();var destroyed=false
        val broken=object:SessionCipher {
            override fun seal(plain:ByteArray)=base.seal(plain)
            override fun open(sealed:ByteArray)=base.open(sealed)
            override fun destroy(){destroyed=true;error("Keystore failed")}
        }
        val store=EncryptedTokenStore(disk,broken);store.replace(TokenPair("a","r"))
        assertThrows(IllegalStateException::class.java){store.replace(null)}
        assertTrue(destroyed);assertNull(disk.bytes);assertNull(store.read())
    }
    @Test fun failedCiphertextDeleteStillClearsKeyAndCachedPair() {
        val disk=Disk();val crypto=Crypto();val broken=object:SessionFile {
            override fun read()=disk.read()
            override fun write(bytes:ByteArray)=disk.write(bytes)
            override fun delete(){error("Delete failed")}
        }
        val store=EncryptedTokenStore(broken,crypto);store.replace(TokenPair("a","r"))
        assertThrows(IllegalStateException::class.java){store.replace(null)};assertNull(store.read())
        assertNull(EncryptedTokenStore(disk,crypto).read())
    }
}
