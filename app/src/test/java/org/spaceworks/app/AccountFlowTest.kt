package org.spaceworks.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.auth.*
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.generated.model.*

private class AccountAPI:AuthGateway {
    var calls=0
    var failure:Exception?=null
    var gate:CompletableDeferred<Unit>?=null
    var signup:List<String>?=null
    override suspend fun config()=PublicConfig(true,100)
    override suspend fun login(username:String,password:String):DeviceLoginResult=error("Unused")
    override suspend fun enroll(challenge:String):MFAEnrollment=error("Unused")
    override suspend fun verify(challenge:String,code:String,enrollment:Boolean):MFACompleted=error("Unused")
    override suspend fun me():AuthUserPayload=error("Unused")
    override suspend fun logout(){}
    override suspend fun forgotPassword(email:String):String {calls++;gate?.await();failure?.let{throw it};return "Private account existence detail"}
    override suspend fun resetPassword(email:String,code:String,replacement:String):String {calls++;failure?.let{throw it};return "Updated"}
    override suspend fun signUp(name:String,email:String,password:String,phone:String):String {calls++;signup=listOf(name,email,password,phone);return "Created"}
    override suspend fun confirmEmail(code:String):String {calls++;return "Verified"}
    override suspend fun resendEmail():String {calls++;return "Sent"}
}
class AccountFlowTest {
    @Test fun registrationHonorsConfigAndNeverEstablishesASession()=runTest {
        val api=AccountAPI();var allowed=false;val holder=AccountStateHolder(api,{allowed})
        holder.open(AccountRoute.SIGN_UP);holder.signUp("Meera","member@example.invalid","new-secret","new-secret","")
        assertEquals(0,api.calls);allowed=true;holder.signUp(" Meera "," member@example.invalid ","new-secret","new-secret","")
        assertEquals(AccountRoute.SIGNUP_ACK,holder.state.value.route);assertEquals("member@example.invalid",holder.state.value.email)
        assertEquals(listOf("Meera","member@example.invalid","new-secret",""),api.signup)
        holder.back();assertNull(holder.state.value.route);assertEquals("member@example.invalid",holder.state.value.email)
    }
    @Test fun recoveryAcknowledgementIsUniformAndResendWaitsSixtySeconds()=runTest {
        var now=0L;val api=AccountAPI();val holder=AccountStateHolder(api,{true},now={now})
        holder.open(AccountRoute.FORGOT_PASSWORD);holder.forgot("member@example.invalid")
        assertEquals("If the details are valid, a verification email has been sent.",holder.state.value.notice)
        assertEquals(AccountRoute.RESET_PASSWORD,holder.state.value.route)
        holder.forgot("member@example.invalid",true);assertEquals(1,api.calls)
        now=60000;holder.forgot("member@example.invalid",true);assertEquals(2,api.calls)
    }
    @Test fun serverCooldownSurvivesNavigationAndEmailChanges()=runTest {
        var now=0L;val api=AccountAPI().apply {failure=ApiFailure(429,"Wait",null,90)};val holder=AccountStateHolder(api,{true},now={now})
        holder.open(AccountRoute.FORGOT_PASSWORD);holder.forgot("a@example.invalid")
        holder.back();holder.open(AccountRoute.FORGOT_PASSWORD);holder.forgot("b@example.invalid")
        assertEquals(1,api.calls);assertEquals(90000,holder.state.value.retryAtMillis)
        now=90001;api.failure=null;holder.forgot("b@example.invalid");assertEquals(2,api.calls)
    }
    @Test fun allResetServerRejectionsHaveIdenticalNonOracleCopy()=runTest {
        listOf(400,401,403,404,409,429,503).forEach {status->
            val api=AccountAPI().apply {failure=ApiFailure(status,"Secret password policy or account status",null,10)}
            val holder=AccountStateHolder(api,{true});holder.open(AccountRoute.RESET_PASSWORD,"a@example.invalid")
            holder.reset("123456","new-secret","new-secret")
            assertEquals("Invalid or expired verification code.",holder.state.value.error)
            assertEquals(AccountRoute.RESET_PASSWORD,holder.state.value.route)
        }
    }
    @Test fun localChecksNeverTransmitShortPasswordMismatchedConfirmationOrPartialCode()=runTest {
        val api=AccountAPI();val holder=AccountStateHolder(api,{true});holder.open(AccountRoute.RESET_PASSWORD,"a@example.invalid")
        holder.reset("123","new-secret","new-secret");holder.reset("123456","short","short");holder.reset("123456","new-secret","different")
        assertEquals(0,api.calls);assertEquals("Passwords don't match.",holder.state.value.error)
        holder.reset("123456","new-secret","new-secret");assertEquals(1,api.calls);assertEquals(AccountRoute.RESET_SUCCESS,holder.state.value.route)
    }
    @Test fun onlyKnownRecoveryUnavailableCodeEntersUnavailableScreen()=runTest {
        val api=AccountAPI().apply {failure=ApiFailure(503,"Offline service",null,null)};val holder=AccountStateHolder(api,{true})
        holder.open(AccountRoute.FORGOT_PASSWORD);holder.forgot("a@example.invalid")
        assertEquals(AccountRoute.FORGOT_PASSWORD,holder.state.value.route)
        api.failure=ApiFailure(503,"Unavailable","recovery_unavailable",null);holder.forgot("a@example.invalid")
        assertEquals(AccountRoute.RECOVERY_UNAVAILABLE,holder.state.value.route)
    }
    @Test fun emailVerificationReloadsAccountAndResendDoesNotInventExpiry()=runTest {
        var now=0L;var refreshed=0;val api=AccountAPI();val holder=AccountStateHolder(api,{true},{refreshed++},now={now})
        holder.open(AccountRoute.VERIFY_EMAIL);holder.resendVerification();holder.resendVerification();assertEquals(1,api.calls)
        holder.verifyEmail("123456");assertEquals(1,refreshed);assertNull(holder.state.value.route)
        assertEquals("Your email is verified.",holder.state.value.notice)
    }
    @Test fun duplicateAndCancelledRequestsNeverAcceptLateAcknowledgement()=runTest {
        val api=AccountAPI().apply {gate=CompletableDeferred()};val holder=AccountStateHolder(api,{true});holder.open(AccountRoute.FORGOT_PASSWORD)
        val request=launch(start=CoroutineStart.UNDISPATCHED){holder.forgot("a@example.invalid")}
        holder.back();holder.forgot("a@example.invalid");assertEquals(1,api.calls);assertTrue(holder.state.value.busy)
        request.cancelAndJoin();api.gate!!.complete(Unit)
        assertEquals(AccountRoute.FORGOT_PASSWORD,holder.state.value.route);assertFalse(holder.state.value.busy);assertNull(holder.state.value.notice)
    }
}
