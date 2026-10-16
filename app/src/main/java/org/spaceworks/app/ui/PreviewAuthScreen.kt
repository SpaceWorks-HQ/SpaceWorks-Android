package org.spaceworks.app.ui

import org.spaceworks.app.platform.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.auth.*

@Composable fun PreviewAuthScreen(controller:PreviewAuthController) {
    val copyText=rememberMobileText()

    val state by controller.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    var code by remember {mutableStateOf("")}
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    var countries by remember {mutableStateOf(false)}
    LaunchedEffect(state.resendAt){while(true){now=System.currentTimeMillis();kotlinx.coroutines.delay(500)}}
    BackHandler {if(!state.busy)controller.cancel()}
    val title=when(state.page){PreviewAuthPage.METHODS->copyText(UiStrings.MOBILE_PREVIEW_SIGN_IN_OPTIONS);PreviewAuthPage.PROVIDER->copyText(UiStrings.MOBILE_VALUE_SIGN_IN_PREVIEW,providerLabel(state.provider,copyText));PreviewAuthPage.PHONE->copyText(UiStrings.MOBILE_CONTINUE_WITH_PHONE);PreviewAuthPage.UPDATE_REQUIRED->copyText(UiStrings.MOBILE_UPDATE_REQUIRED);PreviewAuthPage.SESSION_ENDED->copyText(UiStrings.MOBILE_SIGN_IN_AGAIN);else->copyText(UiStrings.MOBILE_ENTER_YOUR_VERIFICATION_CODE)}
    AuthLayout(title,copyText(UiStrings.MOBILE_DEMO_ONLY_NO_PROVIDER_ACCOUNT_SMS_OR_LIVE_SERVER_IS_USED)) {
        when(state.page) {
            PreviewAuthPage.METHODS -> {
                listOf("Google","Apple","Makerspace SSO","Phone").forEach {provider->BrandButton(copyText(UiStrings.MOBILE_PREVIEW_VALUE,providerLabel(provider,copyText)),{controller.choose(provider)})}
                BrandTextButton(copyText(UiStrings.MOBILE_PREVIEW_UPDATE_REQUIRED),controller::updateRequired)
                BrandTextButton(copyText(UiStrings.MOBILE_PREVIEW_ENDED_SESSION),controller::sessionEnded)
            }
            PreviewAuthPage.UPDATE_REQUIRED -> {
                Text(copyText(UiStrings.MOBILE_UPDATE_SPACE_WORKS_TO_CONTINUE_USING_YOUR_MAKERSPACE))
                Text(copyText(UiStrings.MOBILE_PREVIEW_ONLY_THE_CURRENT_SERVER_CONTRACT_DOES_NOT_PUBLISH_A_MINI),style=MaterialTheme.typography.bodySmall)
                BrandButton(copyText(UiStrings.MOBILE_BACK_TO_PREVIEW_OPTIONS),controller::open)
            }
            PreviewAuthPage.SESSION_ENDED -> {
                Text(copyText(UiStrings.MOBILE_YOUR_SESSION_ENDED_SIGN_IN_AGAIN_TO_CONTINUE))
                BrandButton(copyText(UiStrings.MOBILE_BACK_TO_SIGN_IN),controller::cancel)
            }
            PreviewAuthPage.PROVIDER -> {
                Text(copyText(UiStrings.MOBILE_SAMPLE_ACCOUNT),style=MaterialTheme.typography.labelLarge)
                Text("Meera Nair",style=MaterialTheme.typography.titleLarge)
                Text("meera@example.invalid")
                Row(Modifier.fillMaxWidth().toggleable(state.requireMfa,enabled=!state.busy,
                    role=androidx.compose.ui.semantics.Role.Checkbox,onValueChange=controller::requireMfa),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(state.requireMfa,null,enabled=!state.busy)
                    Text(copyText(UiStrings.MOBILE_REQUIRE_EXTRA_SECURITY_IN_THIS_PREVIEW))
                }
                BrandButton(if(state.busy)copyText(UiStrings.MOBILE_SIGNING_IN) else copyText(UiStrings.MOBILE_CONTINUE_WITH_SAMPLE_ACCOUNT),{scope.launch {controller.continueProvider()}},!state.busy)
                BrandTextButton(copyText(UiStrings.MOBILE_SIMULATE_UNAVAILABLE),controller::unavailable,!state.busy)
            }
            PreviewAuthPage.PHONE -> {
                Box {
                    BrandTextButton(copyText(UiStrings.MOBILE_COUNTRY_CODE_VALUE,state.country),{countries=true},!state.busy)
                    DropdownMenu(countries,{countries=false}) {listOf(copyText(UiStrings.MOBILE_INDIA_91) to "+91",copyText(UiStrings.MOBILE_UNITED_STATES_1) to "+1",copyText(UiStrings.MOBILE_UNITED_KINGDOM_44) to "+44").forEach {(label,value)->DropdownMenuItem(text={Text(label)},onClick={controller.country(value);countries=false})}}
                }
                BrandField(state.phone,controller::phone,copyText(UiStrings.MOBILE_PHONE_NUMBER),Modifier.testTag("previewPhone"),!state.busy,role=FieldRole.PHONE,
                    onDone={if(!state.busy && now>=state.resendAt){focus.clearFocus();keyboard?.hide();controller.sendCode()}})
                Text(copyText(UiStrings.MOBILE_WE_LL_SEND_A_VERIFICATION_CODE_IN_THIS_DEMO_NO_SMS_IS_SENT))
                BrandButton(copyText(UiStrings.MOBILE_SEND_CODE),{focus.clearFocus();keyboard?.hide();controller.sendCode()},!state.busy && now>=state.resendAt)
                if(now<state.resendAt)Text(copyText(UiStrings.MOBILE_TRY_AGAIN_IN_VALUES,((state.resendAt-now+999)/1000).coerceAtLeast(0)))
            }
            PreviewAuthPage.CODE -> {
                Text(copyText(UiStrings.MOBILE_VALUE_VALUE_78C505,state.country,state.phone))
                BrandField(code,{code=it.filter {c->c in '0'..'9'}.take(6)},copyText(UiStrings.MOBILE_VERIFICATION_CODE),Modifier.testTag("previewCode"),!state.busy,role=FieldRole.ONE_TIME_CODE,errorMessage=state.error)
                Text(copyText(UiStrings.MOBILE_DEMO_CODE_123456),style=MaterialTheme.typography.bodySmall)
                BrandButton(if(state.busy)copyText(UiStrings.MOBILE_VERIFYING) else copyText(UiStrings.MOBILE_VERIFY),{focus.clearFocus();keyboard?.hide();scope.launch {controller.verify(code);code=""}},!state.busy)
                val seconds=((state.resendAt-now+999)/1000).coerceAtLeast(0)
                BrandTextButton(if(seconds>0)copyText(UiStrings.MOBILE_RESEND_IN_VALUES,seconds) else copyText(UiStrings.MOBILE_RESEND_CODE),controller::sendCode,!state.busy && seconds==0L)
                BrandTextButton(copyText(UiStrings.MOBILE_CHANGE_NUMBER),{controller.choose("Phone")},!state.busy)
                BrandTextButton(copyText(UiStrings.MOBILE_SIMULATE_EXPIRED_CODE),controller::expire,!state.busy)
            }
            null -> Unit
        }
        if(state.page!=PreviewAuthPage.CODE)BrandError(state.error)
        state.notice?.let {Text(it)}
        BrandTextButton(copyText(UiStrings.MOBILE_CANCEL_PREVIEW),controller::cancel,!state.busy)
    }
}

private fun providerLabel(provider:String,text:MobileText):String=when(provider){"Phone"->text(UiStrings.MOBILE_PHONE);"Makerspace SSO"->text(UiStrings.MOBILE_MAKERSPACE_SSO);else->provider}
