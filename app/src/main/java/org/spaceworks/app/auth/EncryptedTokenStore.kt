package org.spaceworks.app.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.spaceworks.app.network.TokenSnapshot
import org.spaceworks.app.network.TokenPair
import org.spaceworks.app.network.TokenStore
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SessionCipher { fun seal(plain: ByteArray): ByteArray; fun open(sealed: ByteArray): ByteArray; fun destroy() }
interface SessionFile { fun read(): ByteArray?; fun write(bytes: ByteArray); fun delete() }
// Both tokens are one encrypted record. A failed disk write never publishes a half-rotated pair.
class EncryptedTokenStore(private val file: SessionFile, private val cipher: SessionCipher) : TokenStore {
    private var epoch=0L
    private val mutableInstallationEpoch=MutableStateFlow(epoch)
    /** Non-secret process-wide installation changes. Token rotation keeps its identity. */
    val installationEpoch=mutableInstallationEpoch.asStateFlow()
    private var loaded = false
    private var cached: TokenPair? = null
    @Synchronized override fun read(): TokenPair? {
        if (!loaded) {
            cached = try {
                file.read()?.let { bytes ->
                    val values = Json.decodeFromString<List<String>>(cipher.open(bytes).decodeToString())
                    require(values.size == 2 && values.all(String::isNotBlank))
                    TokenPair(values[0],values[1])
                }
            } catch (_: Exception) { cipher.destroy(); file.delete(); null }
            loaded = true
        }
        return cached
    }
    @Synchronized override fun snapshot()=TokenSnapshot(epoch,read())
    @Synchronized override fun replace(pair:TokenPair?) {
        if(pair==null) {
            epoch++
            try {writePair(null)} finally {mutableInstallationEpoch.value=epoch}
        } else {
            writePair(pair)
            epoch++
            mutableInstallationEpoch.value=epoch
        }
    }
    @Synchronized override fun rotateIfCurrent(expected:TokenSnapshot,pair:TokenPair?):Boolean {
        if(expected.pair==null || expected.epoch!=epoch || expected.pair!=read())return false
        if(pair==null) {
            epoch++
            try {writePair(null)} finally {mutableInstallationEpoch.value=epoch}
        } else writePair(pair)
        return true
    }
    private fun writePair(pair:TokenPair?) {
        if (pair == null) {
            // Destroy the key before deleting ciphertext: an interrupted/failed delete cannot restore a session.
            try {cipher.destroy()} finally {
                try {file.delete()} finally {cached=null;loaded=true}
            }
        } else {
            require(pair.access.isNotBlank() && pair.refresh.isNotBlank())
            val plain=Json.encodeToString(listOf(pair.access,pair.refresh)).encodeToByteArray()
            try { file.write(cipher.seal(plain)) } finally { plain.fill(0) }
            cached=pair;loaded=true
        }
    }
    companion object {
        private val shared=java.util.concurrent.ConcurrentHashMap<String,EncryptedTokenStore>()
        /** Every controller in this process must share the origin's epoch/atomic rotation lock. */
        fun sharedAndroid(context:Context,origin:String):EncryptedTokenStore {
            val scope=context.noBackupFilesDir.absolutePath+"\n"+origin
            return shared.computeIfAbsent(scope){android(context,origin)}
        }
        // A fresh factory also permits actual Keystore restoration to be verified in instrumentation.
        fun android(context: Context, origin: String): EncryptedTokenStore {
            val key=MessageDigest.getInstance("SHA-256").digest(origin.encodeToByteArray()).joinToString("") { "%02x".format(it) }
            val atomic=AtomicFile(File(context.noBackupFilesDir,"session-$key.bin"))
            val disk=object:SessionFile {
                override fun read()=if (atomic.baseFile.exists()) atomic.readFully() else null
                override fun write(bytes:ByteArray) {
                    val output=atomic.startWrite()
                    try {output.write(bytes);atomic.finishWrite(output)} catch(f:Exception) {atomic.failWrite(output);throw f}
                }
                override fun delete() {atomic.delete()}
            }
            return EncryptedTokenStore(disk,AndroidSessionCipher("spaceworks.session.$key"))
        }
    }
}
class AndroidSessionCipher(private val alias:String):SessionCipher {
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    override fun seal(plain:ByteArray):ByteArray {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key())}
        val body=cipher.doFinal(plain)
        return byteArrayOf(cipher.iv.size.toByte())+cipher.iv+body
    }
    override fun open(sealed:ByteArray):ByteArray {
        require(sealed.isNotEmpty())
        val count=sealed[0].toInt() and 255
        require(count==12 && sealed.size>count+1)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,sealed.copyOfRange(1,count+1)))}
        return cipher.doFinal(sealed,count+1,sealed.size-count-1)
    }
    override fun destroy() {KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(alias)}}
}
