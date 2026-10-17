package org.spaceworks.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.auth.*
import org.spaceworks.app.network.*
import org.spaceworks.generated.model.*

class AccountGatewayTest {
    @Test fun accountOperationsUseGeneratedExactShapesAndPublicCallsHaveNoBearer()=runBlocking {
        MockWebServer().use {server->
            val store=MemoryTokenStore().apply {replace(TokenPair("protected-access","protected-refresh"))}
            val gateway=GeneratedAuthGateway(ApiSession(server.url("/"),store),"org.example.native",EnvironmentEnum.development,DevelopmentAttestation(true,true))
            fun ack(){server.enqueue(MockResponse().setBody("""{"detail":"Acknowledged"}"""))}
            ack();gateway.signUp("Meera","member@example.invalid","sample-new-password","")
            val signup=server.takeRequest();assertEquals("/api/v1/auth/member-sign-up",signup.path);assertNull(signup.getHeader("Authorization"))
            val signupBody=Json.parseToJsonElement(signup.body.readUtf8()).jsonObject
            assertEquals("Meera",signupBody["display_name"]!!.jsonPrimitive.content);assertFalse(signupBody.containsKey("confirmation"))
            assertEquals("",signupBody["website"]?.jsonPrimitive?.content.orEmpty())
            ack();gateway.forgotPassword("member@example.invalid")
            val forgot=server.takeRequest();assertEquals("/api/v1/auth/forgot-password",forgot.path);assertNull(forgot.getHeader("Authorization"))
            ack();gateway.resetPassword("member@example.invalid","123456","sample-new-password")
            val reset=server.takeRequest();assertEquals("/api/v1/auth/reset-password",reset.path);assertNull(reset.getHeader("Authorization"))
            val resetBody=Json.parseToJsonElement(reset.body.readUtf8()).jsonObject
            assertEquals(setOf("email","code","new_password"),resetBody.keys);assertEquals("123456",resetBody["code"]!!.jsonPrimitive.content)
            ack();gateway.confirmEmail("123456")
            val confirm=server.takeRequest();assertEquals("/api/v1/auth/email-verification/confirm",confirm.path);assertEquals("Bearer protected-access",confirm.getHeader("Authorization"))
            ack();gateway.resendEmail()
            val resend=server.takeRequest();assertEquals("/api/v1/auth/email-verification/resend",resend.path);assertEquals(0,resend.bodySize)
        }
    }
    @Test fun googleBindsFreshDeviceChallengeAndServerNonceToGeneratedExchange()=runBlocking {
        MockWebServer().use {server->
            val gateway=GeneratedAuthGateway(ApiSession(server.url("/")),"org.example.native",EnvironmentEnum.development,DevelopmentAttestation(true,true))
            server.enqueue(MockResponse().setBody("""{"challenge":"fresh-device-proof","expires_in":60}"""))
            server.enqueue(MockResponse().setBody("""{"nonce":"fresh-server-nonce","expires_in":120}"""))
            server.enqueue(MockResponse().setBody("""{"access":"test-access","refresh":"test-refresh","device_grant":{"id":"00000000-0000-0000-0000-000000000001"},"user":{},"outcome":"signed_in"}"""))
            val result=gateway.google("published-web-client-id"){client,nonce->
                assertEquals("published-web-client-id",client);assertEquals("fresh-server-nonce",nonce);"synthetic-id-token"
            }
            assertEquals("test-refresh",result.refresh)
            assertEquals("/api/v1/auth/device/attestation-challenge",server.takeRequest().path)
            val nonce=server.takeRequest();assertEquals("/api/v1/auth/social/nonce",nonce.path)
            val nonceBody=Json.parseToJsonElement(nonce.body.readUtf8()).jsonObject
            assertEquals("device",nonceBody["delivery"]!!.jsonPrimitive.content);assertEquals("android",nonceBody["client_platform"]!!.jsonPrimitive.content)
            assertEquals("fresh-device-proof",nonceBody["challenge"]!!.jsonPrimitive.content)
            val exchange=server.takeRequest();assertEquals("/api/v1/auth/social/google",exchange.path);assertNull(exchange.getHeader("Authorization"))
            val body=Json.parseToJsonElement(exchange.body.readUtf8()).jsonObject
            assertEquals("fresh-server-nonce",body["nonce"]!!.jsonPrimitive.content);assertEquals("synthetic-id-token",body["id_token"]!!.jsonPrimitive.content)
            assertEquals("fresh-device-proof",body["challenge"]!!.jsonPrimitive.content);assertEquals("development",body["attestation"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
    }
}
