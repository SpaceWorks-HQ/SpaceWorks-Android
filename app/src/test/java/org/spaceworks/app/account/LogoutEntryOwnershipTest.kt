package org.spaceworks.app.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.auth.*
import org.spaceworks.app.network.*
import org.spaceworks.generated.model.*
import java.util.concurrent.TimeUnit

class LogoutEntryOwnershipTest {
    @Test fun pendingIdentityReadCannotBorrowAReplacementBeforeItsFirstSend()=runBlocking {
        MockWebServer().use {server->
            val tokens=MemoryTokenStore().apply {replace(TokenPair("old","old-r"))};val expected=tokens.snapshot().epoch
            val session=ApiSession(server.url("/"),tokens);session.bindIdentityEpoch {null}
            val gateway=GeneratedAuthGateway(session,"org.example",EnvironmentEnum.development,DevelopmentAttestation(true,true))
            tokens.replace(TokenPair("new","new-r"))
            server.enqueue(MockResponse().setBody(Json.encodeToString(AuthUserPayload("member",2,"new","new@example.invalid","New","",true,"member",false,false,emptyList()))))
            assertTrue(runCatching {gateway.me(expected)}.exceptionOrNull() is java.io.IOException)
            assertNull(server.takeRequest(200,TimeUnit.MILLISECONDS));assertEquals(TokenPair("new","new-r"),tokens.read())
        }
    }
    @Test fun forcedPasswordRequestMustUseItsSuppliedInstallationNotTheCurrentOne()=runBlocking {
        MockWebServer().use {server->
            val tokens=MemoryTokenStore().apply {replace(TokenPair("old","old-r"))};val expected=tokens.snapshot().epoch
            val gateway=GeneratedAuthGateway(ApiSession(server.url("/"),tokens),"org.example",EnvironmentEnum.development,DevelopmentAttestation(true,true))
            tokens.replace(TokenPair("new","new-r"));server.enqueue(MockResponse().setBody("{\"detail\":\"Password changed\"}"))
            assertTrue(runCatching {gateway.changePassword("current-password","replacement-password",expected)}.exceptionOrNull() is CancellationException)
            assertNull(server.takeRequest(200,TimeUnit.MILLISECONDS));assertEquals(TokenPair("new","new-r"),tokens.read())
        }
    }
    @Test fun obsoletePasswordFormCannotActAfterItsCachedIdentityWasInvalidated()=runBlocking {
        MockWebServer().use {server->
            val tokens=MemoryTokenStore().apply {replace(TokenPair("old","old-r"))}
            val gateway=GeneratedAuthGateway(ApiSession(server.url("/"),tokens),"org.example",EnvironmentEnum.development,DevelopmentAttestation(true,true))
            val holder=AuthStateHolder(gateway,tokens)
            server.enqueue(MockResponse().setBody("{\"email_enabled\":false,\"public_image_max_bytes\":100}"))
            server.enqueue(MockResponse().setBody(Json.encodeToString(AuthUserPayload("member",1,"old","old@example.invalid","Old","",true,"member",false,true,emptyList()))))
            holder.initialize();server.takeRequest();server.takeRequest()
            tokens.replace(TokenPair("new","new-r"));assertTrue(holder.invalidateInstallation(tokens.snapshot().epoch))
            server.enqueue(MockResponse().setBody("{\"detail\":\"Password changed\"}"))
            holder.changePassword("current-password","replacement-password","replacement-password")
            assertEquals("Please sign in again.",holder.state.value.error)
            assertNull(server.takeRequest(200,TimeUnit.MILLISECONDS));assertEquals(2,server.requestCount)
            assertEquals(TokenPair("new","new-r"),tokens.read())
        }
    }
    @Test fun signOutCannotCaptureReplacementInstalledByPrivateCleanupBeforeGatewayEntry()=runBlocking {check(false)}
    @Test fun forcedPasswordCleanupCannotCaptureReplacementBeforeGatewayEntry()=runBlocking {check(true)}
    private suspend fun check(forcedPassword:Boolean) {
        MockWebServer().use {server->
            val tokens=MemoryTokenStore().apply {replace(TokenPair("old","old-r"))}
            var nativeClears=0;var pushCleanups=0;var replaced=false
            val gateway=GeneratedAuthGateway(ApiSession(server.url("/"),tokens),"org.example",EnvironmentEnum.development,
                DevelopmentAttestation(true,true),onNativeSignOut={nativeClears++},onBeforeNativeSignOut={pushCleanups++})
            val holder=AuthStateHolder(gateway,tokens,onSessionEnded={
                if(!replaced) {replaced=true;tokens.replace(TokenPair("new","new-r"))}
            })
            server.enqueue(MockResponse().setBody("{\"email_enabled\":false,\"public_image_max_bytes\":100}"))
            server.enqueue(MockResponse().setBody(Json.encodeToString(AuthUserPayload("member",1,"old","old@example.invalid","Old","",true,"member",false,forcedPassword,emptyList()))))
            holder.initialize()
            assertNotNull(holder.state.value.user)
            assertEquals("/api/v1/config",server.takeRequest(2,TimeUnit.SECONDS)!!.path)
            assertEquals("/api/v1/auth/me",server.takeRequest(2,TimeUnit.SECONDS)!!.path)
            if(forcedPassword)server.enqueue(MockResponse().setBody("{\"detail\":\"Password changed\"}"))
            server.enqueue(MockResponse().setBody("{\"detail\":\"Signed out\"}"))
            val failure=runCatching {
                if(forcedPassword)holder.changePassword("current-password","replacement-password","replacement-password")
                else holder.signOut()
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            if(forcedPassword) {
                val password=server.takeRequest(2,TimeUnit.SECONDS)!!
                assertEquals("/api/v1/auth/change-password",password.path);assertEquals("Bearer old",password.getHeader("Authorization"))
            }
            assertNull(server.takeRequest(200,TimeUnit.MILLISECONDS))
            assertEquals(if(forcedPassword)3 else 2,server.requestCount)
            assertEquals(TokenPair("new","new-r"),tokens.read());assertNull(holder.state.value.user)
            assertEquals(0,nativeClears);assertEquals(0,pushCleanups)
        }
    }
}
