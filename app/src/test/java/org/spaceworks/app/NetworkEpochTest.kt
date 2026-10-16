package org.spaceworks.app

import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.network.*
import org.spaceworks.generated.model.ChangePassword
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NetworkEpochTest {
    @Test fun delayedOld401CannotReplayMutationWithNewAccountCredentials()=runBlocking {withTimeout(15000) {
        val release=CountDownLatch(1);val entered=CompletableDeferred<Unit>()
        MockWebServer().use {server->
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {entered.complete(Unit);check(release.await(10,TimeUnit.SECONDS));return MockResponse().setResponseCode(401).setHeader("Content-Type","application/json").setBody("""{"detail":"Expired old account"}""")}}
            server.start();val store=MemoryTokenStore().apply {replace(TokenPair("old-access","old-refresh"))};val session=ApiSession(server.url("/"),store){"7"}
            try {
                val old=async(start=CoroutineStart.UNDISPATCHED){session.auth.apiV1AuthChangePasswordCreate(ChangePassword("old-password","replacement-password"))}
                entered.await();store.replace(null);store.replace(TokenPair("new-access","new-refresh"));release.countDown()
                assertEquals(401,old.await().code());assertEquals(1,server.requestCount);assertEquals(TokenPair("new-access","new-refresh"),store.read())
                val sent=server.takeRequest(5,TimeUnit.SECONDS)!!;assertEquals("Bearer old-access",sent.getHeader("Authorization"));assertEquals("7",sent.getHeader("X-Makerspace-Id"))
            }finally {release.countDown()}
        }
    }}
    @Test fun queuedOldMutationCannotFirstSendUnderNewLogin()=runBlocking {withTimeout(15000) {
        val release=CountDownLatch(1);val entered=CompletableDeferred<Unit>()
        MockWebServer().use {server->
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {entered.complete(Unit);check(release.await(10,TimeUnit.SECONDS));return MockResponse().setHeader("Content-Type","application/json").setBody("""{"status":"ok"}""")}}
            server.start();val store=MemoryTokenStore().apply {replace(TokenPair("old-access","old-refresh"))};val session=ApiSession(server.url("/"),store){"7"};session.client.dispatcher.maxRequests=1;session.client.dispatcher.maxRequestsPerHost=1
            try {
                val health=async(start=CoroutineStart.UNDISPATCHED){session.health.apiV1HealthRetrieve()};entered.await()
                val queued=async(start=CoroutineStart.UNDISPATCHED){runCatching {session.auth.apiV1AuthChangePasswordCreate(ChangePassword("old-password","replacement-password"))}}
                store.replace(null);store.replace(TokenPair("new-access","new-refresh"));release.countDown();assertEquals(200,health.await().code())
                assertTrue(queued.await().exceptionOrNull() is IOException);assertEquals(1,server.requestCount)
            }finally {release.countDown()}
        }
    }}
    @Test fun accountChangeAfterRotationIsRecheckedBeforePhysicalRetry()=runBlocking {withTimeout(15000) {
        val memory=MemoryTokenStore().apply {replace(TokenPair("old-access","old-refresh"))}
        val store=object:TokenStore {
            override fun read()=memory.read()
            override fun replace(pair:TokenPair?)=memory.replace(pair)
            override fun rotateIfCurrent(expected:TokenSnapshot,pair:TokenPair?)=memory.rotateIfCurrent(expected,pair)
            override fun snapshot():TokenSnapshot {
                if(memory.read()?.access=="rotated-old")memory.replace(TokenPair("new-account","new-refresh"))
                return memory.snapshot()
            }
        }
        MockWebServer().use {server->
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse =
                if(request.path.orEmpty().contains("/device/refresh"))MockResponse().setHeader("Content-Type","application/json").setBody("""{"access":"rotated-old","refresh":"rotated-refresh","device_grant":{"id":"00000000-0000-0000-0000-000000000001","platform":"android","app_id":"org.spaceworks.app","environment":"development","status":"active","attested_at":"2026-10-16T00:00:00Z","last_used_at":"2026-10-16T00:00:00Z","created_at":"2026-10-16T00:00:00Z"}}""")
                else MockResponse().setResponseCode(401).setHeader("Content-Type","application/json").setBody("""{"detail":"Expired"}""")}
            server.start();val session=ApiSession(server.url("/"),store){"7"}
            val result=runCatching {session.auth.apiV1AuthChangePasswordCreate(ChangePassword("old","replacement"))}
            assertTrue(result.exceptionOrNull() is IOException);assertEquals(2,server.requestCount);assertEquals(TokenPair("new-account","new-refresh"),store.read())
        }
    }}
    @Test fun delayedRefreshSuccessCannotOverwriteNewInstallation()=runBlocking {
        val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))};val oldEpoch=store.snapshot().epoch;val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val coordinator=RefreshCoordinator(store){started.complete(Unit);release.await();TokenPair("rotated-old","rotated-refresh")}
        val old=async(start=CoroutineStart.UNDISPATCHED){coordinator.refresh("old",oldEpoch)};started.await();store.replace(null);store.replace(TokenPair("new","new-refresh"));release.complete(Unit)
        assertNull(old.await());assertEquals(TokenPair("new","new-refresh"),store.read())
    }
    @Test fun delayedRefreshFailureCannotClearNewInstallation()=runBlocking {
        val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))};val oldEpoch=store.snapshot().epoch;val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val coordinator=RefreshCoordinator(store){started.complete(Unit);release.await();throw IOException("old rotation refused")}
        val old=async(start=CoroutineStart.UNDISPATCHED){runCatching {coordinator.refresh("old",oldEpoch)}};started.await();store.replace(null);store.replace(TokenPair("new","new-refresh"));release.complete(Unit)
        assertTrue(old.await().exceptionOrNull() is IOException);assertEquals(TokenPair("new","new-refresh"),store.read())
    }
    @Test fun sameSessionLate401ReusesRotationButReinstallationOfIdenticalTextDoesNot()=runBlocking {
        val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))};val epoch=store.snapshot().epoch;var rotations=0
        val coordinator=RefreshCoordinator(store){rotations++;TokenPair("rotated","rotated-refresh")}
        assertEquals(TokenPair("rotated","rotated-refresh"),coordinator.refresh("old",epoch));assertEquals(epoch,store.snapshot().epoch)
        assertEquals(TokenPair("rotated","rotated-refresh"),coordinator.refresh("old",epoch));assertEquals(1,rotations)
        store.replace(null);store.replace(TokenPair("rotated","rotated-refresh"));assertNull(coordinator.refresh("old",epoch));assertEquals(1,rotations)
    }
    @Test fun atomicStoreRejectsBothStaleRotationAndStaleClear() {
        val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))};val old=store.snapshot();store.replace(TokenPair("new","new-refresh"))
        assertFalse(store.rotateIfCurrent(old,TokenPair("stale","stale-refresh")));assertFalse(store.rotateIfCurrent(old,null));assertEquals(TokenPair("new","new-refresh"),store.read())
        val current=store.snapshot();assertTrue(store.rotateIfCurrent(current,TokenPair("current-rotated","current-refresh")));assertEquals(current.epoch,store.snapshot().epoch)
    }
    @Test fun globalGeneratedServiceStripsSelectedMakerButRetainsBearerAndObservedEpochCall()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("{\"status\":\"ok\"}"));server.start()
            val store=MemoryTokenStore().apply {replace(TokenPair("account-access","account-refresh"))}
            val session=ApiSession(server.url("/"),store){"999"}
            var observed:okhttp3.Call?=null
            val response=session.globalApi(org.spaceworks.generated.api.HealthApi::class.java){observed=it}.apiV1HealthRetrieve()
            assertEquals(200,response.code());assertNotNull(observed)
            val request=server.takeRequest(5,TimeUnit.SECONDS)!!
            assertEquals("Bearer account-access",request.getHeader("Authorization"));assertNull(request.getHeader("X-Makerspace-Id"))
            assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("Origin"));assertNull(request.getHeader("Referer"))
        }
    }

}
