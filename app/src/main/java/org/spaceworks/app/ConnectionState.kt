package org.spaceworks.app

import org.spaceworks.app.network.retryDeadlineMillis

import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.MobileCopyException
import org.spaceworks.app.platform.failure
import org.spaceworks.app.copy.CoreStrings
import org.spaceworks.app.copy.apiFailure

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.spaceworks.app.network.ServerAddress
import org.spaceworks.app.network.ApiFailure
import okhttp3.HttpUrl

data class ConnectionState(val url: String = "", val connecting: Boolean = false, val connected: Boolean = false, val error: String? = null)
class ConnectionStateHolder(initialUrl: String, val locked: Boolean, private val debug: Boolean,
    private val health: suspend (HttpUrl) -> Unit, private val save: (String) -> Unit, private val now: () -> Long = System::currentTimeMillis,private val text:MobileText=MobileText.ENGLISH) {
    constructor(initialUrl:String,locked:Boolean,debug:Boolean,health:suspend(HttpUrl)->Unit,save:(String)->Unit,now:()->Long)
        :this(initialUrl,locked,debug,health,save,now,MobileText.ENGLISH)

    private val mutable = MutableStateFlow(ConnectionState(url = initialUrl))
    val state = mutable.asStateFlow()
    private var retryAt = 0L
    fun edit(value: String) { if (!locked && !state.value.connecting) { retryAt=0; mutable.value = state.value.copy(url = value, error = null) } }
    fun changeServer() { if (!locked) { retryAt=0; save(""); mutable.value = ConnectionState() } }
    suspend fun connect() {
        if (state.value.connecting || now() < retryAt) return
        mutable.value = state.value.copy(connecting = true, error = null)
        val url = try { ServerAddress.parse(state.value.url,debug) } catch (_: IllegalArgumentException) {
            mutable.value = state.value.copy(connecting=false,error=text(CoreStrings.CORE_ENTER_A_VALID_HTTPS_SERVER_URL))
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
            if (failure is ApiFailure && failure.status == 429) retryAt = maxOf(retryAt, retryDeadlineMillis(now(), failure.retryAfterSeconds ?: 60L))
            mutable.value = state.value.copy(connecting = false, error = (failure as? ApiFailure)?.let{text.apiFailure(it)} ?: text(CoreStrings.CORE_COULDN_T_CONNECT_TRY_AGAIN))
        }
    }
}
