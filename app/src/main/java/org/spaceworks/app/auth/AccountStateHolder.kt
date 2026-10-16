package org.spaceworks.app.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.spaceworks.app.network.ApiFailure

enum class AccountRoute { SIGN_UP, SIGNUP_ACK, FORGOT_PASSWORD, RESET_PASSWORD, RESET_SUCCESS, RECOVERY_UNAVAILABLE, VERIFY_EMAIL }
data class AccountState(val route:AccountRoute?=null,val email:String="",val busy:Boolean=false,
    val error:String?=null,val notice:String?=null,val retryAtMillis:Long=0,val resendAtMillis:Long=0)
class AccountStateHolder(private val api:AuthGateway,private val signupAllowed:()->Boolean,
    private val onVerified:suspend()->Unit={},private val now:()->Long=System::currentTimeMillis) {
    private val mutable=MutableStateFlow(AccountState())
    val state=mutable.asStateFlow()
    private val mutex=Mutex()
    fun open(route:AccountRoute,email:String="") {if(!state.value.busy)mutable.value=state.value.copy(route=route,email=email,error=null,notice=null)}
    fun back(){if(!state.value.busy)mutable.value=state.value.copy(route=null,error=null,notice=null)}
    private fun email(value:String):String=value.trim().also {if(!it.contains('@') || it.contains(' '))throw AuthMessage("Enter your email address.")}
    suspend fun signUp(name:String,address:String,password:String,confirmation:String,phone:String)=run {
        if(!signupAllowed())throw AuthMessage("Account registration is unavailable on this server.")
        if(name.trim().isBlank())throw AuthMessage("Enter your name.")
        if(password.length<8)throw AuthMessage("Use at least 8 characters.")
        if(password!=confirmation)throw AuthMessage("Passwords don't match.")
        val normalized=email(address);api.signUp(name.trim(),normalized,password,phone.trim())
        mutable.value=state.value.copy(route=AccountRoute.SIGNUP_ACK,email=normalized,notice=null)
    }
    suspend fun forgot(address:String,resend:Boolean=false)=run {
        if(resend && now()<state.value.resendAtMillis)return@run
        val normalized=email(address);api.forgotPassword(normalized)
        mutable.value=state.value.copy(route=AccountRoute.RESET_PASSWORD,email=normalized,resendAtMillis=now()+60000,
            notice="If the details are valid, a verification email has been sent.")
    }
    suspend fun reset(code:String,password:String,confirmation:String)=run(reset=true) {
        if(!Regex("[0-9]{6}").matches(code))throw AuthMessage("Enter the six-digit verification code.")
        if(password.length<8)throw AuthMessage("Use at least 8 characters.")
        if(password!=confirmation)throw AuthMessage("Passwords don't match.")
        api.resetPassword(state.value.email,code,password)
        mutable.value=state.value.copy(route=AccountRoute.RESET_SUCCESS,notice=null)
    }
    suspend fun verifyEmail(code:String)=run {
        if(!Regex("[0-9]{6}").matches(code))throw AuthMessage("Enter the six-digit verification code.")
        api.confirmEmail(code);onVerified()
        mutable.value=state.value.copy(route=null,notice="Your email is verified.")
    }
    suspend fun resendVerification()=run {
        if(now()<state.value.resendAtMillis)return@run
        api.resendEmail()
        mutable.value=state.value.copy(resendAtMillis=now()+60000,notice="If the details are valid, a verification email has been sent.")
    }
    private suspend fun run(reset:Boolean=false,block:suspend()->Unit) {
        if(!mutex.tryLock())return
        try {
            if(now()<state.value.retryAtMillis)return
            mutable.value=state.value.copy(busy=true,error=null,notice=null);block()
        }catch(cancelled:CancellationException){throw cancelled}
        catch(f:Exception){
            val deadline=if(f is ApiFailure && f.status==429)now()+(f.retryAfterSeconds?:60)*1000L else state.value.retryAtMillis
            val message=when {f is AuthMessage->f.message;reset && f is ApiFailure->"Invalid or expired verification code.";f is ApiFailure->f.detail;else->"Couldn't connect. Try again."}
            mutable.value=if(f is ApiFailure && f.code=="recovery_unavailable")state.value.copy(route=AccountRoute.RECOVERY_UNAVAILABLE,error=null,retryAtMillis=deadline)
                else state.value.copy(error=message,retryAtMillis=deadline)
        }finally {mutable.value=state.value.copy(busy=false);mutex.unlock()}
    }
}
