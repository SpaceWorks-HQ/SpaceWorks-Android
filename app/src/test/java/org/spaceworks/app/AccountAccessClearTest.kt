package org.spaceworks.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.auth.*
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.generated.model.*

@OptIn(ExperimentalCoroutinesApi::class)
class AccountAccessClearTest {
    private class API:AuthGateway {
        val calls=mutableListOf<String>()
        var response:suspend(String)->String={"ACK"}
        private suspend fun reply(operation:String):String{calls+=operation;return response(operation)}
        override suspend fun config()=PublicConfig(true,100)
        override suspend fun login(username:String,password:String):DeviceLoginResult=error("Unused")
        override suspend fun enroll(challenge:String):MFAEnrollment=error("Unused")
        override suspend fun verify(challenge:String,code:String,enrollment:Boolean):MFACompleted=error("Unused")
        override suspend fun me():AuthUserPayload=error("Unused")
        override suspend fun logout(){}
        override suspend fun signUp(name:String,email:String,password:String,phone:String)=reply("signup")
        override suspend fun forgotPassword(email:String)=reply("forgot")
        override suspend fun resetPassword(email:String,code:String,replacement:String)=reply("reset")
        override suspend fun confirmEmail(code:String)=reply("verify")
        override suspend fun resendEmail()=reply("resend")
    }
    private suspend fun start(holder:AccountStateHolder,operation:String){when(operation){
        "signup"->{holder.open(AccountRoute.SIGN_UP);holder.signUp("Old member","old@example.invalid","old-secret","old-secret","")}
        "forgot"->{holder.open(AccountRoute.FORGOT_PASSWORD);holder.forgot("old@example.invalid")}
        "reset"->{holder.open(AccountRoute.RESET_PASSWORD,"old@example.invalid");holder.reset("123456","new-secret","new-secret")}
        "resend"->{holder.open(AccountRoute.VERIFY_EMAIL,"old@example.invalid");holder.resendVerification()}
        else->error("Unknown test operation")}}

    @Test fun lateVerificationAfterClearNeverInvokesOldIdentityCallbackOrUnlocksNewRequest()=runTest {
        val api=API();val verify=CompletableDeferred<Unit>();val fresh=CompletableDeferred<Unit>();var callbacks=0
        api.response={op->if(op=="verify")verify.await()else fresh.await();"ACK"}
        val holder=AccountStateHolder(api,{true},{callbacks++});holder.open(AccountRoute.VERIFY_EMAIL,"old@example.invalid")
        val old=launch(start=CoroutineStart.UNDISPATCHED){holder.verifyEmail("123456")}
        holder.clear();assertEquals(AccountState(),holder.state.value)
        holder.open(AccountRoute.FORGOT_PASSWORD)
        val next=launch(start=CoroutineStart.UNDISPATCHED){holder.forgot("new@example.invalid")}
        assertEquals(listOf("verify","forgot"),api.calls);verify.complete(Unit);old.join()
        assertEquals(0,callbacks);assertTrue(holder.state.value.busy);assertNull(holder.state.value.notice)
        holder.forgot("duplicate@example.invalid");assertEquals(2,api.calls.size)
        fresh.complete(Unit);next.join();assertEquals("new@example.invalid",holder.state.value.email)
        assertEquals(AccountRoute.RESET_PASSWORD,holder.state.value.route);assertFalse(holder.state.value.busy)
    }
    @Test fun clearDuringIdentityCallbackWaitSuppressesOldNoticeAndFinallyWhileFreshFlowRuns()=runTest {
        val api=API();val entered=CompletableDeferred<Unit>();val callback=CompletableDeferred<Unit>();val fresh=CompletableDeferred<Unit>()
        val holder=AccountStateHolder(api,{true},{entered.complete(Unit);callback.await()})
        holder.open(AccountRoute.VERIFY_EMAIL);val old=launch{holder.verifyEmail("123456")};entered.await()
        holder.clear();api.response={fresh.await();"ACK"};holder.open(AccountRoute.SIGN_UP)
        val next=launch(start=CoroutineStart.UNDISPATCHED){holder.signUp("New","new@example.invalid","new-secret","new-secret","")}
        callback.complete(Unit);old.join();assertEquals(AccountRoute.SIGN_UP,holder.state.value.route)
        assertTrue(holder.state.value.busy);assertNull(holder.state.value.notice)
        fresh.complete(Unit);next.join();assertEquals(AccountRoute.SIGNUP_ACK,holder.state.value.route)
    }
    @Test fun stalePublicAcknowledgementsCannotReopenRoutesOrRestoreOldEmailOrResendMetadata()=runTest {
        for(operation in listOf("signup","forgot","reset","resend")){
            val api=API();val release=CompletableDeferred<Unit>();api.response={release.await();"ACK"}
            val holder=AccountStateHolder(api,{true});val old=launch(start=CoroutineStart.UNDISPATCHED){start(holder,operation)}
            holder.clear();api.response={"ACK"};holder.open(AccountRoute.SIGN_UP)
            holder.signUp("New","new@example.invalid","new-secret","new-secret","")
            val expected=holder.state.value;release.complete(Unit);old.join()
            assertEquals(operation,expected,holder.state.value);assertEquals(0L,holder.state.value.resendAtMillis)
        }
    }
    @Test fun stale429UnavailableResetRejectionAndOfflineDoNotClobberNewFlow()=runTest {
        val failures=listOf(ApiFailure(429,"Old throttle",null,90),ApiFailure(503,"Old unavailable","recovery_unavailable",null),
            ApiFailure(400,"Old policy",null,null),java.io.IOException("Old offline"))
        for(operation in listOf("signup","forgot","reset","resend"))for(failure in failures){
            val api=API();val release=CompletableDeferred<Unit>();api.response={release.await();throw failure}
            val holder=AccountStateHolder(api,{true});val old=launch(start=CoroutineStart.UNDISPATCHED){start(holder,operation)}
            holder.clear();api.response={"ACK"};holder.open(AccountRoute.FORGOT_PASSWORD);holder.forgot("new@example.invalid")
            val expected=holder.state.value;release.complete(Unit);old.join()
            assertEquals("$operation ${failure.javaClass.simpleName}",expected,holder.state.value)
            assertEquals(0L,holder.state.value.retryAtMillis)
        }
    }
    @Test fun clearRetainsVisibleServerRetryDeadlineButErasesAccountResendMetadata()=runTest {
        var now=1000L;val api=API();val holder=AccountStateHolder(api,{true},now={now})
        holder.open(AccountRoute.VERIFY_EMAIL,"old@example.invalid");holder.resendVerification()
        assertEquals(61000L,holder.state.value.resendAtMillis)
        api.response={throw ApiFailure(429,"Wait",null,90)};holder.forgot("old@example.invalid")
        assertEquals(91000L,holder.state.value.retryAtMillis);holder.clear()
        assertEquals(AccountState(retryAtMillis=91000L),holder.state.value)
        api.response={"ACK"};holder.open(AccountRoute.FORGOT_PASSWORD);holder.forgot("new@example.invalid")
        assertEquals(2,api.calls.size);assertEquals(91000L,holder.state.value.retryAtMillis)
        now=91001L;holder.forgot("new@example.invalid");assertEquals(3,api.calls.size)
        assertEquals("new@example.invalid",holder.state.value.email)
    }
    @Test fun clearedResendCanStartNewGenerationBeforeOldResponseAndOldCancellationCannotClearBusy()=runTest {
        val api=API();val oldGate=CompletableDeferred<Unit>();val freshGate=CompletableDeferred<Unit>();var first=true
        api.response={if(first){first=false;oldGate.await()}else freshGate.await();"ACK"}
        val holder=AccountStateHolder(api,{true});holder.open(AccountRoute.VERIFY_EMAIL,"old@example.invalid")
        val old=launch(start=CoroutineStart.UNDISPATCHED){holder.resendVerification()};holder.clear()
        holder.open(AccountRoute.VERIFY_EMAIL,"new@example.invalid")
        val fresh=launch(start=CoroutineStart.UNDISPATCHED){holder.resendVerification()}
        assertEquals(2,api.calls.size);old.cancelAndJoin();assertTrue(holder.state.value.busy)
        freshGate.complete(Unit);fresh.join();assertFalse(holder.state.value.busy)
        assertEquals("new@example.invalid",holder.state.value.email);assertTrue(holder.state.value.resendAtMillis>0)
    }
    @Test fun cancelledNonCooperativeApiCannotPublishVerificationOrInvokeCallback()=runTest {
        val api=API();val release=CompletableDeferred<Unit>();var callbacks=0
        api.response={withContext(NonCancellable){release.await()};"ACK"}
        val holder=AccountStateHolder(api,{true},{callbacks++});holder.open(AccountRoute.VERIFY_EMAIL)
        val pending=launch(start=CoroutineStart.UNDISPATCHED){holder.verifyEmail("123456")}
        pending.cancel();release.complete(Unit);pending.join()
        assertEquals(0,callbacks);assertEquals(AccountRoute.VERIFY_EMAIL,holder.state.value.route)
        assertNull(holder.state.value.notice);assertFalse(holder.state.value.busy)
    }
}
