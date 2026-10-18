package org.spaceworks.app.platform

import android.app.Activity
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.spaceworks.app.auth.AuthCancelled
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One pending OIDC browser redirect. The app's own scheme carries only the provider's authorization
 * response back; it never selects a server or becomes a navigation link.
 */
object OidcRedirects {
    private val pending=AtomicReference<CompletableDeferred<Uri>?>()
    fun redirectUri(applicationId:String)="$applicationId:/oidc"
    fun begin():CompletableDeferred<Uri> = CompletableDeferred<Uri>().also {pending.getAndSet(it)?.cancel()}
    val waiting get()=pending.get()?.isActive==true
    /** Returns true when [intent] was an OIDC redirect; it is consumed whether or not a sign-in awaits it. */
    fun consume(intent:Intent,applicationId:String):Boolean {
        val data=intent.data?:return false
        if(data.scheme!=applicationId || data.path!="/oidc")return false
        intent.data=null
        pending.getAndSet(null)?.complete(data)
        return true
    }
    /** The person returned to the app without a redirect: the browser step was abandoned. */
    fun abandon() {pending.getAndSet(null)?.cancel()}
}

/** Authorization-code + PKCE (S256) against the published issuer; returns the provider ID token. */
class OidcBrowser(private val client:OkHttpClient=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .callTimeout(30,TimeUnit.SECONDS).build()) {
    private val json=Json {ignoreUnknownKeys=true}
    private val random=SecureRandom()
    private fun token(bytes:Int)=ByteArray(bytes).also(random::nextBytes).let {Base64.getUrlEncoder().withoutPadding().encodeToString(it)}
    private fun https(value:String?):HttpUrl? = value?.toHttpUrlOrNull()?.takeIf {it.isHttps && it.username.isEmpty() && it.password.isEmpty()}

    suspend fun idToken(activity:Activity,issuer:String,clientId:String,nonce:String,redirectUri:String):String {
        val base=https(issuer.trimEnd('/'))?:throw PlatformUnavailable("Invalid issuer")
        val discovery=withContext(Dispatchers.IO) {get(base.newBuilder().addPathSegments(".well-known/openid-configuration").build())}
        val authorize=https(discovery["authorization_endpoint"]?.jsonPrimitive?.contentOrNull)?:throw PlatformUnavailable("No authorization endpoint")
        val tokenEndpoint=https(discovery["token_endpoint"]?.jsonPrimitive?.contentOrNull)?:throw PlatformUnavailable("No token endpoint")
        val verifier=token(48)
        val challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state=token(24)
        val url=authorize.newBuilder().addQueryParameter("response_type","code").addQueryParameter("client_id",clientId)
            .addQueryParameter("redirect_uri",redirectUri).addQueryParameter("scope","openid email profile")
            .addQueryParameter("state",state).addQueryParameter("nonce",nonce)
            .addQueryParameter("code_challenge",challenge).addQueryParameter("code_challenge_method","S256").build()
        val redirect=OidcRedirects.begin()
        withContext(Dispatchers.Main) {
            activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url.toString())).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val response=try {withTimeout(10*60_000L) {redirect.await()}}
            catch(timeout:kotlinx.coroutines.TimeoutCancellationException) {OidcRedirects.abandon();throw AuthCancelled()}
            catch(cancelled:kotlinx.coroutines.CancellationException) {
                // An abandoned browser step is a user cancellation; caller cancellation still propagates.
                if(redirect.isCancelled && kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive!=false)throw AuthCancelled()
                throw cancelled
            }
        if(response.getQueryParameter("state")!=state)throw PlatformUnavailable("State mismatch")
        response.getQueryParameter("error")?.let {if(it=="access_denied")throw AuthCancelled() else throw PlatformUnavailable("Provider refused")}
        val code=response.getQueryParameter("code")?.takeIf {it.isNotBlank()}?:throw PlatformUnavailable("No code")
        val tokens=withContext(Dispatchers.IO) {
            post(tokenEndpoint,FormBody.Builder().add("grant_type","authorization_code").add("code",code)
                .add("redirect_uri",redirectUri).add("client_id",clientId).add("code_verifier",verifier).build())
        }
        return tokens["id_token"]?.jsonPrimitive?.contentOrNull?.takeIf {it.isNotBlank()}?:throw PlatformUnavailable("No ID token")
    }
    private fun get(url:HttpUrl)=execute(Request.Builder().url(url).header("Accept","application/json").build())
    private fun post(url:HttpUrl,body:FormBody)=execute(Request.Builder().url(url).post(body).header("Accept","application/json").build())
    private fun execute(request:Request):JsonObject = client.newCall(request).execute().use {response->
        if(!response.isSuccessful)throw PlatformUnavailable("Provider HTTP ${response.code}")
        json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject ?: throw PlatformUnavailable("Provider response")
    }
}
