package org.spaceworks.app.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.*
import org.openapitools.client.infrastructure.UUIDAdapter
import org.openapitools.client.infrastructure.OffsetDateTimeAdapter
import org.openapitools.client.infrastructure.URIAdapter
import org.openapitools.client.infrastructure.BigDecimalAdapter
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import org.spaceworks.generated.api.AuthApi
import org.spaceworks.generated.api.MFAApi
import org.spaceworks.generated.api.PlatformApi
import org.spaceworks.generated.api.SocialAuthApi
import org.spaceworks.generated.api.HealthApi
import org.spaceworks.generated.api.DeviceAuthApi
import org.spaceworks.generated.model.DeviceRefresh
import org.spaceworks.generated.model.HardwareRequestError
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object ServerAddress {
    fun parse(value: String, debug: Boolean): HttpUrl {
        val url = try { value.trim().toHttpUrl() } catch (_: IllegalArgumentException) { throw IllegalArgumentException("Enter a valid HTTPS server URL.") }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null && url.encodedPath == "/") { "Enter a valid HTTPS server URL." }
        require(url.isHttps || (debug && url.host in setOf("localhost", "10.0.2.2"))) { "Enter a valid HTTPS server URL." }
        return url
    }
}
// Local application state, not a transport model. Production uses a Keystore-backed TokenStore.
data class TokenPair(val access: String, val refresh: String) { override fun toString() = "TokenPair(redacted)" }
interface TokenStore { fun read(): TokenPair?; fun replace(pair: TokenPair?) }
class MemoryTokenStore : TokenStore {
    @Volatile private var pair: TokenPair? = null
    override fun read() = pair
    @Synchronized override fun replace(pair: TokenPair?) { this.pair = pair }
}
class RefreshCoordinator(private val store: TokenStore, private val rotate: suspend (String) -> TokenPair) {
    private val mutex = Mutex()
    suspend fun refresh(rejectedAccess: String): TokenPair? = mutex.withLock {
        val current = store.read() ?: return@withLock null
        if (current.access != rejectedAccess) return@withLock current
        // Rotation may consume the one-use refresh token even if its caller leaves the screen.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            try { rotate(current.refresh).also(store::replace) }
            catch (failure: Exception) { store.replace(null); throw failure }
        }
    }
}
class RequestHeaders(private val store: TokenStore, private val makerspace: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .removeHeader("Cookie").removeHeader("Origin").removeHeader("Referer")
            .removeHeader("Authorization").removeHeader("X-Makerspace-Id")
        store.read()?.let { request.header("Authorization", "Bearer ${it.access}") }
        makerspace()?.let { request.header("X-Makerspace-Id", it) }
        return chain.proceed(request.build())
    }
}
class SessionAuthenticator(private val coordinator: RefreshCoordinator) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null
        val rejected = response.request.header("Authorization")?.removePrefix("Bearer ") ?: return null
        val pair = try { kotlinx.coroutines.runBlocking { coordinator.refresh(rejected) } } catch (_: Exception) { return null }
        return pair?.let { response.request.newBuilder().header("Authorization", "Bearer ${it.access}").build() }
    }
}

// OkHttp retries selected HTTP responses independently of retryOnConnectionFailure. Hide those
// statuses only from its follow-up interceptor, then restore the exact response for Retrofit.
// Metadata is a local request tag: nothing extra is sent to the deployment.
private data class OriginalResponse(val code: Int, val message: String, val headers: Headers)
private class ServiceResponseState { var original: OriginalResponse? = null }
private class PreventServiceReplay : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code !in setOf(408, 421, 503)) return response
        checkNotNull(chain.request().tag(ServiceResponseState::class.java)).original =
            OriginalResponse(response.code, response.message, response.headers)
        return response.newBuilder().code(503).header("Retry-After", "1").build()
    }
}
private class RestoreServiceResponse : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val local = ServiceResponseState()
        val request = chain.request().newBuilder().tag(ServiceResponseState::class.java, local).build()
        val response = chain.proceed(request)
        val original = local.original ?: return response
        return response.newBuilder().code(original.code).message(original.message).headers(original.headers)
            .request(response.request.newBuilder().tag(ServiceResponseState::class.java, null).build()).build()
    }
}
data class ApiFailure(val status: Int, val detail: String, val code: String?, val retryAfterSeconds: Long?) : Exception(detail)
object Errors {
    private val json = Json { ignoreUnknownKeys = true }
    fun map(status: Int, body: String?, retryAfter: String? = null, now: Instant = Instant.now()): ApiFailure {
        val error = try { body?.let { json.decodeFromString<HardwareRequestError>(it) } } catch (_: Exception) { null }
        val envelope = try { body?.let { json.parseToJsonElement(it) as? JsonObject } } catch (_: Exception) { null }
        val code = error?.code ?: (envelope?.get("code") as? JsonPrimitive)?.contentOrNull
        val serverDetail = error?.detail ?: (envelope?.get("detail") as? JsonPrimitive)?.contentOrNull
        fun messages(value: JsonElement, path:String): List<Pair<String,String>> = when (value) {
            is JsonPrimitive -> listOfNotNull(value.contentOrNull?.takeIf {it.isNotBlank()}?.let {path to it})
            is JsonArray -> value.flatMap {messages(it,path)}
            is JsonObject -> value.entries.flatMap {(field,nested)->messages(nested,"$path.$field")}
        }
        val fieldDetail = if(status==400) envelope?.filterKeys {it!="code"&&it!="detail"}?.entries
            ?.flatMap {(field,value)->messages(value,field)}?.groupBy({it.first},{it.second})?.entries
            ?.joinToString("\n") {(path,parts)->"$path: ${parts.joinToString(" ")}"}?.takeIf {it.isNotBlank()} else null
        val detail = when (code) {
            "device_superadmin_not_supported" -> "Use the web console"
            "payment_rail_conflict" -> "Finish this payment on the web."
            "payments_unavailable" -> "Online payment is unavailable. Try again later or pay at the desk."
            else -> serverDetail?.takeIf { it.isNotBlank() } ?: fieldDetail ?: when(status) {
                400 -> "Check the details and try again."
                401 -> "Please sign in again."
                403 -> "You do not have permission to do this."
                404 -> "This item wasn't found."
                409 -> "This changed. Refresh and try again."
                429 -> "Too many requests. Try again later."
                503 -> "The server is unavailable. Try again later."
                else -> "Couldn't connect. Try again."
            }
        }
        val seconds = retryAfter?.toLongOrNull()?.coerceAtLeast(0) ?: try {
            retryAfter?.let { (ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().epochSecond - now.epochSecond).coerceAtLeast(0) }
        } catch (_: Exception) { null }
        return ApiFailure(status,detail,code,seconds)
    }
}
object Pagination {
    fun next(base: HttpUrl, value: String?): HttpUrl? = value?.let {
        val next = requireNotNull(base.resolve(it)) { "Invalid next page." }
        require(next.scheme == base.scheme && next.host == base.host && next.port == base.port && next.username.isEmpty() && next.password.isEmpty()) { "Next page must belong to this server." }
        next
    }
}
class ApiSession(val server: HttpUrl, store: TokenStore = MemoryTokenStore(), makerspace: () -> String? = { null }) {
    private val json = Json {
        ignoreUnknownKeys = true; explicitNulls = false
        serializersModule = SerializersModule {
            contextual(java.util.UUID::class, UUIDAdapter)
            contextual(java.time.OffsetDateTime::class, OffsetDateTimeAdapter)
            contextual(java.net.URI::class, URIAdapter)
            contextual(java.math.BigDecimal::class, BigDecimalAdapter)
        }
    }
    // Redirects are surfaced to callers; credentials never follow another origin.
    private val baseClient = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(45,java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor(RestoreServiceResponse()).addNetworkInterceptor(PreventServiceReplay()).build()
    private fun retrofit(client: OkHttpClient) = Retrofit.Builder().baseUrl(server)
        .client(client).addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    fun <T> publicApi(type: Class<T>): T = retrofit(baseClient).create(type)
    fun <T> scopedApi(type: Class<T>, makerId: Int, onCall: (Call) -> Unit = {}): T {
        require(makerId > 0)
        val scoped = client.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("X-Makerspace-Id", makerId.toString()).build())
        }.build()
        // Observe only this generated service instance's calls so streamed consumers can
        // cancel their socket without closing a ResponseBody concurrently with its reader.
        val factory = Call.Factory { request -> scoped.newCall(request).also(onCall) }
        return Retrofit.Builder().baseUrl(server).callFactory(factory)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(type)
    }
    val devicePublic = retrofit(baseClient).create(DeviceAuthApi::class.java)
    val platform = retrofit(baseClient).create(PlatformApi::class.java)
    val mfa = retrofit(baseClient).create(MFAApi::class.java)
    val authPublic = retrofit(baseClient).create(AuthApi::class.java)
    val socialPublic = retrofit(baseClient).create(SocialAuthApi::class.java)
    private val refreshApi = devicePublic
    val coordinator = RefreshCoordinator(store) { refresh ->
        val response = refreshApi.apiV1AuthDeviceRefreshCreate(DeviceRefresh(refresh))
        val result = response.body() ?: throw Errors.map(response.code(),response.errorBody()?.string(),response.headers()["Retry-After"])
        TokenPair(result.access,result.refresh)
    }
    val client: OkHttpClient = baseClient.newBuilder().dispatcher(Dispatcher()).addInterceptor(RequestHeaders(store,makerspace))
        .authenticator(SessionAuthenticator(coordinator)).build()
    val auth = retrofit(client).create(AuthApi::class.java)
    val deviceAuthenticated = retrofit(client).create(DeviceAuthApi::class.java)
    val health: HealthApi = retrofit(client).create(HealthApi::class.java)
    suspend fun checkHealth() {
        val response = health.apiV1HealthRetrieve()
        if (!response.isSuccessful) throw Errors.map(response.code(),response.errorBody()?.string(),response.headers()["Retry-After"])
        checkNotNull(response.body()) { "The server returned an empty health response." }
    }
}
