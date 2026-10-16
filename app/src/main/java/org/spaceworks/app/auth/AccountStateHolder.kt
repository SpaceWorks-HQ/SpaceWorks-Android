package org.spaceworks.app.auth

import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.MobileCopyException
import org.spaceworks.app.platform.failure
import org.spaceworks.app.copy.CoreStrings
import org.spaceworks.app.copy.apiFailure

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.spaceworks.app.network.ApiFailure

enum class AccountRoute { SIGN_UP, SIGNUP_ACK, FORGOT_PASSWORD, RESET_PASSWORD, RESET_SUCCESS, RECOVERY_UNAVAILABLE, VERIFY_EMAIL }
data class AccountState(val route:AccountRoute?=null,val email:String="",val busy:Boolean=false,
    val error:String?=null,val notice:String?=null,val retryAtMillis:Long=0,val resendAtMillis:Long=0)
class AccountStateHolder(private val api:AuthGateway,private val signupAllowed:()->Boolean,
    private val onVerified:suspend()->Unit={},private val now:()->Long=System::currentTimeMillis,private val text:MobileText=MobileText.ENGLISH) {
    constructor(api:AuthGateway,signupAllowed:()->Boolean,onVerified:suspend()->Unit={},now:()->Long)
        :this(api,signupAllowed,onVerified,now,MobileText.ENGLISH)

    private val mutable=MutableStateFlow(AccountState())
    val state=mutable.asStateFlow()
    private val mutex=Mutex()
    fun open(route:AccountRoute,email:String="") {if(!state.value.busy)mutable.value=state.value.copy(route=route,email=email,error=null,notice=null)}
    fun back(){if(!state.value.busy)mutable.value=state.value.copy(route=null,error=null,notice=null)}
    private fun email(value:String):String=value.trim().also {if(!it.contains('@') || it.contains(' '))throw AuthMessage(text(CoreStrings.CORE_ENTER_YOUR_EMAIL_ADDRESS))}
    suspend fun signUp(name:String,address:String,password:String,confirmation:String,phone:String)=run {
        if(!signupAllowed())throw AuthMessage(text(CoreStrings.CORE_ACCOUNT_REGISTRATION_IS_UNAVAILABLE_ON_THIS_SERVER))
        if(name.trim().isBlank())throw AuthMessage(text(CoreStrings.CORE_ENTER_YOUR_NAME))
        if(password.length<8)throw AuthMessage(text(CoreStrings.CORE_USE_AT_LEAST_8_CHARACTERS))
        if(password!=confirmation)throw AuthMessage(text(CoreStrings.CORE_PASSWORDS_DON_T_MATCH))
        val normalized=email(address);api.signUp(name.trim(),normalized,password,phone.trim())
        mutable.value=state.value.copy(route=AccountRoute.SIGNUP_ACK,email=normalized,notice=null)
    }
    suspend fun forgot(address:String,resend:Boolean=false)=run {
        if(resend && now()<state.value.resendAtMillis)return@run
        val normalized=email(address);api.forgotPassword(normalized)
        mutable.value=state.value.copy(route=AccountRoute.RESET_PASSWORD,email=normalized,resendAtMillis=now()+60000,
            notice=text(CoreStrings.CORE_IF_THE_DETAILS_ARE_VALID_A_VERIFICATION_EMAIL_HAS_BEEN_SENT))
    }
    suspend fun reset(code:String,password:String,confirmation:String)=run(reset=true) {
        if(!Regex("[0-9]{6}").matches(code))throw AuthMessage(text(CoreStrings.CORE_ENTER_THE_SIX_DIGIT_VERIFICATION_CODE))
        if(password.length<8)throw AuthMessage(text(CoreStrings.CORE_USE_AT_LEAST_8_CHARACTERS))
        if(password!=confirmation)throw AuthMessage(text(CoreStrings.CORE_PASSWORDS_DON_T_MATCH))
        api.resetPassword(state.value.email,code,password)
        mutable.value=state.value.copy(route=AccountRoute.RESET_SUCCESS,notice=null)
    }
    suspend fun verifyEmail(code:String)=run {
        if(!Regex("[0-9]{6}").matches(code))throw AuthMessage(text(CoreStrings.CORE_ENTER_THE_SIX_DIGIT_VERIFICATION_CODE))
        api.confirmEmail(code);onVerified()
        mutable.value=state.value.copy(route=null,notice=text(CoreStrings.CORE_YOUR_EMAIL_IS_VERIFIED))
    }
    suspend fun resendVerification()=run {
        if(now()<state.value.resendAtMillis)return@run
        api.resendEmail()
        mutable.value=state.value.copy(resendAtMillis=now()+60000,notice=text(CoreStrings.CORE_IF_THE_DETAILS_ARE_VALID_A_VERIFICATION_EMAIL_HAS_BEEN_SENT))
    }
    private suspend fun run(reset:Boolean=false,block:suspend()->Unit) {
        if(!mutex.tryLock())return
        try {
            if(now()<state.value.retryAtMillis)return
            mutable.value=state.value.copy(busy=true,error=null,notice=null);block()
        }catch(cancelled:CancellationException){throw cancelled}
        catch(f:Exception){
            val deadline=if(f is ApiFailure && f.status==429)now()+(f.retryAfterSeconds?:60)*1000L else state.value.retryAtMillis
            val message=when {f is MobileCopyException->text.failure(f);f is AuthMessage->f.message;reset && f is ApiFailure->text(CoreStrings.CORE_INVALID_OR_EXPIRED_VERIFICATION_CODE);f is ApiFailure->text.apiFailure(f);else->text(CoreStrings.CORE_COULDN_T_CONNECT_TRY_AGAIN)}
            mutable.value=if(f is ApiFailure && f.code=="recovery_unavailable")state.value.copy(route=AccountRoute.RECOVERY_UNAVAILABLE,error=null,retryAtMillis=deadline)
                else state.value.copy(error=message,retryAtMillis=deadline)
        }finally {mutable.value=state.value.copy(busy=false);mutex.unlock()}
    }
}
