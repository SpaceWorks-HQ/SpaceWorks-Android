package org.spaceworks.app.actions

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.*
import java.util.concurrent.TimeUnit

class MakerCapabilitiesTest {
    private val scope=MemberScope(7,"maker-space")
    private fun bootstrap(id:Int=7,modules:String="[\"membership\",\"member_accounts\"]",features:String="[\"membership.self_service\",\"notifications.member_preferences\",\"notifications.digests\"]")="""{
      "branding":{},"theme":{},"edition":"community","email_enabled":true,"modules":$modules,"features":$features,"workflows":[],
      "frontend":{"type":"web","hostname":"example.invalid","allowed_origins":[]},
      "public_api":{"base_url":"https://example.invalid/api/v1","inventory_path":"/inventory","publishable_key":"unused"},
      "makerspace":{"id":$id,"name":"Maker space","slug":"maker-space","public_code":"DEMO","location":"Kochi","timezone":"Asia/Kolkata","opening_hours":{},"map_url":"","logo_url":null,"cover_image_url":null,"public_stats_enabled":false,"public_site_enabled":true,"public_inventory_enabled":true,"public_landing_page":"auto","membership_policy":"request"}
    }"""
    private fun response(body:String)=MockResponse().setHeader("Content-Type","application/json").setBody(body)
    @Test fun generatedBootstrapUsesPublishedSlugAndImmutableScopeWithoutBrowserCredentials()=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(response(bootstrap()));val tokens=MemoryTokenStore().apply {replace(TokenPair("access","refresh"))}
            val result=GeneratedMakerCapabilitiesGateway(ApiSession(server.url("/"),tokens){"999"}).load(scope)
            assertEquals(MakerCapabilities.DEMO,result)
            val request=requireNotNull(server.takeRequest(5,TimeUnit.SECONDS));assertEquals("/api/v1/bootstrap?slug=maker-space",request.path);assertEquals("7",request.getHeader("X-Makerspace-Id"));assertEquals("Bearer access",request.getHeader("Authorization"))
            listOf("Origin","Referer","Cookie","X-Publishable-Key","X-Nonce").forEach {assertNull(request.getHeader(it))}
        }
    }
    @Test fun missingModulesAndFeaturesNeverInferSelfServiceFromEditionOrOtherFeatures()=runBlocking {
        MockWebServer().use {server->server.start();val gateway=GeneratedMakerCapabilitiesGateway(ApiSession(server.url("/")))
            server.enqueue(response(bootstrap(modules="[\"membership\"]")));assertEquals(MakerCapabilities(false,true,true),gateway.load(scope))
            server.enqueue(response(bootstrap(features="[]")));assertEquals(MakerCapabilities.NONE,gateway.load(scope))
            server.enqueue(response(bootstrap(features="[\"membership.self_service\"]")));assertEquals(MakerCapabilities(true,false,false),gateway.load(scope))
        }
    }
    @Test fun wrongMakerAndBootstrapRefusalNeverPublishPermissions()=runBlocking {
        MockWebServer().use {server->server.start();val gateway=GeneratedMakerCapabilitiesGateway(ApiSession(server.url("/")))
            server.enqueue(response(bootstrap(id=8)));val mismatch=runCatching {gateway.load(scope)}.exceptionOrNull();assertTrue(mismatch is ApiFailure);assertEquals(403,(mismatch as ApiFailure).status)
            server.enqueue(response("""{"detail":"Makerspace unavailable."}""").setResponseCode(503));val refused=runCatching {gateway.load(scope)}.exceptionOrNull();assertTrue(refused is ApiFailure);assertEquals("Makerspace unavailable.",refused?.message)
        }
    }
}
