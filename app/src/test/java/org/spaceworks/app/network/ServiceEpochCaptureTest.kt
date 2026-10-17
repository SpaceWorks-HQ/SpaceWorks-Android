package org.spaceworks.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.network.*
import org.spaceworks.generated.api.*
import org.spaceworks.generated.model.ChangePassword
import java.io.IOException

class ServiceEpochCaptureTest {
    @Test(timeout=10000) fun retainedScopedMutationCannotFirstSendUsingReplacementCredentials()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(204));server.start()
            val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))}
            val session=ApiSession(server.url("/"),store){"999"}
            val operation=session.scopedApi(LoansApi::class.java,7)
            store.replace(TokenPair("replacement","replacement-refresh"))
            val result=runCatching {operation.apiV1MemberMakerspacesLoansRenewCreate(17,7,emptyMap())}
            assertTrue("An old generated service must not first-send as the replacement account",result.exceptionOrNull() is IOException)
            assertEquals(0,server.requestCount);assertEquals(TokenPair("replacement","replacement-refresh"),store.read())
        }
    }
    @Test(timeout=10000) fun retainedGlobalMutationCannotFirstSendUsingReplacementCredentials()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(204));server.start()
            val store=MemoryTokenStore().apply {replace(TokenPair("old","refresh"))}
            val operation=ApiSession(server.url("/"),store).globalApi(AuthApi::class.java)
            store.replace(TokenPair("replacement","replacement-refresh"))
            val result=runCatching {operation.apiV1AuthChangePasswordCreate(ChangePassword("old-password","new-password"))}
            assertTrue(result.exceptionOrNull() is IOException)
            assertEquals(0,server.requestCount);assertEquals(TokenPair("replacement","replacement-refresh"),store.read())
        }
    }
}
