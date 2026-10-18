package org.spaceworks.app.account

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.network.*
import java.util.concurrent.TimeUnit

private const val BLOCKER="""{"code":"unpaid_charges","makerspace_id":7,"makerspace_slug":"workshop","makerspace_member_url":null,"organization_id":null,"organization_url":null,"count":2,"detail":"Two charges need payment."}"""
private const val ME="""{"session_authority":"member","id":1,"username":"m","email":"m@example.invalid","display_name":"M","phone":"","email_verified":true,"role":"member","is_superuser":false,"must_change_password":false,"makerspaces":[]}"""

class AccountDeletionTest {
    private fun json(body:String,code:Int=200)=MockResponse().setResponseCode(code).setHeader("Content-Type","application/json").setBody(body)
    private fun withGateway(block:suspend(GeneratedAccountDeletionGateway,MockWebServer)->Unit)=runBlocking {
        MockWebServer().use {server->
            val tokens=MemoryTokenStore().apply {replace(TokenPair("access","refresh"))}
            block(GeneratedAccountDeletionGateway(ApiSession(server.url("/"),tokens) {"7"}) {tokens.read()!=null},server)
        }
    }
    private fun MockWebServer.next()=requireNotNull(takeRequest(5,TimeUnit.SECONDS))
    private fun RecordedRequest.json()=Json.parseToJsonElement(body.readUtf8()).jsonObject

    @Test fun previewProjectsBlockersAndReauthWithoutMakerspaceHeader()=withGateway {gateway,server->
        server.enqueue(json("""{"deletable":false,"restorable":true,"restore_reason":"Restorable for 90 days.","blockers":[$BLOCKER,{"code":"future_code","makerspace_id":null,"makerspace_slug":null,"organization_id":3,"organization_url":"https://example.invalid/org/3","count":1,"detail":"Server says so."}],"reauth":{"required":true,"methods":["password","totp","google"]}}"""))
        val preview=gateway.preview()
        assertFalse(preview.deletable);assertTrue(preview.reauthRequired);assertEquals(listOf("password","totp","google"),preview.methods)
        assertEquals(AccountFixDestination.PAYMENTS,preview.blockers[0].fix);assertEquals(2,preview.blockers[0].count)
        assertEquals(AccountFixDestination.SUPPORT,preview.blockers[1].fix);assertEquals("Server says so.",preview.blockers[1].detail)
        val request=server.next();assertEquals("GET",request.method);assertEquals("/api/v1/auth/account/deletion",request.path)
        assertEquals("Bearer access",request.getHeader("Authorization"));assertNull(request.getHeader("X-Makerspace-Id"))
    }
    @Test fun passwordProofSendsPasswordAndCodeAndReportsExpiry()=withGateway {gateway,server->
        server.enqueue(json("""{"verified":true,"expires_in":600}"""))
        assertEquals(DeletionProof.Verified(600),gateway.password("secret","123456"))
        val request=server.next();assertEquals("/api/v1/auth/reauth",request.path)
        val body=request.json();assertEquals("secret",body["password"]!!.jsonPrimitive.content);assertEquals("123456",body["code"]!!.jsonPrimitive.content)
    }
    @Test fun wrongPasswordWithValidIdentityIsAVerificationFailureNotSessionLoss()=withGateway {gateway,server->
        server.enqueue(json("""{"detail":"Identity verification failed."}""",401))
        server.enqueue(json("""{"access":"a2","refresh":"r2","device_grant":{"id":"00000000-0000-0000-0000-000000000001","platform":"android","app_id":"org.example","environment":"development","status":"active","attested_at":"2026-10-17T00:00:00Z","last_used_at":"2026-10-17T00:00:00Z","created_at":"2026-10-17T00:00:00Z"}}"""))
        server.enqueue(json("""{"detail":"Identity verification failed."}""",401))
        server.enqueue(json(ME))
        try {gateway.password("wrong",null);fail()}catch(f:ApiFailure){assertEquals("reauth_failed",f.code);assertEquals(0,f.status)}
        assertEquals("/api/v1/auth/reauth",server.next().path);assertEquals("/api/v1/auth/device/refresh",server.next().path)
        assertEquals("/api/v1/auth/reauth",server.next().path);assertEquals("/api/v1/auth/me",server.next().path)
    }
    @Test fun phoneAndSocialProofsUseServerTokensAndFactors()=withGateway {gateway,server->
        server.enqueue(json("""{"sent":true,"token":"phone-token"}"""))
        val token=gateway.startPhone();assertEquals("PhoneProofToken(redacted)",token.toString())
        assertEquals("/api/v1/auth/reauth/phone/start",server.next().path)
        server.enqueue(json("""{"verified":true,"expires_in":300}"""))
        assertEquals(DeletionProof.Verified(300),gateway.confirmPhone(token,"654321"))
        val confirm=server.next().json();assertEquals("phone-token",confirm["token"]!!.jsonPrimitive.content);assertEquals("654321",confirm["code"]!!.jsonPrimitive.content)
        server.enqueue(json("""{"authorization_url":"https://accounts.example/auth","state":"s1","nonce":"n1","expires_in":300}"""))
        val intent=gateway.startSocial("google")
        assertEquals("google",server.next().json()["provider"]!!.jsonPrimitive.content)
        server.enqueue(json("""{"verified":false,"expires_in":0,"mfa_required":true,"factor_required":"totp","factor_token":"factor-1"}"""))
        assertEquals(DeletionProof.Factor("factor-1","totp"),gateway.completeSocial(intent,"id-token"))
        val complete=server.next().json()
        assertEquals("s1",complete["state"]!!.jsonPrimitive.content);assertEquals("n1",complete["nonce"]!!.jsonPrimitive.content);assertEquals("id-token",complete["id_token"]!!.jsonPrimitive.content)
        server.enqueue(json("""{"verified":true,"expires_in":600}"""))
        assertEquals(DeletionProof.Verified(600),gateway.factor("factor-1","111111"))
        assertEquals("/api/v1/auth/reauth/social/factor",server.next().path)
    }
    @Test fun deletionSendsTypedConfirmationAndDecodesConflicts()=withGateway {gateway,server->
        server.enqueue(json("""{"deleted_at":"2026-10-18T00:00:00Z","purge_after":"2027-01-16T00:00:00Z"}""",202))
        val deleted=gateway.delete() as DeletionOutcome.Deleted
        assertTrue(deleted.purgeAfter.startsWith("2027-01-16"))
        val request=server.next();assertEquals("POST",request.method);assertEquals(buildJsonObject {put("confirm","DELETE")},request.json())
        server.enqueue(json("""{"blockers":[$BLOCKER]}""",409))
        assertEquals("unpaid_charges",(gateway.delete() as DeletionOutcome.Blocked).blockers.single().code);server.next()
        server.enqueue(json("""{"blockers":[$BLOCKER]}""",403))
        assertTrue(gateway.delete() is DeletionOutcome.Blocked);server.next()
        server.enqueue(json("""{"detail":"Verify again.","code":"recent_reauth_required"}""",403))
        try {gateway.delete();fail()}catch(f:ApiFailure){assertEquals("recent_reauth_required",f.code)};server.next()
        server.enqueue(json("""{"detail":"Try again.","code":"deletion_retry"}""",503))
        try {gateway.delete();fail()}catch(f:ApiFailure){assertEquals(503,f.status)};server.next()
    }
}

private class FakeDeletion:AccountDeletionGateway {
    var previews=ArrayDeque<DeletionPreview>()
    var passwordResult:DeletionProof=DeletionProof.Verified(600)
    var deleteResult:DeletionOutcome=DeletionOutcome.Deleted("2026-10-18T00:00:00Z","2027-01-16T00:00:00Z")
    var deleteFailure:ApiFailure?=null
    var passwords=0;var deletes=0
    override suspend fun preview()=previews.removeFirstOrNull()?:previews.lastOrNull()?:ready()
    override suspend fun password(password:String,code:String?):DeletionProof {passwords++;if(password=="wrong")throw ApiFailure(0,"x","reauth_failed",null);return passwordResult}
    override suspend fun startPhone()=PhoneProofToken("t")
    override suspend fun confirmPhone(token:PhoneProofToken,code:String)=DeletionProof.Verified(600)
    override suspend fun startSocial(provider:String)=SocialProofIntent(provider,"s","n")
    override suspend fun completeSocial(intent:SocialProofIntent,idToken:String):DeletionProof {assertEquals("token-for-n",idToken);return DeletionProof.Factor("f","totp")}
    override suspend fun factor(token:String,code:String)=DeletionProof.Verified(600)
    override suspend fun delete():DeletionOutcome {deletes++;deleteFailure?.let {deleteFailure=null;throw it};return deleteResult}
    companion object {
        fun ready(methods:List<String> =listOf("password"),required:Boolean=true)=DeletionPreview(true,true,"",emptyList(),required,methods)
        val blocked=DeletionPreview(false,false,"",listOf(DeletionBlocker("active_loans",7,"workshop",null,1,"Return hardware.")),true,listOf("password"))
    }
}

class AccountDeletionStateTest {
    @Test fun blockersStopBeforeReauthentication()=runTest {
        val api=FakeDeletion().apply {previews.add(FakeDeletion.blocked)}
        val holder=AccountDeletionStateHolder(api);holder.load()
        assertEquals(DeletionStage.BLOCKERS,holder.state.value.stage);assertEquals(AccountFixDestination.ACTIVITY,holder.state.value.preview!!.blockers.single().fix)
        holder.verifyPassword("secret","");assertEquals(0,api.passwords)
    }
    @Test fun passwordProofThenTypedConfirmationDeletes()=runTest {
        var deleted:DeletionOutcome.Deleted?=null
        val api=FakeDeletion().apply {previews.add(FakeDeletion.ready(listOf("password","totp")))}
        val holder=AccountDeletionStateHolder(api,onDeleted={deleted=it});holder.load()
        assertEquals(DeletionStage.REAUTHENTICATE,holder.state.value.stage)
        holder.verifyPassword("secret","12");assertEquals(DeletionMessage.CODE_REQUIRED,holder.state.value.message);assertEquals(0,api.passwords)
        holder.verifyPassword("wrong","123456");assertEquals(DeletionMessage.PROOF_FAILED,holder.state.value.message)
        api.previews.add(FakeDeletion.ready(listOf("password","totp"),required=false))
        holder.verifyPassword("secret","123456");assertEquals(DeletionStage.CONFIRM,holder.state.value.stage)
        holder.confirm("delete");assertEquals(DeletionMessage.CONFIRM_INVALID,holder.state.value.message);assertEquals(0,api.deletes)
        holder.confirm("DELETE");assertEquals(DeletionStage.FINISHED,holder.state.value.stage);assertNotNull(deleted)
    }
    @Test fun conflictReturnsToFreshBlockersAndExpiredProofReauthenticates()=runTest {
        var clock=0L
        val api=FakeDeletion().apply {previews.add(FakeDeletion.ready());deleteResult=DeletionOutcome.Blocked(FakeDeletion.blocked.blockers)}
        val holder=AccountDeletionStateHolder(api,now={clock});holder.load()
        api.previews.add(FakeDeletion.ready(required=false))
        holder.verifyPassword("secret","");holder.confirm("DELETE")
        assertEquals(DeletionStage.BLOCKERS,holder.state.value.stage);assertEquals(DeletionMessage.CONFLICT,holder.state.value.message)
        api.previews.add(FakeDeletion.ready());holder.load()
        api.previews.add(FakeDeletion.ready(required=false));holder.verifyPassword("secret","")
        clock+=601_000;holder.confirm("DELETE")
        assertEquals(DeletionStage.REAUTHENTICATE,holder.state.value.stage);assertEquals(DeletionMessage.PROOF_EXPIRED,holder.state.value.message);assertEquals(1,api.deletes)
        api.previews.add(FakeDeletion.ready(required=false));holder.verifyPassword("secret","")
        api.deleteFailure=ApiFailure(403,"Verify again.","recent_reauth_required",null);holder.confirm("DELETE")
        assertEquals(DeletionStage.REAUTHENTICATE,holder.state.value.stage)
    }
    @Test fun socialProofCanRequireASecondFactor()=runTest {
        val api=FakeDeletion().apply {previews.add(FakeDeletion.ready(listOf("google")))}
        val holder=AccountDeletionStateHolder(api);holder.load()
        assertEquals(listOf("google"),holder.state.value.socialProviders)
        holder.verifySocial("google") {nonce->"token-for-$nonce"}
        assertEquals(DeletionStage.FACTOR,holder.state.value.stage);assertEquals("totp",holder.state.value.factorKind)
        api.previews.add(FakeDeletion.ready(listOf("google"),required=false))
        holder.verifyFactor("123456");assertEquals(DeletionStage.CONFIRM,holder.state.value.stage)
    }
    @Test fun throttlingBlocksFurtherAttemptsUntilRetryAfter()=runTest {
        var clock=0L
        val api=FakeDeletion().apply {previews.add(FakeDeletion.ready(required=false));deleteFailure=ApiFailure(429,"Wait.","throttled",30)}
        val holder=AccountDeletionStateHolder(api,now={clock});holder.load()
        holder.confirm("DELETE");assertEquals(30_000L,holder.state.value.retryAtMillis)
        holder.confirm("DELETE");assertEquals(1,api.deletes)
        clock=30_000;holder.confirm("DELETE");assertEquals(2,api.deletes)
    }
}
