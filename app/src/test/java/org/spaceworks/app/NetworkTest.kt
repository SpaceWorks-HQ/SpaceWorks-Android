package org.spaceworks.app

import org.junit.Test
import org.junit.Assert.*
import org.spaceworks.app.network.*
import okhttp3.mockwebserver.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NetworkTest {
    @Test(timeout=15000) fun serviceRetryAfterZeroNeverReplaysHealthOrRefresh() = runBlocking {
        for (status in listOf(408, 421, 503)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "0")
                .setHeader("X-Original", "preserved").setBody("""{"detail":"Wait for the server."}"""))
            server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
            val response = ApiSession(server.url("/")).health.apiV1HealthRetrieve()
            assertEquals(status, response.code()); assertEquals("0", response.headers()["Retry-After"])
            assertEquals("preserved", response.headers()["X-Original"])
            assertEquals("Wait for the server.", Errors.map(response.code(), response.errorBody()?.string()).detail)
            assertEquals(1, server.requestCount)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0")
                .setBody("""{"detail":"Rotation unavailable."}"""))
            server.enqueue(MockResponse().setBody("""{"access":"bogus","refresh":"replayed"}"""))
            val store = MemoryTokenStore().apply { replace(TokenPair("old", "single-use")) }
            try { ApiSession(server.url("/"), store).coordinator.refresh("old",store.snapshot().epoch); fail("Must fail without replay") }
            catch (failure: ApiFailure) {
                assertEquals(503, failure.status); assertEquals(0L, failure.retryAfterSeconds)
                assertEquals("Rotation unavailable.", failure.detail)
            }
            assertEquals(1, server.requestCount); assertNull(store.read())
        }
    }
    @Test(timeout=10000) fun serviceRetryAfterZeroNeverReplaysMutation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0")
                .setBody("""{"detail":"Not changed."}"""))
            server.enqueue(MockResponse().setBody("""{"detail":"Changed twice."}"""))
            val session = ApiSession(server.url("/"))
            val response = session.auth.apiV1AuthChangePasswordCreate(
                org.spaceworks.generated.model.ChangePassword("old-password", "new-password"))
            assertEquals(503, response.code()); assertEquals("0", response.headers()["Retry-After"])
            assertEquals(1, server.requestCount)
            assertEquals("POST", server.takeRequest().method)
        }
    }
    @Test fun healthUsesGeneratedClientAndSanitizedHeaders() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
            val store=MemoryTokenStore().apply {replace(TokenPair("token","refresh"))}
            ApiSession(server.url("/"),store){"42"}.checkHealth()
            val request=server.takeRequest()
            assertEquals("/api/v1/health/",request.path)
            assertEquals("Bearer token",request.getHeader("Authorization"))
            assertEquals("42",request.getHeader("X-Makerspace-Id"))
            listOf("Cookie","Origin","Referer").forEach {assertNull(request.getHeader(it))}
        }
    }
    @Test(timeout=30000) fun concurrent401ResponsesRotateExactlyOnceAndPersistBeforeRetry() = runBlocking {
        MockWebServer().use { server ->
            val calls=AtomicInteger()
            val oldRequests=AtomicInteger()
            val firstWave=CountDownLatch(5)
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse = when {
                    request.path=="/api/v1/auth/device/refresh" -> {
                        calls.incrementAndGet()
                        assertTrue(request.body.readUtf8().contains("old-refresh"))
                        MockResponse().setBody("""{"access":"new-access","refresh":"new-refresh","device_grant":{"id":"00000000-0000-0000-0000-000000000001","platform":"android","app_id":"org.spaceworks.app","environment":"development","status":"active","attested_at":"2026-10-16T00:00:00Z","last_used_at":"2026-10-16T00:00:00Z","created_at":"2026-10-16T00:00:00Z"}}""")
                    }
                    request.getHeader("Authorization")=="Bearer old-access" -> {
                        oldRequests.incrementAndGet();firstWave.countDown()
                        check(firstWave.await(10,TimeUnit.SECONDS)) { "Concurrent first wave did not arrive." }
                        MockResponse().setResponseCode(401).setBody("""{"detail":"Expired","code":"expired"}""")
                    }
                    request.getHeader("Authorization")=="Bearer new-access" -> MockResponse().setBody("""{"status":"ok"}""")
                    else -> MockResponse().setResponseCode(401)
                }
            }
            val store=MemoryTokenStore().apply {replace(TokenPair("old-access","old-refresh"))}
            val session=ApiSession(server.url("/"),store)
            (1..12).map {async(Dispatchers.IO){session.checkHealth()}}.awaitAll()
            assertEquals(1,calls.get());assertTrue(oldRequests.get() >= 5)
            assertEquals(TokenPair("new-access","new-refresh"),store.read())
        }
    }
    @Test fun failureAndRetryAfterAreMappedFromContract() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","90").setBody("""{"detail":"Please wait.","code":"throttled"}"""))
            try {ApiSession(server.url("/")).checkHealth();fail("Expected error")} catch(f:ApiFailure) {
                assertEquals("Please wait.",f.detail);assertEquals("throttled",f.code);assertEquals(90L,f.retryAfterSeconds)
            }
        }
        assertEquals("Use the web console",Errors.map(403,"""{"detail":"No phones","code":"device_superadmin_not_supported"}""").detail)
        assertEquals("The server is unavailable. Try again later.",Errors.map(503,"broken").detail)
    }
    @Test(timeout=15000) fun disconnectedRefreshIsNeverReplayed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val store=MemoryTokenStore().apply {replace(TokenPair("old","single-use-refresh"))}
            val session=ApiSession(server.url("/"),store)
            try { session.coordinator.refresh("old",store.snapshot().epoch);fail("Expected disconnection") } catch(_:java.io.IOException) { }
            assertEquals(1,server.requestCount)
            assertEquals("/api/v1/auth/device/refresh",server.takeRequest().path)
            assertNull(store.read())
        }
    }
    @Test fun frameworkAndFieldDictionaryErrorsAreReadable() {
        assertEquals("Maintenance",Errors.map(503,"""{"detail":"Maintenance"}""").detail)
        assertEquals("email: Enter a valid email.",Errors.map(400,"""{"email":["Enter a valid email."]}""").detail)
        assertEquals("Check the details and try again.",Errors.map(400,"bad json").detail)
    }
    @Test fun customAnswerErrorsKeepNestedPathsAndRespectDetailAndSpecialCodes() {
        val fields="""{"custom_answers":{"voltage":["Enter a number.","Use a whole number."],"safety":"Answer this question."}}"""
        assertEquals("custom_answers.voltage: Enter a number. Use a whole number.\ncustom_answers.safety: Answer this question.",Errors.map(400,fields).detail)
        assertEquals("Review the form.",Errors.map(400,"""{"detail":"Review the form.","custom_answers":{"voltage":["Enter a number."]}}""").detail)
        assertEquals("Use the web console",Errors.map(400,"""{"code":"device_superadmin_not_supported","detail":"Review the form.","custom_answers":{"voltage":"Invalid"}}""").detail)
    }
    @Test fun addressAndPaginationRejectCredentialLeaks() {
        assertEquals("https://example.org/",ServerAddress.parse("https://example.org",false).toString())
        assertEquals("http://localhost/",ServerAddress.parse("http://localhost",true).toString())
        listOf("http://example.org","https://user:pass@example.org","https://example.org/api/","https://example.org/?a=1").forEach { value ->
            assertThrows(IllegalArgumentException::class.java){ServerAddress.parse(value,false)}
        }
        val base=ServerAddress.parse("https://example.org",false)
        assertEquals("https://example.org/api/v1/items?page=2",Pagination.next(base,"/api/v1/items?page=2").toString())
        assertNull(Pagination.next(base,null))
        assertThrows(IllegalArgumentException::class.java){Pagination.next(base,"https://evil.example/collect")}
    }
    @Test(timeout=10000) fun cancelledCallerCannotDiscardRotatedTokens() = runBlocking {
        val store=MemoryTokenStore().apply {replace(TokenPair("a","r"))}
        val started=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        var calls=0
        val coordinator=RefreshCoordinator(store){calls++;started.complete(Unit);release.await();TokenPair("b","s")}
        val caller=launch {coordinator.refresh("a",store.snapshot().epoch)}
        started.await();caller.cancel();release.complete(Unit);caller.join()
        assertEquals(TokenPair("b","s"),store.read())
        assertEquals(TokenPair("b","s"),coordinator.refresh("a",store.snapshot().epoch));assertEquals(1,calls)
    }
}
