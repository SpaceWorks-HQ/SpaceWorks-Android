package org.spaceworks.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.auth.*
import org.spaceworks.app.network.*
import org.spaceworks.generated.model.*

private class RestoreFake:AuthGateway {
    var started=0;var restored=0
    var result=AccountRestoreResult(true,"Account restored. Sign in with your new password.")
    var failure:ApiFailure?=null
    override suspend fun config()=PublicConfig(false,100)
    override suspend fun login(username:String,password:String)=throw AssertionError()
    override suspend fun enroll(challenge:String)=throw AssertionError()
    override suspend fun verify(challenge:String,code:String,enrollment:Boolean)=throw AssertionError()
    override suspend fun me()=throw AssertionError()
    override suspend fun logout() {}
    override suspend fun phoneStart(phone:String):PhoneChallenge {started++;return PhoneChallenge("restore-challenge",300)}
    override suspend fun restorePhone(phone:String,code:String,newPassword:String,challenge:PhoneChallenge):AccountRestoreResult {
        restored++;failure?.let {throw it};assertEquals("restore-challenge",challenge.challenge);assertEquals("+919876543210",phone);return result
    }
}

class AccountRestoreTest {
    @Test fun restoreValidatesThenUsesTheStartedChallengeAndNeverSignsIn()=runTest {
        val api=RestoreFake();val holder=AccountStateHolder(api,{false})
        holder.open(AccountRoute.RESTORE_PHONE)
        holder.restoreStart("98765");assertEquals(0,api.started);assertNotNull(holder.state.value.error)
        holder.restoreStart("+91 98765 43210");assertEquals(AccountRoute.RESTORE_CODE,holder.state.value.route);assertEquals("+919876543210",holder.state.value.phone)
        holder.restore("123456","short","short");assertEquals(0,api.restored)
        holder.restore("123456","new-password","other-password");assertEquals(0,api.restored)
        holder.restore("123456","new-password","new-password")
        assertEquals(AccountRoute.RESTORE_SUCCESS,holder.state.value.route);assertEquals("Account restored. Sign in with your new password.",holder.state.value.notice)
    }
    @Test fun serverRefusalKeepsItsReason()=runTest {
        val api=RestoreFake().apply {failure=ApiFailure(409,"Purge has already started for this account.",null,null)}
        val holder=AccountStateHolder(api,{false});holder.open(AccountRoute.RESTORE_PHONE)
        holder.restoreStart("+919876543210");holder.restore("123456","new-password","new-password")
        assertEquals(AccountRoute.RESTORE_CODE,holder.state.value.route);assertEquals("Purge has already started for this account.",holder.state.value.error)
    }
    @Test fun restoreSendsDeviceChallengeAttestationAndNewPassword()=runBlocking {
        MockWebServer().use {server->
            val gateway=GeneratedAuthGateway(ApiSession(server.url("/")),"org.example",EnvironmentEnum.development,DevelopmentAttestation(true,true))
            server.enqueue(MockResponse().setBody("""{"restored":true,"detail":"Restored."}"""))
            val result=gateway.restorePhone("+919876543210","123456","new-password",PhoneChallenge("c9",300))
            assertTrue(result.restored)
            val request=server.takeRequest();assertEquals("/api/v1/auth/account/restore/phone",request.path);assertNull(request.getHeader("Authorization"))
            val body=Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("c9",body["challenge"]!!.jsonPrimitive.content);assertEquals("device",body["delivery"]!!.jsonPrimitive.content)
            assertEquals("new-password",body["new_password"]!!.jsonPrimitive.content);assertEquals("development",body["attestation"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
    }
}
