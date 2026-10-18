package org.spaceworks.app.auth

import org.spaceworks.app.network.retryDeadlineMillis

import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.MobileCopyException
import org.spaceworks.app.platform.failure
import org.spaceworks.app.copy.CoreStrings
import org.spaceworks.app.copy.apiFailure

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.spaceworks.app.network.ApiFailure

enum class AccountRoute { SIGN_UP, SIGNUP_ACK, FORGOT_PASSWORD, RESET_PASSWORD, RESET_SUCCESS, RECOVERY_UNAVAILABLE, VERIFY_EMAIL, RESTORE_PHONE, RESTORE_CODE, RESTORE_SUCCESS }
data class AccountState(val route:AccountRoute?=null,val email:String="",val busy:Boolean=false,
    val error:String?=null,val notice:String?=null,val retryAtMillis:Long=0,val resendAtMillis:Long=0,val phone:String="")
class AccountStateHolder(private val api:AuthGateway,private val signupAllowed:()->Boolean,
    private val onVerified:suspend()->Unit={},private val now:()->Long=System::currentTimeMillis,private val text:MobileText=MobileText.ENGLISH) {
    constructor(api:AuthGateway,signupAllowed:()->Boolean,onVerified:suspend()->Unit={},now:()->Long)
        :this(api,signupAllowed,onVerified,now,MobileText.ENGLISH)

    private val mutable=MutableStateFlow(AccountState())
    val state=mutable.asStateFlow()
    private class FlowGeneration {val mutex=Mutex()}
    @Volatile private var generation=FlowGeneration()
    /** Clear private context synchronously, even while an older request/callback is suspended. */
    fun clear(){val retry=state.value.retryAtMillis;generation=FlowGeneration();restoreChallenge=null;mutable.value=AccountState(retryAtMillis=retry)}
    private var restoreChallenge:PhoneChallenge?=null
    /** Native restore of an archived phone-only account: code by SMS, then a new password. */
    suspend fun restoreStart(phone:String,resend:Boolean=false)=run {expected->
        if(resend && now()<state.value.resendAtMillis)return@run
        val number=phone.filter {it.isDigit() || it=='+'}
        if(!Regex("\\+[1-9]\\d{6,14}").matches(number))throw AuthMessage(text(CoreStrings.CORE_ENTER_YOUR_PHONE_NUMBER_WITH_ITS_COUNTRY_CODE))
        val challenge=api.phoneStart(number)
        if(!accept(expected))return@run
        restoreChallenge=challenge
        mutable.value=state.value.copy(route=AccountRoute.RESTORE_CODE,phone=number,resendAtMillis=now()+30000)
    }
    suspend fun restore(code:String,password:String,confirmation:String)=run {expected->
        if(!Regex("[0-9]{6}").matches(code))throw AuthMessage(text(CoreStrings.CORE_ENTER_THE_SIX_DIGIT_VERIFICATION_CODE))
        if(password.length<8)throw AuthMessage(text(CoreStrings.CORE_USE_AT_LEAST_8_CHARACTERS))
        if(password!=confirmation)throw AuthMessage(text(CoreStrings.CORE_PASSWORDS_DON_T_MATCH))
        val challenge=restoreChallenge?:throw AuthMessage(text(CoreStrings.CORE_YOUR_CODE_EXPIRED_SEND_A_NEW_CODE))
        val result=api.restorePhone(state.value.phone,code,password,challenge)
        if(!accept(expected))return@run
        restoreChallenge=null
        if(!result.restored)throw AuthMessage(result.detail.ifBlank {text(CoreStrings.CORE_INVALID_OR_EXPIRED_VERIFICATION_CODE)})
        mutable.value=state.value.copy(route=AccountRoute.RESTORE_SUCCESS,notice=result.detail.takeIf {it.isNotBlank()})
    }
    private fun current(expected:FlowGeneration)=generation===expected
    private suspend fun accept(expected:FlowGeneration):Boolean {currentCoroutineContext().ensureActive();return current(expected)}
    fun open(route:AccountRoute,email:String="") {if(!state.value.busy)mutable.value=state.value.copy(route=route,email=email,error=null,notice=null)}
    fun back(){if(!state.value.busy){restoreChallenge=null;mutable.value=state.value.copy(route=null,error=null,notice=null)}}
    private fun email(value:String):String=value.trim().also {if(!it.contains('@') || it.contains(' '))throw AuthMessage(text(CoreStrings.CORE_ENTER_YOUR_EMAIL_ADDRESS))}
    suspend fun signUp(name:String,address:String,password:String,confirmation:String,phone:String)=run {expected->
        if(!signupAllowed())throw AuthMessage(text(CoreStrings.CORE_ACCOUNT_REGISTRATION_IS_UNAVAILABLE_ON_THIS_SERVER))
        if(name.trim().isBlank())throw AuthMessage(text(CoreStrings.CORE_ENTER_YOUR_NAME))
        if(password.length<8)throw AuthMessage(text(CoreStrings.CORE_USE_AT_LEAST_8_CHARACTERS))
        if(password!=confirmation)throw AuthMessage(text(CoreStrings.CORE_PASSWORDS_DON_T_MATCH))
        val normalized=email(address);api.signUp(name.trim(),normalized,password,phone.trim())
        if(!accept(expected))return@run
        mutable.value=state.value.copy(route=AccountRoute.SIGNUP_ACK,email=normalized,notice=null)
    }
    suspend fun forgot(address:String,resend:Boolean=false)=run {expected->
        if(resend && now()<state.value.resendAtMillis)return@run
        val normalized=email(address);api.forgotPassword(normalized)
        if(!accept(expected))return@run
        mutable.value=state.value.copy(route=AccountRoute.RESET_PASSWORD,email=normalized,resendAtMillis=now()+60000,
            notice=text(CoreStrings.CORE_IF_THE_DETAILS_ARE_VALID_A_VERIFICATION_EMAIL_HAS_BEEN_SENT))
    }
    suspend fun reset(code:String,password:String,confirmation:String)=run(reset=true) {expected->
        if(!Regex("[0-9]{6}").matches(code))throw AuthMessage(text(CoreStrings.CORE_ENTER_THE_SIX_DIGIT_VERIFICATION_CODE))
        if(password.length<8)throw AuthMessage(text(CoreStrings.CORE_USE_AT_LEAST_8_CHARACTERS))
        if(password!=confirmation)throw AuthMessage(text(CoreStrings.CORE_PASSWORDS_DON_T_MATCH))
        api.resetPassword(state.value.email,code,password)
        if(!accept(expected))return@run
        mutable.value=state.value.copy(route=AccountRoute.RESET_SUCCESS,notice=null)
    }
    suspend fun verifyEmail(code:String)=run {expected->
        if(!Regex("[0-9]{6}").matches(code))throw AuthMessage(text(CoreStrings.CORE_ENTER_THE_SIX_DIGIT_VERIFICATION_CODE))
        api.confirmEmail(code)
        if(!accept(expected))return@run
        onVerified()
        if(!accept(expected))return@run
        mutable.value=state.value.copy(route=null,notice=text(CoreStrings.CORE_YOUR_EMAIL_IS_VERIFIED))
    }
    suspend fun resendVerification()=run {expected->
        if(now()<state.value.resendAtMillis)return@run
        api.resendEmail()
        if(!accept(expected))return@run
        mutable.value=state.value.copy(resendAtMillis=now()+60000,notice=text(CoreStrings.CORE_IF_THE_DETAILS_ARE_VALID_A_VERIFICATION_EMAIL_HAS_BEEN_SENT))
    }
    private suspend fun run(reset:Boolean=false,block:suspend(FlowGeneration)->Unit) {
        val expected=generation;val mutex=expected.mutex
        if(!mutex.tryLock())return
        try {
            if(!current(expected))return
            if(now()<state.value.retryAtMillis)return
            mutable.value=state.value.copy(busy=true,error=null,notice=null);block(expected)
        }catch(cancelled:CancellationException){throw cancelled}
        catch(f:Exception){
            if(!current(expected))return
            val deadline=if(f is ApiFailure && f.status==429)maxOf(state.value.retryAtMillis,retryDeadlineMillis(now(),f.retryAfterSeconds?:60)) else state.value.retryAtMillis
            val message=when {f is MobileCopyException->text.failure(f);f is AuthMessage->f.message;reset && f is ApiFailure->text(CoreStrings.CORE_INVALID_OR_EXPIRED_VERIFICATION_CODE);f is ApiFailure->text.apiFailure(f);else->text(CoreStrings.CORE_COULDN_T_CONNECT_TRY_AGAIN)}
            mutable.value=if(f is ApiFailure && f.code=="recovery_unavailable")state.value.copy(route=AccountRoute.RECOVERY_UNAVAILABLE,error=null,retryAtMillis=deadline)
                else state.value.copy(error=message,retryAtMillis=deadline)
        }finally {if(current(expected))mutable.value=state.value.copy(busy=false);mutex.unlock()}
    }
}
