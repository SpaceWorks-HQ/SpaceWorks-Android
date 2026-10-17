package org.spaceworks.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.auth.EncryptedTokenStore
import org.spaceworks.app.network.TokenPair

class KeystoreTest {
    @Test fun keystoreEncryptedSessionRestoresAtomicallyAndIsolatesServers() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val first="https://storage-test.example.invalid/"
        val second="https://storage-test-other.example.invalid/"
        val store=EncryptedTokenStore.android(context,first)
        store.replace(null);EncryptedTokenStore.android(context,second).replace(null)
        try {
            store.replace(TokenPair("first-access-sensitive","first-refresh-sensitive"))
            assertEquals(store.read(),EncryptedTokenStore.android(context,first).read())
            assertNull(EncryptedTokenStore.android(context,second).read())
            context.noBackupFilesDir.listFiles()!!.filter {it.name.startsWith("session-")}.forEach {
                assertFalse(it.readBytes().decodeToString().contains("sensitive"))
            }
            store.replace(TokenPair("rotated-access","rotated-refresh"))
            assertEquals(TokenPair("rotated-access","rotated-refresh"),EncryptedTokenStore.android(context,first).read())
            store.replace(null);assertNull(EncryptedTokenStore.android(context,first).read())
        } finally {store.replace(null)}
    }
}
