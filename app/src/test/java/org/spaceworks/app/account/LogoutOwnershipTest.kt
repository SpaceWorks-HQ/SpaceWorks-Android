package org.spaceworks.app.account

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.auth.*
import org.spaceworks.app.network.*
import org.spaceworks.generated.model.EnvironmentEnum
import java.util.concurrent.TimeUnit

class LogoutOwnershipTest {
    @Test fun loginDuringOldPushCleanupNeverSendsLogoutOrClearsNewNativeIdentity()=runBlocking {
        MockWebServer().use {server->
            val tokens=MemoryTokenStore().apply {replace(TokenPair("old","old-r"))}
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var nativeClears=0
            val gateway=GeneratedAuthGateway(ApiSession(server.url("/"),tokens),"org.example",EnvironmentEnum.development,DevelopmentAttestation(true,true),
                onNativeSignOut={nativeClears++},onBeforeNativeSignOut={entered.complete(Unit);release.await()})
            val job=async {runCatching {gateway.logout()}}
            withTimeout(2000){entered.await()};tokens.replace(TokenPair("new","new-r"));release.complete(Unit)
            assertTrue(withTimeout(2000){job.await()}.exceptionOrNull() is CancellationException)
            assertNull(server.takeRequest(200,TimeUnit.MILLISECONDS));assertEquals(0,nativeClears)
            assertEquals(TokenPair("new","new-r"),tokens.read())
        }
    }
    @Test fun replacementDuringLogoutResponseNeverClearsNewNativeIdentity()=runBlocking {
        MockWebServer().use {server->
            val tokens=MemoryTokenStore().apply {replace(TokenPair("old","old-r"))};var nativeClears=0
            server.enqueue(MockResponse().setBody("{\"detail\":\"Signed out\"}").setBodyDelay(250,TimeUnit.MILLISECONDS))
            val gateway=GeneratedAuthGateway(ApiSession(server.url("/"),tokens),"org.example",EnvironmentEnum.development,DevelopmentAttestation(true,true),onNativeSignOut={nativeClears++})
            val job=async(Dispatchers.Default) {gateway.logout()}
            val request=withContext(Dispatchers.IO){server.takeRequest(2,TimeUnit.SECONDS)}!!
            assertEquals("Bearer old",request.getHeader("Authorization"));assertNull(request.getHeader("X-Makerspace-Id"))
            tokens.replace(TokenPair("new","new-r"));withTimeout(2000){job.await()}
            assertEquals(0,nativeClears);assertEquals(TokenPair("new","new-r"),tokens.read())
        }
    }
    @Test fun unchangedInstallationStillRevokesAndClearsSdkAfterFailedPushCleanup()=runBlocking {
        MockWebServer().use {server->
            val tokens=MemoryTokenStore().apply {replace(TokenPair("old","old-r"))};var nativeClears=0
            server.enqueue(MockResponse().setBody("{\"detail\":\"Signed out\"}"))
            val gateway=GeneratedAuthGateway(ApiSession(server.url("/"),tokens),"org.example",EnvironmentEnum.development,DevelopmentAttestation(true,true),
                onNativeSignOut={nativeClears++},onBeforeNativeSignOut={error("Push cleanup unavailable")})
            assertTrue(runCatching {gateway.logout()}.isFailure)
            assertEquals("/api/v1/auth/device/logout",server.takeRequest(2,TimeUnit.SECONDS)!!.path)
            assertEquals(1,nativeClears)
        }
    }
}
