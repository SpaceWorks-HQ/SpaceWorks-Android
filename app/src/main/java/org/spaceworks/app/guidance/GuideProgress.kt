package org.spaceworks.app.guidance

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI preference scope only. Callers supply the actual canonical origin and accepted account/maker. */
data class GuideScope(val origin: String, val userID: String, val makerspaceID: Int, val version: Int = 1) {
    init { require(origin.isNotBlank() && userID.isNotBlank() && makerspaceID > 0 && version > 0) }
    override fun toString() = "GuideScope(redacted)"
}

interface GuideStore {
    fun get(key: String): Boolean
    fun set(key: String, value: Boolean)
}

/** Demo store is transient and must never be backed by the live preference file. */
class MemoryGuideStore : GuideStore {
    private val flags = mutableMapOf<String, Boolean>()
    @Synchronized override fun get(key: String) = flags[key] ?: false
    @Synchronized override fun set(key: String, value: Boolean) { flags[key] = value }
    @Synchronized fun clear() { flags.clear() }
}

class GuideProgress(val scope: GuideScope, private val store: GuideStore) {
    val key = key(scope)
    private val mutable = MutableStateFlow(store.get(key))
    val dismissed = mutable.asStateFlow()
    fun dismiss() { store.set(key, true); mutable.value = true }
    fun resetTips() { store.set(key, false); mutable.value = false }

    companion object {
        fun key(scope: GuideScope): String {
            val digest = MessageDigest.getInstance("SHA-256")
            // Length framing keeps separators, Unicode and unusual user labels unambiguous.
            for (value in listOf("start-here", scope.origin, scope.userID, scope.makerspaceID.toString(), scope.version.toString())) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
            }
            return "usage-guide." + digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
