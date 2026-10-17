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
data class TokenSnapshot(val epoch:Long,val pair:TokenPair?)
interface TokenStore {
    fun read():TokenPair?
    /** A login/logout installs a new local session even when token text happens to match. */
    fun replace(pair:TokenPair?)
    fun snapshot():TokenSnapshot
    /** Same-session rotation is atomic and cannot alter a later login/logout. */
    fun rotateIfCurrent(expected:TokenSnapshot,pair:TokenPair?):Boolean
}
class MemoryTokenStore:TokenStore {
    @Volatile private var pair:TokenPair?=null
    private var epoch=0L
    override fun read()=pair
    @Synchronized override fun replace(pair:TokenPair?){epoch++;this.pair=pair}
    @Synchronized override fun snapshot()=TokenSnapshot(epoch,pair)
    @Synchronized override fun rotateIfCurrent(expected:TokenSnapshot,pair:TokenPair?):Boolean {
        if(expected.pair==null || expected.epoch!=epoch || expected.pair!=this.pair)return false
        if(pair==null)epoch++
        this.pair=pair;return true
    }
}
/** Share only the lock: API instances and their owner callbacks stay controller-local. */
private object RefreshLocks {
    private data class Entry(val store:java.lang.ref.WeakReference<TokenStore>,val mutex:Mutex)
    private val entries=mutableListOf<Entry>()
    @Synchronized fun forStore(store:TokenStore):Mutex {
        entries.removeAll {it.store.get()==null}
        entries.firstOrNull {it.store.get()===store}?.let {return it.mutex}
        return Mutex().also {entries+=Entry(java.lang.ref.WeakReference(store),it)}
    }
}
class RefreshCoordinator(private val store:TokenStore,private val rotate:suspend(String)->TokenPair) {
    private val mutex=RefreshLocks.forStore(store)
    suspend fun refresh(rejectedAccess:String,expectedEpoch:Long):TokenPair?=mutex.withLock {
        val expected=store.snapshot()
        if(expected.epoch!=expectedEpoch)return@withLock null
        val current=expected.pair?:return@withLock null
        if(current.access!=rejectedAccess)return@withLock current
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            try {
                val replacement=rotate(current.refresh)
                if(store.rotateIfCurrent(expected,replacement))replacement else null
            }catch(failure:Exception){store.rotateIfCurrent(expected,null);throw failure}
        }
    }
}
private data class SessionEpochTag(val epoch:Long)
class RequestHeaders(private val store:TokenStore,private val makerspace:()->String?):Interceptor {
    override fun intercept(chain:Interceptor.Chain):Response {
        val snapshot=store.snapshot()
        val expected=chain.request().tag(SessionEpochTag::class.java)?:SessionEpochTag(snapshot.epoch)
        if(expected.epoch!=snapshot.epoch)throw java.io.IOException("Session changed")
        val request=chain.request().newBuilder().tag(SessionEpochTag::class.java,expected)
            .removeHeader("Cookie").removeHeader("Origin").removeHeader("Referer")
            .removeHeader("Authorization").removeHeader("X-Makerspace-Id")
        snapshot.pair?.let {request.header("Authorization","Bearer ${it.access}")}
        makerspace()?.let {request.header("X-Makerspace-Id",it)}
        return chain.proceed(request.build())
    }
}
/** Recheck every physical attempt, including the gap after an authenticator builds a retry. */
private class SessionEpochGuard(private val store:TokenStore):Interceptor {
    override fun intercept(chain:Interceptor.Chain):Response {
        val epoch=chain.request().tag(SessionEpochTag::class.java)?.epoch
        if(epoch==null || store.snapshot().epoch!=epoch)throw java.io.IOException("Session changed")
        return chain.proceed(chain.request())
    }
}
class SessionAuthenticator(private val coordinator:RefreshCoordinator):Authenticator {
    override fun authenticate(route:Route?,response:Response):Request? {
        if(response.priorResponse!=null)return null
        val epoch=response.request.tag(SessionEpochTag::class.java)?.epoch?:return null
        val rejected=response.request.header("Authorization")?.removePrefix("Bearer ")?:return null
        val pair=try {kotlinx.coroutines.runBlocking {coordinator.refresh(rejected,epoch)}}catch(_:Exception){return null}
        return pair?.let {response.request.newBuilder().header("Authorization","Bearer ${it.access}").build()}
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
data class ApiFailure(val status: Int, val detail: String, val code: String?, val retryAfterSeconds: Long?,
    val localCopy: org.spaceworks.app.platform.MobileCopyKey? = null) : Exception(detail)
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
        val localCopy = when (code) {
            "device_superadmin_not_supported" -> org.spaceworks.app.copy.CoreStrings.CORE_USE_THE_WEB_CONSOLE
            "payment_rail_conflict" -> org.spaceworks.app.copy.CoreStrings.CORE_FINISH_THIS_PAYMENT_ON_THE_WEB
            "payments_unavailable" -> org.spaceworks.app.copy.CoreStrings.CORE_ONLINE_PAYMENT_IS_UNAVAILABLE_TRY_AGAIN_LATER_OR_PAY_AT_THE
            else -> if (!serverDetail.isNullOrBlank() || fieldDetail != null) null else when(status) {
                400 -> org.spaceworks.app.copy.CoreStrings.CORE_CHECK_THE_DETAILS_AND_TRY_AGAIN
                401 -> org.spaceworks.app.copy.CoreStrings.CORE_PLEASE_SIGN_IN_AGAIN
                403 -> org.spaceworks.app.copy.CoreStrings.CORE_YOU_DO_NOT_HAVE_PERMISSION_TO_DO_THIS
                404 -> org.spaceworks.app.copy.CoreStrings.CORE_THIS_ITEM_WASN_T_FOUND
                409 -> org.spaceworks.app.copy.CoreStrings.CORE_THIS_CHANGED_REFRESH_AND_TRY_AGAIN
                429 -> org.spaceworks.app.copy.CoreStrings.CORE_TOO_MANY_REQUESTS_TRY_AGAIN_LATER
                503 -> org.spaceworks.app.copy.CoreStrings.CORE_THE_SERVER_IS_UNAVAILABLE_TRY_AGAIN_LATER
                else -> org.spaceworks.app.copy.CoreStrings.CORE_COULDN_T_CONNECT_TRY_AGAIN
            }
        }
        val detail = localCopy?.english ?: serverDetail?.takeIf { it.isNotBlank() } ?: fieldDetail ?: org.spaceworks.app.copy.CoreStrings.CORE_COULDN_T_CONNECT_TRY_AGAIN.english
        val seconds = parseRetryAfterDeltaSeconds(retryAfter) ?: try {
            retryAfter?.let { (ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().epochSecond - now.epochSecond).coerceAtLeast(0) }
        } catch (_: Exception) { null }
        return ApiFailure(status,detail,code,seconds,localCopy)
    }
}
object Pagination {
    fun next(base: HttpUrl, value: String?): HttpUrl? = value?.let {
        val next = requireNotNull(base.resolve(it)) { "Invalid next page." }
        require(next.scheme == base.scheme && next.host == base.host && next.port == base.port && next.username.isEmpty() && next.password.isEmpty()) { "Next page must belong to this server." }
        next
    }
}
class ApiSession(val server: HttpUrl, private val store: TokenStore = MemoryTokenStore(), makerspace: () -> String? = { null }) {
    /** Non-secret installation identity for generated operations with several awaited calls. */
    val installationEpoch:Long get()=store.snapshot().epoch
    @Volatile private var identityEpoch:(()->Long?)?=null
    /** Controller-local identity proof; authentication bootstrap uses its explicit auth services. */
    fun bindIdentityEpoch(expected:()->Long?) {
        check(identityEpoch==null)
        identityEpoch=expected
    }
    private fun serviceEpoch():Long {
        val identity=identityEpoch?:return store.snapshot().epoch
        return identity()?:throw java.io.IOException("Session changed")
    }
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
    private fun authenticatedCalls(client:OkHttpClient,onCall:(Call)->Unit={},expectedEpoch:Long?=null):Call.Factory=Call.Factory {request->
        // Capture before enqueue: a queued old mutation must never first-send as a newly signed-in account.
        val tagged=request.newBuilder().tag(SessionEpochTag::class.java,SessionEpochTag(expectedEpoch?:store.snapshot().epoch)).build()
        client.newCall(tagged).also(onCall)
    }
    private fun retrofit(client:OkHttpClient,authenticated:Boolean=false)=Retrofit.Builder().baseUrl(server)
        .callFactory(if(authenticated)authenticatedCalls(client)else client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    fun <T> publicApi(type:Class<T>,onCall:(Call)->Unit={}):T=Retrofit.Builder().baseUrl(server)
        .callFactory(Call.Factory {request->baseClient.newCall(request).also(onCall)})
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(type)
    /** Authenticated account-global operation; no selected-maker header or fabricated maker ID. */
    fun <T> globalApi(type:Class<T>,onCall:(Call)->Unit={}):T =
        pinnedGlobalApi(type,serviceEpoch(),onCall)
    /** Narrow cleanup seam: callers must supply the immutable operation owner's epoch. */
    fun <T> pinnedGlobalApi(type:Class<T>,expectedEpoch:Long,onCall:(Call)->Unit={}):T {
        val global=client.newBuilder().addInterceptor {chain->
            chain.proceed(chain.request().newBuilder().removeHeader("X-Makerspace-Id").build())
        }.build()
        return Retrofit.Builder().baseUrl(server).callFactory(authenticatedCalls(global,onCall,expectedEpoch))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(type)
    }
    fun <T> scopedApi(type: Class<T>, makerId: Int, onCall: (Call) -> Unit = {}): T =
        pinnedScopedApi(type,makerId,serviceEpoch(),onCall)
    /** Owned push cleanup stays possible after cached private identity has been cleared. */
    fun <T> pinnedScopedApi(type:Class<T>,makerId:Int,expectedEpoch:Long,onCall:(Call)->Unit={}):T {
        require(makerId > 0)
        val scoped = client.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("X-Makerspace-Id", makerId.toString()).build())
        }.build()
        // Observe only this generated service instance's calls so streamed consumers can
        // cancel their socket without closing a ResponseBody concurrently with its reader.
        val factory = authenticatedCalls(scoped,onCall,expectedEpoch)
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
        .authenticator(SessionAuthenticator(coordinator)).addNetworkInterceptor(SessionEpochGuard(store)).build()
    val auth = retrofit(client,true).create(AuthApi::class.java)
    val deviceAuthenticated = retrofit(client,true).create(DeviceAuthApi::class.java)
    val health: HealthApi = retrofit(client,true).create(HealthApi::class.java)
    suspend fun checkHealth() {
        val response = health.apiV1HealthRetrieve()
        if (!response.isSuccessful) throw Errors.map(response.code(),response.errorBody()?.string(),response.headers()["Retry-After"])
        checkNotNull(response.body()) { "The server returned an empty health response." }
    }
}
