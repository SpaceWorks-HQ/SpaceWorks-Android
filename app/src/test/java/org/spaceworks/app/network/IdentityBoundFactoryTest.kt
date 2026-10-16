package org.spaceworks.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.network.*
import org.spaceworks.generated.api.*
import java.io.IOException
import java.util.concurrent.TimeUnit

class IdentityBoundFactoryTest {
    @Test fun staleAcceptedIdentityCannotCreateServiceAfterSharedStoreReplacement() {
        MockWebServer().use {server->
            server.start();val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))}
            val accepted=store.snapshot().epoch
            val session=ApiSession(server.url("/"),store)
            session.bindIdentityEpoch {accepted.takeIf{store.snapshot().epoch==accepted}}
            store.replace(TokenPair("replacement","replacement-refresh"))
            assertThrows(IOException::class.java){session.scopedApi(HealthApi::class.java,7)}
            assertThrows(IOException::class.java){session.globalApi(HealthApi::class.java)}
            assertEquals(0,server.requestCount);assertEquals(TokenPair("replacement","replacement-refresh"),store.read())
        }
    }
    @Test fun pendingOwnLoginCannotUseDomainServicesButFreshAuthBootstrapRemainsAvailable()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(204));server.start()
            val store=MemoryTokenStore().apply {replace(TokenPair("pending","pending-r"))}
            val session=ApiSession(server.url("/"),store);session.bindIdentityEpoch {null}
            assertThrows(IOException::class.java){session.scopedApi(HealthApi::class.java,7)}
            assertEquals(204,session.auth.apiV1AuthMeRetrieve().code())
            assertEquals("Bearer pending",server.takeRequest(2,TimeUnit.SECONDS)!!.getHeader("Authorization"))
        }
    }
    @Test fun explicitOwnedCleanupWorksAfterPrivateIdentityClearAndRejectsReplacement()=runBlocking {
        MockWebServer().use {server->
            repeat(3){server.enqueue(MockResponse().setResponseCode(204))};server.start()
            val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))};val expected=store.snapshot().epoch
            val session=ApiSession(server.url("/"),store){"999"};session.bindIdentityEpoch {null}
            assertEquals(204,session.pinnedScopedApi(HealthApi::class.java,7,expected).apiV1HealthRetrieve().code())
            assertEquals(204,session.pinnedGlobalApi(HealthApi::class.java,expected).apiV1HealthRetrieve().code())
            val scoped=server.takeRequest(2,TimeUnit.SECONDS)!!;val global=server.takeRequest(2,TimeUnit.SECONDS)!!
            assertEquals("7",scoped.getHeader("X-Makerspace-Id"));assertNull(global.getHeader("X-Makerspace-Id"))
            assertEquals("Bearer old",scoped.getHeader("Authorization"));assertEquals("Bearer old",global.getHeader("Authorization"))
            store.replace(TokenPair("replacement","replacement-refresh"))
            val result=runCatching {session.pinnedScopedApi(HealthApi::class.java,7,expected).apiV1HealthRetrieve()}
            assertTrue(result.exceptionOrNull() is IOException);assertEquals(2,server.requestCount)
            assertEquals(TokenPair("replacement","replacement-refresh"),store.read())
        }
    }
    @Test fun retainedServiceAcceptsSameInstallationTokenRotation()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(204));server.start()
            val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))};val expected=store.snapshot().epoch
            val session=ApiSession(server.url("/"),store);session.bindIdentityEpoch {expected}
            val service=session.scopedApi(HealthApi::class.java,7)
            assertTrue(store.rotateIfCurrent(store.snapshot(),TokenPair("rotated","next")))
            assertEquals(204,service.apiV1HealthRetrieve().code())
            assertEquals("Bearer rotated",server.takeRequest(2,TimeUnit.SECONDS)!!.getHeader("Authorization"))
        }
    }
}
