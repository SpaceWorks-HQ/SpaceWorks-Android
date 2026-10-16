package org.spaceworks.app.auth

import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.copy.CoreStrings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*

class DemoAuthFlow(private val auth:AuthStateHolder,private val now:()->Long=System::currentTimeMillis,private val text:MobileText=MobileText.ENGLISH):PreviewAuthController {
    constructor(auth:AuthStateHolder,now:()->Long):this(auth,now,MobileText.ENGLISH)
    private val mutable=MutableStateFlow(PreviewAuthState())
    override val state=mutable.asStateFlow()
    override fun open(){if(!state.value.busy)mutable.value=state.value.copy(page=PreviewAuthPage.METHODS,error=null,notice=null)}
    override fun choose(provider:String){if(!state.value.busy && provider in listOf("Google","Apple","Makerspace SSO","Phone"))mutable.value=state.value.copy(page=if(provider=="Phone")PreviewAuthPage.PHONE else PreviewAuthPage.PROVIDER,provider=provider,error=null,notice=null)}
    override fun country(value:String){if(!state.value.busy && value in listOf("+91","+1","+44"))mutable.value=state.value.copy(country=value,error=null)}
    override fun phone(value:String){if(!state.value.busy)mutable.value=state.value.copy(phone=value.filter {it in '0'..'9'}.take(15),error=null)}
    override fun requireMfa(value:Boolean){if(!state.value.busy)mutable.value=state.value.copy(requireMfa=value)}
    override fun cancel(){if(!state.value.busy)mutable.value=state.value.copy(page=null,error=null,notice=text(CoreStrings.CORE_SIGN_IN_CANCELLED))}
    override fun unavailable(){if(!state.value.busy)mutable.value=state.value.copy(error=text(CoreStrings.CORE_THIS_SIGN_IN_OPTION_ISN_T_AVAILABLE_ON_THIS_DEVICE))}
    override fun expire(){if(!state.value.busy)mutable.value=state.value.copy(expiresAt=now(),error=text(CoreStrings.CORE_THIS_CODE_HAS_EXPIRED_REQUEST_ANOTHER_CODE))}
    override fun updateRequired(){if(!state.value.busy)mutable.value=state.value.copy(page=PreviewAuthPage.UPDATE_REQUIRED,error=null)}
    override fun sessionEnded(){if(!state.value.busy)mutable.value=state.value.copy(page=PreviewAuthPage.SESSION_ENDED,error=null)}
    override fun sendCode(){
        if(state.value.busy || now()<state.value.resendAt)return
        if(!Regex("\\+[1-9][0-9]{7,14}").matches(state.value.country+state.value.phone)) {
            mutable.value=state.value.copy(error=text(CoreStrings.CORE_ENTER_A_VALID_PHONE_NUMBER));return
        }
        mutable.value=state.value.copy(page=PreviewAuthPage.CODE,error=null,notice=text(CoreStrings.CORE_SIMULATED_CODE_SENT_NO_SMS_IS_SENT),resendAt=now()+60000,expiresAt=now()+300000)
    }
    override suspend fun continueProvider(){if(state.value.page==PreviewAuthPage.PROVIDER)complete(if(state.value.requireMfa)"mfa.demo" else "provider.demo")}
    override suspend fun verify(code:String) {
        if(state.value.busy || state.value.page!=PreviewAuthPage.CODE)return
        if(now()>=state.value.expiresAt){mutable.value=state.value.copy(error=text(CoreStrings.CORE_THIS_CODE_HAS_EXPIRED_REQUEST_ANOTHER_CODE));return}
        if(code!="123456"){mutable.value=state.value.copy(error=text(CoreStrings.CORE_THAT_CODE_DIDN_T_WORK_CHECK_IT_AND_TRY_AGAIN));return}
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
