package org.spaceworks.app.platform

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.spaceworks.app.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resumeWithException

enum class StorageTransferFailure { INVALID_URL, INVALID_INPUT, TOO_LARGE, REFUSED, UNSUPPORTED_DOCUMENT, FAILED }

/** Deliberately omits request URLs, signed fields, network exception messages and credentials. */
class StorageTransferException(val reason: StorageTransferFailure) : IOException(when (reason) {
    StorageTransferFailure.INVALID_URL -> "The storage address is not allowed."
    StorageTransferFailure.INVALID_INPUT -> "The file or storage settings are invalid."
    StorageTransferFailure.TOO_LARGE -> "The file exceeds the allowed size."
    StorageTransferFailure.REFUSED -> "The storage service refused this transfer."
    StorageTransferFailure.UNSUPPORTED_DOCUMENT -> "This document cannot be opened."
    StorageTransferFailure.FAILED -> "The file could not be transferred. Try again."
})

/** Local file ownership only, not a generated transport model. Retain until preview/share finishes. */
class StorageDocumentFile internal constructor(val file: File) : Closeable {
    override fun close() {
        if (!file.delete() && file.exists()) throw IOException("The temporary document could not be removed.")
    }
}

enum class StorageDocumentKind(val extension: String, private vararg val mimeTypes: String) {
    PDF("pdf", "application/pdf"), JPEG("jpg", "image/jpeg"), PNG("png", "image/png"),
    IMAGE("img", "image/jpeg", "image/png", "image/webp"),
    ZIP("zip", "application/zip", "application/x-zip-compressed", "application/octet-stream");

    internal fun accepts(body: ResponseBody): Boolean = body.contentType()?.let {
        "${it.type}/${it.subtype}".lowercase(Locale.ROOT) in mimeTypes
    } == true

    internal fun recognizes(prefix: ByteArray, mimeType: String? = null): Boolean = when (this) {
        PDF -> prefix.startsWith(byteArrayOf(37, 80, 68, 70, 45))
        JPEG -> prefix.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        PNG -> prefix.startsWith(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        IMAGE -> when (mimeType) {
            "image/jpeg" -> JPEG.recognizes(prefix)
            "image/png" -> PNG.recognizes(prefix)
            "image/webp" -> prefix.size >= 12 && prefix.startsWith(byteArrayOf(82, 73, 70, 70)) &&
                prefix.copyOfRange(8, 12).contentEquals(byteArrayOf(87, 69, 66, 80))
            else -> false
        }
        ZIP -> listOf(byteArrayOf(80, 75, 3, 4), byteArrayOf(80, 75, 5, 6), byteArrayOf(80, 75, 7, 8))
            .any(prefix::startsWith)
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

/**
 * Storage transport for actual server-issued presigns/documents after generated API authorization.
 * The caller owns API operations, membership, expiry, and attachment after successful upload.
 * Pass an app-private cache directory, never a public downloads directory. No API client is reused.
 */
class StorageTransfer private constructor(
    private val tempDirectory: File,
    private val debugHttpOrigin: HttpUrl?,
    private val client: OkHttpClient,
) {
    constructor(tempDirectory: File, debugHttpOrigin: String? = null) :
        this(tempDirectory, debugHttpOrigin?.let(::debugOrigin), storageClient().build())

    /** HTTPS test seam: a fresh client still enforces every storage policy; never accepts an API client. */
    internal constructor(tempDirectory: File, socketFactory: SSLSocketFactory, trustManager: X509TrustManager,
                         listener: EventListener = EventListener.NONE) :
        this(tempDirectory, null, storageClient().sslSocketFactory(socketFactory, trustManager).eventListener(listener).build())

    /** Missing presign method means POST at the caller. Every signed field precedes the file part. */
    suspend fun uploadPost(url: String, fields: Map<String, String>, photo: ByteArray, contentType: String,
                           filename: String, maximumBytes: Int = DEFAULT_PHOTO_LIMIT) {
        val target = target(url)
        val kind = validatePhoto(photo, contentType, maximumBytes)
        if (fields.size > 64 || fields.entries.sumOf { it.key.toByteArray().size.toLong() + it.value.toByteArray().size } > 512 * 1024 ||
            fields.any { (key, value) -> key.isEmpty() || key.length > 128 || key == "file" ||
                key.any { !it.isAsciiLetterOrDigit() && it != '_' && it != '-' } || value.toByteArray().size > 64 * 1024 }) {
            throw StorageTransferException(StorageTransferFailure.INVALID_INPUT)
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            fields.toSortedMap().forEach { (key, value) -> addFormDataPart(key, value) }
            addFormDataPart("file", safeName(filename) + "." + kind.extension, photo.toRequestBody(contentType.toMediaType()))
        }.build()
        execute(Request.Builder().url(target).post(body).build()) { response ->
            copyBounded(response.body ?: throw StorageTransferException(StorageTransferFailure.FAILED), null, ACK_LIMIT)
            Unit
        }
    }

    /** Only for an explicit PUT presign. Signed metadata headers may not carry app credentials. */
    suspend fun uploadPut(url: String, headers: Map<String, String>, photo: ByteArray, contentType: String,
                          maximumBytes: Int = DEFAULT_PHOTO_LIMIT) {
        val target = target(url)
        validatePhoto(photo, contentType, maximumBytes)
        if (headers.size > 64 || headers.any { (key, value) ->
                key.isEmpty() || key.length > 128 || key.lowercase(Locale.ROOT) in FORBIDDEN_HEADERS ||
                    key.any { it !in '!'..'~' || it in "()<>@,;:\\\"/[]?={} " } ||
                    value.length > 8192 || value.any { it !in ' '..'~' } ||
                    (key.equals("Content-Type", ignoreCase = true) && value != contentType)
            }) throw StorageTransferException(StorageTransferFailure.INVALID_INPUT)
        val request = Request.Builder().url(target).apply {
            headers.forEach { (key, value) -> header(key, value) }
            header("Content-Type", contentType)
        }.put(photo.toRequestBody(contentType.toMediaType())).build()
        execute(request) { response ->
            copyBounded(response.body ?: throw StorageTransferException(StorageTransferFailure.FAILED), null, ACK_LIMIT)
            Unit
        }
    }

    suspend fun download(url: String, expectedKind: StorageDocumentKind, suggestedName: String,
                         maximumBytes: Int = DEFAULT_DOCUMENT_LIMIT): StorageDocumentFile {
        val target = target(url)
        validateLimit(maximumBytes)
        return execute(Request.Builder().url(target).get().build(), discard = { it.closeQuietly() }) { response ->
            val body = response.body ?: throw StorageTransferException(StorageTransferFailure.FAILED)
            if (!expectedKind.accepts(body)) throw StorageTransferException(StorageTransferFailure.UNSUPPORTED_DOCUMENT)
            if (body.contentLength() > maximumBytes) throw StorageTransferException(StorageTransferFailure.TOO_LARGE)
            if (!tempDirectory.isDirectory) throw StorageTransferException(StorageTransferFailure.FAILED)
            val file = File.createTempFile("sw-" + safeName(suggestedName) + "-", "." + expectedKind.extension, tempDirectory)
            try {
                val prefix = file.outputStream().use { copyBounded(body, it, maximumBytes) }
                val mimeType = body.contentType()?.let { "${it.type}/${it.subtype}".lowercase(Locale.ROOT) }
                if (!expectedKind.recognizes(prefix, mimeType)) throw StorageTransferException(StorageTransferFailure.UNSUPPORTED_DOCUMENT)
                StorageDocumentFile(file)
            } catch (failure: Exception) {
                file.delete()
                throw failure
            }
        }
    }

    private fun target(value: String): HttpUrl {
        if (value.toByteArray().size > 16 * 1024 || value.any { it == '\\' || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) {
            throw StorageTransferException(StorageTransferFailure.INVALID_URL)
        }
        val raw = try { URI(value) } catch (_: Exception) { throw StorageTransferException(StorageTransferFailure.INVALID_URL) }
        val url = value.toHttpUrlOrNull() ?: throw StorageTransferException(StorageTransferFailure.INVALID_URL)
        if (raw.rawUserInfo != null || raw.rawFragment != null || raw.host.isNullOrEmpty()) {
            throw StorageTransferException(StorageTransferFailure.INVALID_URL)
        }
        if (url.isHttps) return url
        if (BuildConfig.DEBUG && url.scheme == "http" && debugHttpOrigin?.let { url.host == it.host && url.port == it.port } == true) return url
        throw StorageTransferException(StorageTransferFailure.INVALID_URL)
    }

    private suspend fun <T> execute(request: Request, discard: (T) -> Unit = {}, consume: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            val activeResponse = AtomicReference<Response?>(null)
            continuation.invokeOnCancellation {
                call.cancel()
                activeResponse.getAndSet(null)?.closeQuietly()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (e is StorageTransferException) e else StorageTransferException(StorageTransferFailure.FAILED))
                }

                override fun onResponse(call: Call, response: Response) {
                    activeResponse.set(response)
                    try {
                        if (!continuation.isActive) return
                        val result = consume(response)
                        // Prompt cancellation can win after resume but before the caller receives a file.
                        continuation.resume(result) { _, abandoned, _ -> discard(abandoned) }
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (failure is StorageTransferException) failure else StorageTransferException(StorageTransferFailure.FAILED))
                    } finally {
                        activeResponse.compareAndSet(response, null)
                        response.closeQuietly()
                    }
                }
            })
        }

    companion object {
        private const val DEFAULT_PHOTO_LIMIT = 20 * 1024 * 1024
        private const val DEFAULT_DOCUMENT_LIMIT = 25 * 1024 * 1024
        private const val HARD_LIMIT = 100 * 1024 * 1024
        private const val ACK_LIMIT = 64 * 1024
        private val FORBIDDEN_HEADERS = setOf("authorization", "proxy-authorization", "cookie", "cookie2", "set-cookie", "origin", "referer",
            "x-makerspace-id", "host", "content-length", "transfer-encoding", "connection", "proxy-connection", "keep-alive", "te", "trailer", "upgrade")

        private fun storageClient(): OkHttpClient.Builder = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).cache(null)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                // Also blocks OkHttp's status-based 503 Retry-After:0 follow-up, independent of retryOnConnectionFailure.
                if (!response.isSuccessful) {
                    response.close()
                    throw StorageTransferException(StorageTransferFailure.REFUSED)
                }
                response
            }

        private fun debugOrigin(value: String): HttpUrl {
            val url = value.toHttpUrlOrNull() ?: throw StorageTransferException(StorageTransferFailure.INVALID_URL)
            val raw = try { URI(value) } catch (_: Exception) { throw StorageTransferException(StorageTransferFailure.INVALID_URL) }
            if (url.scheme != "http" || url.host !in setOf("localhost", "10.0.2.2") || raw.rawUserInfo != null ||
                url.encodedPath != "/" || url.encodedQuery != null || url.encodedFragment != null ||
                value.any { it == '\\' || it.isISOControl() }) throw StorageTransferException(StorageTransferFailure.INVALID_URL)
            return url
        }

        private fun validateLimit(value: Int) {
            if (value !in 1..HARD_LIMIT) throw StorageTransferException(StorageTransferFailure.INVALID_INPUT)
        }

        private fun validatePhoto(photo: ByteArray, contentType: String, maximumBytes: Int): StorageDocumentKind {
            validateLimit(maximumBytes)
            if (photo.size > maximumBytes) throw StorageTransferException(StorageTransferFailure.TOO_LARGE)
            val kind = when (contentType) {
                "image/jpeg" -> StorageDocumentKind.JPEG
                "image/png" -> StorageDocumentKind.PNG
                else -> throw StorageTransferException(StorageTransferFailure.INVALID_INPUT)
            }
            if (!kind.recognizes(photo)) throw StorageTransferException(StorageTransferFailure.INVALID_INPUT)
            return kind
        }

        /** Streams to a file or discards bounded acknowledgement bytes; only a 12-byte magic prefix is buffered. */
        private fun copyBounded(body: ResponseBody, target: OutputStream?, limit: Int): ByteArray {
            if (body.contentLength() > limit) throw StorageTransferException(StorageTransferFailure.TOO_LARGE)
            val prefix = ByteArrayOutputStream(12)
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                var count = 0
                while (true) {
                    val read = input.read(buffer, 0, minOf(buffer.size, limit - count + 1))
                    if (read < 0) break
                    if (read > limit - count) throw StorageTransferException(StorageTransferFailure.TOO_LARGE)
                    count += read
                    if (prefix.size() < 12) prefix.write(buffer, 0, minOf(read, 12 - prefix.size()))
                    target?.write(buffer, 0, read)
                }
            }
            return prefix.toByteArray()
        }

        private fun safeName(value: String): String = value.filter { it.isAsciiLetterOrDigit() || it == '-' || it == '_' }
            .take(60).ifEmpty { "document" }
        private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
    }
}

private fun Closeable.closeQuietly() { try { close() } catch (_: Exception) { /* No signed URL or local path logging. */ } }
