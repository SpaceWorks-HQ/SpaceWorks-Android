package org.spaceworks.app

import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.network.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SharedRefreshTest {
    @Test(timeout=10000) fun separateCoordinatorsShareRotationForOneStore()=runBlocking {
        val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))}
        val epoch=store.snapshot().epoch
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val calls=AtomicInteger()
        val first=RefreshCoordinator(store){calls.incrementAndGet();entered.complete(Unit);release.await();TokenPair("new","next")}
        val second=RefreshCoordinator(store){calls.incrementAndGet();throw IOException("Single-use token already consumed")}
        val a=async(start=CoroutineStart.UNDISPATCHED){first.refresh("old",epoch)}
        entered.await()
        val b=async(start=CoroutineStart.UNDISPATCHED){second.refresh("old",epoch)}
        val beforeRelease=calls.get()
        release.complete(Unit)
        assertEquals(TokenPair("new","next"),a.await())
        assertEquals(TokenPair("new","next"),b.await())
        assertEquals(1,beforeRelease);assertEquals(1,calls.get())
        assertEquals(epoch,store.snapshot().epoch)
    }

    @Test(timeout=10000) fun failedRotationClearsOnceAndWaiterCannotRefreshAgain()=runBlocking {
        val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))}
        val epoch=store.snapshot().epoch
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var calls=0
        val first=RefreshCoordinator(store){calls++;entered.complete(Unit);release.await();throw IOException("Revoked")}
        val second=RefreshCoordinator(store){calls++;TokenPair("invalid","invalid")}
        val a=async(start=CoroutineStart.UNDISPATCHED){runCatching {first.refresh("old",epoch)}}
        entered.await()
        val b=async(start=CoroutineStart.UNDISPATCHED){second.refresh("old",epoch)}
        release.complete(Unit)
        assertTrue(a.await().exceptionOrNull() is IOException)
        assertNull(b.await());assertNull(store.read());assertEquals(1,calls)
        assertEquals(epoch+1,store.snapshot().epoch)
    }

    @Test(timeout=10000) fun equalButDistinctStoresRemainIndependent()=runBlocking {
        fun equalStore():TokenStore=object:TokenStore {
            val memory=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))}
            override fun read()=memory.read()
            override fun replace(pair:TokenPair?)=memory.replace(pair)
            override fun snapshot()=memory.snapshot()
            override fun rotateIfCurrent(expected:TokenSnapshot,pair:TokenPair?)=memory.rotateIfCurrent(expected,pair)
            override fun equals(other:Any?)=other is TokenStore
            override fun hashCode()=1
        }
        val firstStore=equalStore();val secondStore=equalStore()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val first=RefreshCoordinator(firstStore){entered.complete(Unit);release.await();TokenPair("first","next1")}
        val second=RefreshCoordinator(secondStore){TokenPair("second","next2")}
        val a=async(start=CoroutineStart.UNDISPATCHED){first.refresh("old",firstStore.snapshot().epoch)}
        entered.await()
        try {
            assertEquals(TokenPair("second","next2"),withTimeout(2000){second.refresh("old",secondStore.snapshot().epoch)})
        } finally {release.complete(Unit)}
        assertEquals(TokenPair("first","next1"),a.await())
    }

    @Test(timeout=15000) fun twoApiSessionsRetryBoth401sAfterOneGeneratedRefresh()=runBlocking {
        val oldRequests=CountDownLatch(2);val refreshes=AtomicInteger()
        MockWebServer().use {server->
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    if(request.path.orEmpty().contains("/device/refresh")) {
                        assertNull(request.getHeader("Authorization"));assertNull(request.getHeader("X-Makerspace-Id"))
                        if(refreshes.incrementAndGet()!=1)return MockResponse().setResponseCode(401)
                        return MockResponse().setHeader("Content-Type","application/json").setBodyDelay(350,TimeUnit.MILLISECONDS)
                            .setBody("""{"access":"new","refresh":"next","device_grant":{"id":"00000000-0000-0000-0000-000000000001","platform":"android","app_id":"org.spaceworks.app","environment":"development","status":"active","attested_at":"2026-10-16T00:00:00Z","last_used_at":"2026-10-16T00:00:00Z","created_at":"2026-10-16T00:00:00Z"}}""")
                    }
                    if(request.getHeader("Authorization")=="Bearer old") {
                        oldRequests.countDown();check(oldRequests.await(5,TimeUnit.SECONDS))
                        return MockResponse().setResponseCode(401).setBody("""{"detail":"Expired"}""")
                    }
                    assertEquals("Bearer new",request.getHeader("Authorization"))
                    return MockResponse().setHeader("Content-Type","application/json").setBody("""{"status":"ok"}""")
                }
            }
            server.start()
            val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))}
            val epoch=store.snapshot().epoch
            val first=ApiSession(server.url("/"),store){"7"}
            val second=ApiSession(server.url("/"),store){"8"}
            val a=async(start=CoroutineStart.UNDISPATCHED){first.health.apiV1HealthRetrieve()}
            val b=async(start=CoroutineStart.UNDISPATCHED){second.health.apiV1HealthRetrieve()}
            assertEquals(200,a.await().code());assertEquals(200,b.await().code())
            assertEquals(1,refreshes.get());assertEquals(5,server.requestCount)
            assertEquals(TokenPair("new","next"),store.read());assertEquals(epoch,store.snapshot().epoch)
            val requests=(1..5).map {checkNotNull(server.takeRequest(2,TimeUnit.SECONDS))}
            assertEquals(setOf("7","8"),requests.filter {it.getHeader("Authorization")=="Bearer new"}.map {it.getHeader("X-Makerspace-Id")}.toSet())
        }
    }
}
