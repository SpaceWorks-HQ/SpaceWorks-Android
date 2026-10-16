package org.spaceworks.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.auth.*
import org.spaceworks.app.network.*
import org.spaceworks.generated.model.*

class AuthGatewayTest {
    @Test fun generatedConfigChallengeLoginEnrollmentVerificationAndLogoutMatchContract()=runBlocking {
        MockWebServer().use {server ->
            val tokens=MemoryTokenStore().apply {replace(TokenPair("access","refresh"))}
            val gateway=GeneratedAuthGateway(ApiSession(server.url("/"),tokens),"org.example.whitelabel",EnvironmentEnum.development,DevelopmentAttestation(true,true))
            server.enqueue(MockResponse().setBody("""{"email_enabled":true,"public_image_max_bytes":100}"""));gateway.config()
            assertEquals("/api/v1/config",server.takeRequest().path)
            server.enqueue(MockResponse().setBody("""{"detail":"Password changed"}"""));gateway.changePassword("old","new")
            val password=server.takeRequest();assertEquals("/api/v1/auth/change-password",password.path)
            assertEquals("new",Json.parseToJsonElement(password.body.readUtf8()).jsonObject["new_password"]!!.jsonPrimitive.content)
            server.enqueue(MockResponse().setBody("""{"challenge":"one-use","expires_in":60}"""))
            server.enqueue(MockResponse().setBody("""{"mfa_required":true,"enrollment_required":true,"challenge":"mfa-proof","expires_in":300}"""))
            assertTrue(gateway.login("member","password") is DeviceLoginResult.MFAChallengeResponseValue)
            val challenge=server.takeRequest();assertEquals("/api/v1/auth/device/attestation-challenge",challenge.path)
            assertEquals("org.example.whitelabel",Json.parseToJsonElement(challenge.body.readUtf8()).jsonObject["app_id"]!!.jsonPrimitive.content)
            val login=server.takeRequest();assertEquals("/api/v1/auth/device/login",login.path)
            val body=Json.parseToJsonElement(login.body.readUtf8()).jsonObject
            assertEquals("one-use",body["challenge"]!!.jsonPrimitive.content);assertEquals("development",body["attestation"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertNull(login.getHeader("Authorization"));assertNull(login.getHeader("Cookie"))
            server.enqueue(MockResponse().setBody("""{"secret":"key","provisioning_uri":"otpauth://demo","qr_data_url":"data:image/png;base64,AA=="}"""))
            assertEquals("key",gateway.enroll("mfa-proof").secret);assertEquals("/api/v1/auth/mfa/enroll",server.takeRequest().path)
            listOf(false,true).forEach {enrollment ->
                server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"Code expired"}"""))
                val failure=try {gateway.verify("mfa-proof","123456",enrollment);null} catch(f:ApiFailure){f}
                assertEquals("Code expired",failure!!.detail)
                val request=server.takeRequest();assertEquals(if(enrollment)"/api/v1/auth/mfa/confirm" else "/api/v1/auth/mfa/verify",request.path)
                assertEquals("123456",Json.parseToJsonElement(request.body.readUtf8()).jsonObject["code"]!!.jsonPrimitive.content)
            }
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"No identity"}"""))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Expired refresh"}"""))
            try {gateway.me();fail()}catch(_:ApiFailure){}
            assertEquals("/api/v1/auth/me",server.takeRequest().path);assertEquals("/api/v1/auth/device/refresh",server.takeRequest().path)
            server.enqueue(MockResponse().setBody("""{"detail":"Signed out"}"""))
            // Re-establish a fixture token after the refused refresh for the logout transport assertion.
            tokens.replace(TokenPair("logout-access","logout-refresh"));gateway.logout()
            val logout=server.takeRequest();assertEquals("/api/v1/auth/device/logout",logout.path);assertEquals("Bearer logout-access",logout.getHeader("Authorization"))
        }
    }
    @Test fun developmentAttestationIsNeverAvailableInReleaseOrPhysicalDevices()=runBlocking {
        listOf(false to true,true to false,false to false).forEach {(debug,emulator)->
            try {DevelopmentAttestation(debug,emulator).attest("challenge");fail()}catch(_:AuthMessage){}
        }
    }
}

