package org.spaceworks.app.notifications

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.network.*
import org.spaceworks.generated.model.EnvironmentEnum
import java.util.concurrent.TimeUnit

class PushGatewayTest {
    private suspend fun using(block:suspend(GeneratedPushGateway,MockWebServer)->Unit) {
        MockWebServer().use {server->server.start();val tokens=MemoryTokenStore().apply {replace(TokenPair("member-access","member-refresh"))};block(GeneratedPushGateway(ApiSession(server.url("/"),tokens){"999"},EnvironmentEnum.development),server)}
    }
    private fun json(body:String,status:Int=200)=MockResponse().setResponseCode(status).setHeader("Content-Type","application/json").setBody(body)
    private fun device(maker:Int=7,provider:String="fcm",environment:String="development",active:Boolean=true)="""{"id":29,"provider":"$provider","environment":"$environment","makerspace_id":$maker,"active":$active,"created_at":"2026-10-16T00:00:00Z","updated_at":"2026-10-16T00:00:00Z"}"""
    @Test fun generatedRegistrationAndDeleteUseCapturedScopeAndContractFieldsOnly()=runBlocking {using {gateway,server->
        server.enqueue(json(device(),201));assertEquals(29,gateway.register(7,TOKEN));val post=requireNotNull(server.takeRequest(5,TimeUnit.SECONDS));assertEquals("POST",post.method);assertEquals("/api/v1/integrations/push/devices",post.path);assertEquals("Bearer member-access",post.getHeader("Authorization"));assertEquals("7",post.getHeader("X-Makerspace-Id"));for(header in listOf("Origin","Referer","Cookie"))assertNull(post.getHeader(header))
        val body=kotlinx.serialization.json.Json.parseToJsonElement(post.body.readUtf8()) as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("token","provider","environment"),body.keys);assertEquals(TOKEN,(body.getValue("token") as kotlinx.serialization.json.JsonPrimitive).content);assertEquals("fcm",(body.getValue("provider") as kotlinx.serialization.json.JsonPrimitive).content)
        server.enqueue(MockResponse().setResponseCode(204));gateway.remove(7,29);val delete=requireNotNull(server.takeRequest(5,TimeUnit.SECONDS));assertEquals("DELETE",delete.method);assertEquals("/api/v1/integrations/push/devices/29",delete.path);assertEquals("7",delete.getHeader("X-Makerspace-Id"));assertEquals(0,delete.bodySize)
    }}
    @Test fun registrationRefusesWrongMakerProviderEnvironmentAndInactiveResponse()=runBlocking {using {gateway,server->
        for(body in listOf(device(maker=8),device(provider="apns"),device(environment="production"),device(active=false))) {server.enqueue(json(body));assertTrue(runCatching {gateway.register(7,TOKEN)}.isFailure);requireNotNull(server.takeRequest(5,TimeUnit.SECONDS))}
    }}
    @Test fun removal404MeansAlreadyRemovedBut403RemainsUnconfirmed()=runBlocking {using {gateway,server->
        server.enqueue(json("""{"detail":"Already removed"}""",404));gateway.remove(7,29)
        server.enqueue(json("""{"detail":"Permission changed"}""",403));val failed=runCatching {gateway.remove(7,29)}.exceptionOrNull() as ApiFailure;assertEquals(403,failed.status);assertEquals("Permission changed",failed.detail)
    }}
    @Test fun retryAfterAndServiceErrorsPreserveSafeEnvelopeAndNeverReplay()=runBlocking {using {gateway,server->
        server.enqueue(json("""{"detail":"Try later"}""",429).setHeader("Retry-After","30"));val limited=runCatching {gateway.register(7,TOKEN)}.exceptionOrNull() as ApiFailure;assertEquals(30L,limited.retryAfterSeconds)
        server.enqueue(json("""{"detail":"Push service unavailable"}""",503).setHeader("Retry-After","0"));server.enqueue(json(device()));val failure=runCatching {gateway.register(7,TOKEN)}.exceptionOrNull() as ApiFailure;assertEquals(503,failure.status);assertEquals(2,server.requestCount)
    }}
    companion object {private const val TOKEN="valid_fcm_token_123456789012345"}
}
