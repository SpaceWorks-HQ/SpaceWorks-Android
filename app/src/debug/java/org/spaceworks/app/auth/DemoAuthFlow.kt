package org.spaceworks.app.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*

class DemoAuthFlow(private val auth:AuthStateHolder,private val now:()->Long=System::currentTimeMillis):PreviewAuthController {
    private val mutable=MutableStateFlow(PreviewAuthState())
    override val state=mutable.asStateFlow()
    override fun open(){if(!state.value.busy)mutable.value=state.value.copy(page=PreviewAuthPage.METHODS,error=null,notice=null)}
    override fun choose(provider:String){if(!state.value.busy && provider in listOf("Google","Apple","Makerspace SSO","Phone"))mutable.value=state.value.copy(page=if(provider=="Phone")PreviewAuthPage.PHONE else PreviewAuthPage.PROVIDER,provider=provider,error=null,notice=null)}
    override fun country(value:String){if(!state.value.busy && value in listOf("+91","+1","+44"))mutable.value=state.value.copy(country=value,error=null)}
    override fun phone(value:String){if(!state.value.busy)mutable.value=state.value.copy(phone=value.filter {it in '0'..'9'}.take(15),error=null)}
    override fun requireMfa(value:Boolean){if(!state.value.busy)mutable.value=state.value.copy(requireMfa=value)}
    override fun cancel(){if(!state.value.busy)mutable.value=state.value.copy(page=null,error=null,notice="Sign-in cancelled.")}
    override fun unavailable(){if(!state.value.busy)mutable.value=state.value.copy(error="This sign-in option isn't available on this device.")}
    override fun expire(){if(!state.value.busy)mutable.value=state.value.copy(expiresAt=now(),error="This code has expired. Request another code.")}
    override fun updateRequired(){if(!state.value.busy)mutable.value=state.value.copy(page=PreviewAuthPage.UPDATE_REQUIRED,error=null)}
    override fun sessionEnded(){if(!state.value.busy)mutable.value=state.value.copy(page=PreviewAuthPage.SESSION_ENDED,error=null)}
    override fun sendCode(){
        if(state.value.busy || now()<state.value.resendAt)return
        if(!Regex("\\+[1-9][0-9]{7,14}").matches(state.value.country+state.value.phone)) {
            mutable.value=state.value.copy(error="Enter a valid phone number.");return
        }
        mutable.value=state.value.copy(page=PreviewAuthPage.CODE,error=null,notice="Simulated code sent. No SMS is sent.",resendAt=now()+60000,expiresAt=now()+300000)
    }
    override suspend fun continueProvider(){if(state.value.page==PreviewAuthPage.PROVIDER)complete(if(state.value.requireMfa)"mfa.demo" else "provider.demo")}
    override suspend fun verify(code:String) {
        if(state.value.busy || state.value.page!=PreviewAuthPage.CODE)return
        if(now()>=state.value.expiresAt){mutable.value=state.value.copy(error="This code has expired. Request another code.");return}
        if(code!="123456"){mutable.value=state.value.copy(error="That code didn't work. Check it and try again.");return}
        // Phone preview always creates the member fixture; it never grants staff authority.
        complete("phone.demo")
    }
    private suspend fun complete(username:String) {
        if(state.value.busy)return
        mutable.value=state.value.copy(busy=true,error=null)
        try {
            auth.signIn(username,"demo-password")
            mutable.value=state.value.copy(page=if(auth.state.value.user!=null || auth.state.value.mfa!=null)null else state.value.page,error=auth.state.value.error)
        }catch(cancelled:CancellationException){throw cancelled}
        finally {mutable.value=state.value.copy(busy=false)}
    }
}
