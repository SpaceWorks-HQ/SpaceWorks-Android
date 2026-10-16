package org.spaceworks.app.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.spaceworks.app.BuildConfig
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.CoroutineContext

class StorageTransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
    private val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private fun cache() = temporary.newFolder()
    private fun server() = MockWebServer().apply { useHttps(tls.socketFactory, false); start() }
    // Windows Docker reverse DNS may name loopback kubernetes.docker.internal. Keep the fixture's real TLS identity.
    private fun MockWebServer.localUrl(path: String) = url(path).newBuilder().host("localhost").build()
    private fun transfer(cache: File, listener: EventListener = EventListener.NONE) = StorageTransfer(cache, tls.socketFactory, trustManager, listener)
    private fun assertNoAppHeaders(request: RecordedRequest) {
        for (name in listOf("Authorization", "Proxy-Authorization", "Cookie", "Origin", "Referer", "X-Makerspace-Id")) {
            assertNull(name, request.getHeader(name))
        }
    }
    private suspend fun refusal(expected: StorageTransferFailure, block: suspend () -> Unit) {
        try { block(); fail("Expected $expected") }
        catch (failure: StorageTransferException) {
            assertEquals(expected, failure.reason)
            assertFalse(failure.message.orEmpty().contains("signature="))
            assertNull(failure.cause)
        }
    }

    @Test fun multipartPreservesAllSignedFieldsAndPlacesTheRawPhotoLast() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            transfer(cache()).uploadPost(server.localUrl("/photo?signature=private").toString(),
                mapOf("key" to "folder/photo.jpg", "policy" to "signed-policy", "x-amz-signature" to "signed-value", "Content-Type" to "image/jpeg"),
                jpeg, "image/jpeg", "../../evidence<script>.jpg")
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertNoAppHeaders(request)
            assertEquals("POST", request.method)
            assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
            val body = request.body.readByteArray().toString(Charsets.ISO_8859_1)
            val photoIndex = body.indexOf("name=\"file\";")
            assertTrue(photoIndex >= 0)
            for ((key, value) in mapOf("key" to "folder/photo.jpg", "policy" to "signed-policy", "x-amz-signature" to "signed-value", "Content-Type" to "image/jpeg")) {
                val index = body.indexOf("name=\"$key\"\r\n")
                assertTrue("Field $key must precede the file", index >= 0 && index < photoIndex)
                assertTrue(body.contains("name=\"$key\"\r\nContent-Length: ${value.toByteArray().size}\r\n\r\n$value\r\n") ||
                    body.contains("name=\"$key\"\r\n\r\n$value\r\n"))
            }
            assertTrue(body.contains(jpeg.toString(Charsets.ISO_8859_1)))
            assertFalse(body.contains("../")); assertFalse(body.contains("<script>"))
        }
    }

    @Test fun explicitPutSendsOnlyRawPhotoAndSafeStorageHeadersWithoutRetainingCookies() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().setHeader("Set-Cookie", "session=web; Path=/").setBody("ok"))
            server.enqueue(MockResponse().setResponseCode(204))
            val client = transfer(cache())
            repeat(2) {
                client.uploadPut(server.localUrl("/photo?signature=private").toString(),
                    mapOf("x-amz-meta-object" to "server-value", "Content-Type" to "image/jpeg"), jpeg, "image/jpeg")
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertNoAppHeaders(request)
                assertEquals("PUT", request.method)
                assertArrayEquals(jpeg, request.body.readByteArray())
                assertEquals("image/jpeg", request.getHeader("Content-Type"))
                assertEquals("server-value", request.getHeader("x-amz-meta-object"))
            }
        }
    }

    @Test fun unsafeHeaderFieldAndPhotoInputsAreRejectedBeforeConnecting() = runBlocking {
        server().use { server ->
            val client = transfer(cache())
            val url = server.localUrl("/photo?signature=private").toString()
            for (headers in listOf(mapOf("Authorization" to "Bearer app"), mapOf("Cookie" to "session=web"),
                mapOf("Origin" to "https://api.example"), mapOf("Referer" to "secret"), mapOf("X-Makerspace-Id" to "7"),
                mapOf("Host" to "other.example"), mapOf("Content-Length" to "1"), mapOf("Transfer-Encoding" to "chunked"),
                mapOf("X-Test" to "a\r\nAuthorization: secret"), mapOf("Bad Header" to "value"), mapOf("Content-Type" to "text/html"))) {
                refusal(StorageTransferFailure.INVALID_INPUT) { client.uploadPut(url, headers, jpeg, "image/jpeg") }
            }
            for (fields in listOf(mapOf("file" to "shadow"), mapOf("bad\r\nkey" to "value"), mapOf("policy" to "x".repeat(65537)))) {
                refusal(StorageTransferFailure.INVALID_INPUT) { client.uploadPost(url, fields, jpeg, "image/jpeg", "photo") }
            }
            refusal(StorageTransferFailure.INVALID_INPUT) { client.uploadPut(url, emptyMap(), jpeg, "text/html") }
            refusal(StorageTransferFailure.INVALID_INPUT) { client.uploadPut(url, emptyMap(), "<html>".toByteArray(), "image/jpeg") }
            refusal(StorageTransferFailure.TOO_LARGE) { client.uploadPut(url, emptyMap(), jpeg, "image/jpeg", maximumBytes = 3) }
            refusal(StorageTransferFailure.INVALID_INPUT) { client.download(url, StorageDocumentKind.PDF, "receipt", maximumBytes = 0) }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun unsafeUrlsNeverOpenAStorageConnection() = runBlocking {
        val client = transfer(cache())
        for (url in listOf("http://storage.example/photo", "http://localhost/photo", "file:///private/photo",
                "https://user:password@storage.example/photo", "https://@storage.example/photo", "https://storage.example/photo#fragment",
                "https://storage.example\\@other.example/photo", "https://storage.example/a\nb", "https://storage.example/a\u202Eb")) {
            refusal(StorageTransferFailure.INVALID_URL) { client.uploadPut(url, emptyMap(), jpeg, "image/jpeg") }
        }
    }

    @Test fun debugHttpRequiresAnExactExplicitLocalOriginAndReleaseAlwaysRefusesIt() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val url = server.localUrl("/photo").toString()
            val origin = server.localUrl("/").toString()
            val client = StorageTransfer(cache(), debugHttpOrigin = origin)
            if (BuildConfig.DEBUG) {
                server.enqueue(MockResponse().setResponseCode(204))
                client.uploadPut(url, emptyMap(), jpeg, "image/jpeg")
                assertEquals(1, server.requestCount)
            } else refusal(StorageTransferFailure.INVALID_URL) { client.uploadPut(url, emptyMap(), jpeg, "image/jpeg") }
            refusal(StorageTransferFailure.INVALID_URL) { StorageTransfer(cache()).uploadPut(url, emptyMap(), jpeg, "image/jpeg") }
            refusal(StorageTransferFailure.INVALID_URL) {
                StorageTransfer(cache(), "http://localhost:1").uploadPut(url, emptyMap(), jpeg, "image/jpeg")
            }
        }
        for (origin in listOf("http://remote.example", "http://localhost/path", "http://localhost/?token=secret", "http://user@localhost", "http://127.0.0.1")) {
            refusal(StorageTransferFailure.INVALID_URL) { StorageTransfer(cache(), origin) }
        }
        // Android emulator host is an explicit development option, not an implicit HTTP exception.
        StorageTransfer(cache(), "http://10.0.2.2:8000")
        Unit
    }

    @Test fun redirectsAuthenticationChallengesAndRetryAfterZeroNeverTriggerAnotherRequest() = runBlocking {
        for (status in listOf(302, 307, 401, 503)) {
            server().use { server ->
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", server.localUrl("/unexpected"))
                    .setHeader("Retry-After", "0").setHeader("WWW-Authenticate", "Basic realm=storage").setBody("private error"))
                server.enqueue(MockResponse().setResponseCode(204)) // Would conceal a forbidden automatic follow-up.
                refusal(StorageTransferFailure.REFUSED) {
                    transfer(cache()).uploadPut(server.localUrl("/photo?signature=private").toString(), emptyMap(), jpeg, "image/jpeg")
                }
                assertEquals("status=$status", 1, server.requestCount)
                assertNoAppHeaders(server.takeRequest(5, TimeUnit.SECONDS)!!)
            }
        }
    }

    @Test fun downloadsCheckExpectedMimeAndMagicUseSafeNamesAndHaveExplicitCleanup() = runBlocking {
        val cases = listOf(Triple(StorageDocumentKind.PDF, "application/pdf", "%PDF-1.7\nfixture".toByteArray()),
            Triple(StorageDocumentKind.JPEG, "image/jpeg", jpeg), Triple(StorageDocumentKind.PNG, "image/png", png),
            Triple(StorageDocumentKind.ZIP, "application/zip", byteArrayOf(80, 75, 3, 4)))
        server().use { server ->
            val directory = cache()
            val client = transfer(directory)
            for ((kind, mime, bytes) in cases) {
                server.enqueue(MockResponse().setHeader("Content-Type", "$mime; charset=binary").setBody(Buffer().write(bytes)))
                val owner = client.download(server.localUrl("/document?signature=private").toString(), kind, "../../Receipt<script>.html")
                assertArrayEquals(bytes, owner.file.readBytes())
                assertEquals(directory.canonicalFile, owner.file.canonicalFile.parentFile)
                assertEquals(kind.extension, owner.file.extension)
                assertFalse(owner.file.name.contains("<")); assertFalse(owner.file.name.contains(".."))
                assertNoAppHeaders(server.takeRequest(5, TimeUnit.SECONDS)!!)
                owner.close(); owner.close()
                assertFalse(owner.file.exists())
            }
            assertEquals(0, directory.listFiles()!!.size)
        }
    }

    @Test fun memberImagesRequireMatchingJpegPngOrWebpMimeAndMagicAndExplicitCleanup() = runBlocking {
        val webp = byteArrayOf(82, 73, 70, 70, 4, 0, 0, 0, 87, 69, 66, 80)
        server().use { server ->
            val directory = cache()
            val client = transfer(directory)
            for ((mime, bytes) in listOf("image/jpeg" to jpeg, "image/png" to png, "image/webp" to webp)) {
                server.enqueue(MockResponse().setHeader("Content-Type", mime).setBody(Buffer().write(bytes)))
                client.download(server.localUrl("/member-image").toString(), StorageDocumentKind.IMAGE, "image", 1024).use {
                    assertEquals("img", it.file.extension)
                    assertArrayEquals(bytes, it.file.readBytes())
                }
                assertNoAppHeaders(server.takeRequest(5, TimeUnit.SECONDS)!!)
                assertEquals(0, directory.listFiles()!!.size)
            }
            for ((mime, bytes) in listOf("image/png" to webp, "image/webp" to png, "image/jpeg" to png,
                    "image/webp" to webp.copyOf(11), "image/webp" to "RIFF0000FAKE".toByteArray(),
                    "image/svg+xml" to "<svg/>".toByteArray(), "text/html" to jpeg)) {
                server.enqueue(MockResponse().setHeader("Content-Type", mime).setBody(Buffer().write(bytes)))
                refusal(StorageTransferFailure.UNSUPPORTED_DOCUMENT) {
                    client.download(server.localUrl("/invalid-image").toString(), StorageDocumentKind.IMAGE, "image")
                }
                assertEquals(0, directory.listFiles()!!.size)
            }
        }
    }

    @Test fun declaredAndChunkedResponseCapsApplyToDocumentsAndUploadAcknowledgements() = runBlocking {
        server().use { server ->
            val directory = cache()
            val client = transfer(directory)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/pdf").setBody("%PDF-").setHeader("Content-Length", "9999"))
            refusal(StorageTransferFailure.TOO_LARGE) { client.download(server.localUrl("/declared").toString(), StorageDocumentKind.PDF, "receipt", 32) }
            server.enqueue(MockResponse().setHeader("Content-Type", "application/pdf").setChunkedBody(Buffer().writeUtf8("%PDF-" + "x".repeat(40)), 3))
            refusal(StorageTransferFailure.TOO_LARGE) { client.download(server.localUrl("/chunked").toString(), StorageDocumentKind.PDF, "receipt", 32) }
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(65537)), 4096))
            refusal(StorageTransferFailure.TOO_LARGE) { client.uploadPut(server.localUrl("/ack").toString(), emptyMap(), jpeg, "image/jpeg") }
            assertEquals(0, directory.listFiles()!!.size)
        }
    }

    @Test fun htmlOrWrongMagicCannotBecomeAnOpenableDocumentAndLeavesNoTemporaryFile() = runBlocking {
        server().use { server ->
            val directory = cache()
            val client = transfer(directory)
            for ((mime, bytes) in listOf("text/html" to "%PDF-fake".toByteArray(), "application/pdf" to "<html>secret</html>".toByteArray(),
                    "application/pdf" to ByteArray(0), "image/png" to jpeg)) {
                server.enqueue(MockResponse().setHeader("Content-Type", mime).setBody(Buffer().write(bytes)))
                val expected = if (mime == "image/png") StorageDocumentKind.PNG else StorageDocumentKind.PDF
                refusal(StorageTransferFailure.UNSUPPORTED_DOCUMENT) { client.download(server.localUrl("/invalid").toString(), expected, "receipt") }
                assertEquals(0, directory.listFiles()!!.size)
            }
        }
    }

    @Test fun cancellationWhileWaitingForHeadersCancelsTheCall() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val failed = CountDownLatch(1)
            val listener = object : EventListener() { override fun callFailed(call: Call, ioe: IOException) { failed.countDown() } }
            val directory = cache()
            val client = transfer(directory, listener)
            val waiting = async(Dispatchers.IO) { client.download(server.localUrl("/waiting").toString(), StorageDocumentKind.PDF, "receipt") }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(5000) { waiting.cancelAndJoin() }
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            assertEquals(0, directory.listFiles()!!.size)
        }
    }

    @Test fun cancellationDuringBodyReadClosesTheResponseAndDeletesPartialFile() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/pdf").setBody("%PDF-" + "x".repeat(1024))
                .throttleBody(1, 1, TimeUnit.SECONDS))
            val reading = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val activeCall = AtomicReference<Call>()
            val listener = object : EventListener() {
                override fun responseBodyStart(call: Call) { activeCall.set(call); reading.countDown() }
                // Closing an HTTP/2 response can finish its exchange normally or fail its read.
                // callFailed alone is not a body-close invariant and differs with that race.
                override fun responseBodyEnd(call: Call, byteCount: Long) { closed.countDown() }
                override fun responseFailed(call: Call, ioe: IOException) { closed.countDown() }
            }
            val directory = cache()
            val client = transfer(directory, listener)
            val waiting = async(Dispatchers.IO) { client.download(server.localUrl("/slow").toString(), StorageDocumentKind.PDF, "receipt") }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            withTimeout(5000) { waiting.cancelAndJoin() }
            assertTrue("The active storage call must be canceled", activeCall.get().isCanceled())
            assertTrue("The response body must close or terminate its read", closed.await(5, TimeUnit.SECONDS))
            awaitEmpty(directory)
        }
    }

    @Test fun cancellationAfterNetworkCompletionBeforeDispatcherDeliveryDeletesUndeliveredFile() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/pdf").setBody("%PDF-1.7\nfixture"))
            val dispatcher = HoldingDispatcher()
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val directory = cache()
            try {
                val waiting = scope.async { transfer(directory).download(server.localUrl("/ready").toString(), StorageDocumentKind.PDF, "receipt") }
                dispatcher.next().run() // Start the coroutine, then hold its successful result off-dispatcher.
                val delivery = dispatcher.next()
                assertEquals(1, directory.listFiles()!!.size)
                waiting.cancel()
                delivery.run()
                assertTrue(waiting.isCancelled)
                awaitEmpty(directory)
            } finally { scope.cancel() }
        }
    }

    private fun awaitEmpty(directory: File) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (directory.listFiles()!!.isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(0, directory.listFiles()!!.size)
    }
    private class HoldingDispatcher : CoroutineDispatcher() {
        private val queue = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun next(): Runnable = queue.poll(10, TimeUnit.SECONDS) ?: error("Coroutine was not dispatched")
    }

    companion object {
        // Public, self-signed localhost TEST fixture. It is not trusted by the app's production constructor.
        private val certificate: X509Certificate by lazy {
            CertificateFactory.getInstance("X.509").generateCertificate(TEST_CERT.byteInputStream()) as X509Certificate
        }
        private val trustManager: X509TrustManager by lazy {
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", certificate) }
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }.trustManagers
                .filterIsInstance<X509TrustManager>().single()
        }
        private val tls: SSLContext by lazy {
            val encodedKey = TEST_KEY.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encodedKey)))
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null); setKeyEntry("fixture", key, "test-only".toCharArray(), arrayOf(certificate))
            }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "test-only".toCharArray()) }
            SSLContext.getInstance("TLS").apply { init(managers.keyManagers, arrayOf(trustManager), null) }
        }
        private val TEST_CERT = """
            -----BEGIN CERTIFICATE-----
            MIIDDjCCAfagAwIBAgIEB1vNFTANBgkqhkiG9w0BAQsFADAmMSQwIgYDVQQDDBts
            b2NhbGhvc3QgdGVzdCBmaXh0dXJlIG9ubHkwHhcNMjUwMTAxMDAwMDAwWhcNNDUw
            MTAxMDAwMDAwWjAmMSQwIgYDVQQDDBtsb2NhbGhvc3QgdGVzdCBmaXh0dXJlIG9u
            bHkwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDAoqnS8ngz+14tcSjq
            ApYs3UhgI7te7xBaYMjq1HLywXKoLOWiNaQ86xsw8R5eVCIhN6rpuMwJ+/wlWyQ5
            PFvUAnDVZ2RfUKTi+fl6/t7XJ9yas0AIbVRvzeaW427e5CakHgr8Z5AlhaqL4sev
            k5w4ceKjcr+srZSwUoBqFs72q+PvbfW1R550v0ceLbWe5hno/3Lin15bkNkjzY68
            dphWmIlZFueLx5pb4rtSjkl0uM6TPobPQasIh8/JbLRV7n+avune/Qyl8RXyLEBu
            YSElCYRfNLMKiCWPd6fup3SZFbWfUsfedvPkC9m5R48CfDguMC5ekgB7YDfk0spA
            789lAgMBAAGjRDBCMBoGA1UdEQQTMBGCCWxvY2FsaG9zdIcEfwAAATAPBgNVHRMB
            Af8EBTADAQH/MBMGA1UdJQQMMAoGCCsGAQUFBwMBMA0GCSqGSIb3DQEBCwUAA4IB
            AQC+oqzQFEzpA+Vdj6YxWuEpLRaDsD6uFFFh2vMRSNIvrEAwFwwXKMI6eJNkxE3E
            w46OJYyXfsFCsuDFq2FBJWPk/GCwWuvktWDon+tI9X54wdE3CyGgdsAinrrxDaFe
            4HOzB/YTcJQM/Ruik3gjmU3exgRtS650K/L9b2Nhn/rQRWSwxw5Po4EYjcubbDvK
            XwxpYVrIEo21GXPTSJkCI3ZlK02JJRcfSmQmAs08/Vypc/EdL72S9oUGsMMXhGKn
            C05OCBlQzgL8xLDYU0CYUJIwq0MWeBnMIT9oXr4imRLNXGkFXabFWsHNDIwrF8YO
            K932IQlCmFL10ppY0Ag1Oew0
            -----END CERTIFICATE-----
        """.trimIndent()
        private val TEST_KEY = """
            -----BEGIN PRIVATE KEY-----
            MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDAoqnS8ngz+14t
            cSjqApYs3UhgI7te7xBaYMjq1HLywXKoLOWiNaQ86xsw8R5eVCIhN6rpuMwJ+/wl
            WyQ5PFvUAnDVZ2RfUKTi+fl6/t7XJ9yas0AIbVRvzeaW427e5CakHgr8Z5AlhaqL
            4sevk5w4ceKjcr+srZSwUoBqFs72q+PvbfW1R550v0ceLbWe5hno/3Lin15bkNkj
            zY68dphWmIlZFueLx5pb4rtSjkl0uM6TPobPQasIh8/JbLRV7n+avune/Qyl8RXy
            LEBuYSElCYRfNLMKiCWPd6fup3SZFbWfUsfedvPkC9m5R48CfDguMC5ekgB7YDfk
            0spA789lAgMBAAECggEAAPtTPj36Gtm3GyguezLULRpbmhHUjKFPRgakP34hr9cI
            jI7eBLudQKo6Ez/HVde77Mtp1ZMZhxt/p3kVcC08kpIInS3PtySkEnwPp27RF+ue
            FjO/qctU2NUFzdMO55fmEoQMrkig1XAe9GONfPmVnDwIWajmDS09SsDA4smG+H7Z
            gOSgBXBKqT7RARpOG4mUEh+vhj+KubM1xtg3sr+EQNOS+xQjOJvABItYGllzaQTe
            d2k0sy88x28npyzFXLQCfQGDGnt0X/eqzBes7I9Jqa/VI2lBQEyob18WFBNSOmP+
            TG01laOUkKSMftqbPUTNsDDyfEC7Ys3QRTG9sOx8KQKBgQDjrSoBPcUTIVwyTWmG
            cDajbLtH+GF0kWlcwO6+gkhOvZP7y9chwFE1nMdIyv98iolDpgKiFZ9nHoWBA0KF
            5CTqyMMCfX+qXCFpLbVLgD49DZCugjVUBHkgjRDHdCBCrqJE1xxjvQohCd9DDDCc
            K99qpUbVV8gYzvHytkAChAFGPwKBgQDYmYtNVAM798+6bzAYTx4iZv3sHYtYvfCz
            h6pHDlPX5cv7bDNHhbxtt+5/ByIldwBqhT7f073i0xDNwT2yrg2mb+A6KeBeIew2
            6mz64bXhz8+UhtoSt/LdIW1uJq1JIXeC2vwhkG+7r+5mFHwM/m+dQGBl5zJr3JC0
            UEoNvlRpWwKBgG9U6vhmaysaogna/uIFl0I81iuwUn9856e6lERGUvNmEqoLGGzh
            rRRpUK3QzjPpTovs7taxehwjuabHoEz2rfK9L1bVZYs5uADFoCSGVmjmbDxZYZhv
            A4geUvklWO0ilijRg4lmFEDpoeoYWODVN0/f+A40+Zzf/UHxSsWwJStjAoGAOteb
            rGpCZqwv1qoWGsSDnzzzho2QKY+QMHWOoLcXt/NJswf19lwykGwDcqbxc9QcXNPN
            /Oh9EX3ddsgkhsIaGzkRZTuzJF962IBfEtj2nF3kOqlX3PX0GHbYT7BaKx/YY8gx
            7rxaXlaxITI3cSA+Gi4/AfAJFpxZA2M8evdV3zkCgYEAzAW77s7WKVzvlsT+K1t2
            ZZJe9AmWd3dPVxPGQEY9vbG/gzrN1b8JWlcxvo6A/PW4LubVgSKfJaN+qcIk1Tfv
            IQu0NaGcOr7/oU3TciZ4MHeLxY5YEDpth0wmHiW3Ua02OfhT7SuDkxHdd2yNanP+
            yC30mJle3UhnBj1q3ynkwzo=
            -----END PRIVATE KEY-----
        """.trimIndent()
    }
}
