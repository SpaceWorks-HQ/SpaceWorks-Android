package org.spaceworks.app

import org.junit.Test
import org.junit.Assert.*
import org.spaceworks.app.network.*
import okhttp3.mockwebserver.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

class NetworkTest {
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
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse = when {
                    request.path=="/api/v1/auth/device/refresh" -> {
                        calls.incrementAndGet()
                        assertTrue(request.body.readUtf8().contains("old-refresh"))
                        MockResponse().setBody("""{"access":"new-access","refresh":"new-refresh","device_grant":{"id":"00000000-0000-0000-0000-000000000001","platform":"android","app_id":"org.spaceworks.app","environment":"development","status":"active","attested_at":"2026-10-16T00:00:00Z","last_used_at":"2026-10-16T00:00:00Z","created_at":"2026-10-16T00:00:00Z"}}""")
                    }
                    request.getHeader("Authorization")=="Bearer old-access" -> MockResponse().setResponseCode(401).setBody("""{"detail":"Expired","code":"expired"}""")
                    request.getHeader("Authorization")=="Bearer new-access" -> MockResponse().setBody("""{"status":"ok"}""")
                    else -> MockResponse().setResponseCode(401)
                }
            }
            val store=MemoryTokenStore().apply {replace(TokenPair("old-access","old-refresh"))}
            val session=ApiSession(server.url("/"),store)
            (1..12).map {async(Dispatchers.IO){session.checkHealth()}}.awaitAll()
            assertEquals(1,calls.get())
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
    @Test fun frameworkAndFieldDictionaryErrorsAreReadable() {
        assertEquals("Maintenance",Errors.map(503,"""{"detail":"Maintenance"}""").detail)
        assertEquals("email: Enter a valid email.",Errors.map(400,"""{"email":["Enter a valid email."]}""").detail)
        assertEquals("Check the details and try again.",Errors.map(400,"bad json").detail)
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
    @Test fun refreshCancellationDoesNotEraseTokens() = runBlocking {
        val store=MemoryTokenStore().apply {replace(TokenPair("a","r"))}
        val coordinator=RefreshCoordinator(store){throw CancellationException()}
        try {coordinator.refresh("a");fail("Expected cancellation")} catch(_:CancellationException) {assertEquals("r",store.read()?.refresh)}
    }
}
