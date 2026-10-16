package org.spaceworks.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.spaceworks.app.network.ServerAddress
import org.spaceworks.app.network.ApiFailure
import okhttp3.HttpUrl

data class ConnectionState(val url: String = "", val connecting: Boolean = false, val connected: Boolean = false, val error: String? = null)
class ConnectionStateHolder(initialUrl: String, val locked: Boolean, private val debug: Boolean,
    private val health: suspend (HttpUrl) -> Unit, private val save: (String) -> Unit, private val now: () -> Long = System::currentTimeMillis) {
    private val mutable = MutableStateFlow(ConnectionState(url = initialUrl))
    val state = mutable.asStateFlow()
    private var retryAt = 0L
    fun edit(value: String) { if (!locked && !state.value.connecting) { retryAt=0; mutable.value = state.value.copy(url = value, error = null) } }
    fun changeServer() { if (!locked) { retryAt=0; save(""); mutable.value = ConnectionState() } }
    suspend fun connect() {
        if (state.value.connecting || now() < retryAt) return
        mutable.value = state.value.copy(connecting = true, error = null)
        val url = try { ServerAddress.parse(state.value.url,debug) } catch (_: IllegalArgumentException) {
            mutable.value = state.value.copy(connecting=false,error="Enter a valid HTTPS server URL.")
            return
        }
        try {
            health(url)
            save(url.toString())
            mutable.value = state.value.copy(url = url.toString(), connecting = false, connected = true)
        } catch (cancelled: CancellationException) {
            mutable.value = state.value.copy(connecting = false)
            throw cancelled
        } catch (failure: Exception) {
            if (failure is ApiFailure && failure.status == 429) retryAt = now() + (failure.retryAfterSeconds ?: 60L) * 1000L
            mutable.value = state.value.copy(connecting = false, error = (failure as? ApiFailure)?.detail ?: "Couldn't connect. Try again.")
        }
    }
}
