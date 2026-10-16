package org.spaceworks.app.ui

import org.spaceworks.app.network.retrySecondsRemaining

import org.spaceworks.app.platform.*

import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.auth.*

@Composable fun AccountFlowScreen(holder:AccountStateHolder,demo:Boolean) {
    val copyText=rememberMobileText()

    val state by holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    var name by remember {mutableStateOf("")}
    var email by remember {mutableStateOf(state.email)}
    var phone by remember {mutableStateOf("")}
    var password by remember {mutableStateOf("")}
    var confirmation by remember {mutableStateOf("")}
    var code by remember {mutableStateOf("")}
    var showPhone by remember {mutableStateOf(false)}
    ProtectSecrets(!demo)
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(state.retryAtMillis,state.resendAtMillis) {
        while(true){now=System.currentTimeMillis();kotlinx.coroutines.delay(500)}
    }
    val ready=!state.busy && now>=state.retryAtMillis
    fun submit(block:suspend()->Unit) {
        focus.clearFocus();keyboard?.hide()
        val before=state.route
        scope.launch {
            block()
            if(holder.state.value.route!=before){password="";confirmation=""}
            code=""
        }
    }
    BackHandler {if(!state.busy)holder.back()}
    val title=when(state.route){AccountRoute.SIGN_UP->copyText(UiStrings.MOBILE_CREATE_YOUR_ACCOUNT);AccountRoute.SIGNUP_ACK->copyText(UiStrings.MOBILE_ACCOUNT_CREATED);AccountRoute.FORGOT_PASSWORD->copyText(UiStrings.MOBILE_RESET_PASSWORD);AccountRoute.RESET_PASSWORD->copyText(UiStrings.MOBILE_ENTER_YOUR_RECOVERY_CODE);AccountRoute.RESET_SUCCESS->copyText(UiStrings.MOBILE_PASSWORD_UPDATED);AccountRoute.RECOVERY_UNAVAILABLE->copyText(UiStrings.MOBILE_PASSWORD_RECOVERY_UNAVAILABLE);else->copyText(UiStrings.MOBILE_CONFIRM_YOUR_EMAIL)}
    val subtitle=when(state.route){AccountRoute.SIGN_UP->copyText(UiStrings.MOBILE_USE_THE_EMAIL_YOU_WANT_TO_USE_WITH_YOUR_MAKERSPACE);AccountRoute.SIGNUP_ACK->copyText(UiStrings.MOBILE_SIGN_IN_TO_CONTINUE_YOU_MAY_NEED_TO_CONFIRM_YOUR_EMAIL);AccountRoute.FORGOT_PASSWORD->copyText(UiStrings.MOBILE_ENTER_YOUR_ACCOUNT_EMAIL_TO_REQUEST_A_SIX_DIGIT_RECOVERY_CODE);AccountRoute.RESET_PASSWORD->copyText(UiStrings.MOBILE_IF_AN_ACCOUNT_MATCHES_THIS_EMAIL_A_RECOVERY_CODE_WILL_ARRIVE_DEL);AccountRoute.RESET_SUCCESS->copyText(UiStrings.MOBILE_YOUR_PASSWORD_HAS_BEEN_UPDATED_YOU_CAN_NOW_SIGN_IN);AccountRoute.RECOVERY_UNAVAILABLE->copyText(UiStrings.MOBILE_CONTACT_YOUR_MAKERSPACE_STAFF_TO_RESET_YOUR_PASSWORD);else->copyText(UiStrings.MOBILE_ENTER_THE_SIX_DIGIT_CODE_SENT_TO_YOUR_EMAIL)}
    AuthLayout(title,subtitle) {
        when(state.route) {
            AccountRoute.SIGN_UP -> {
                BrandField(name,{name=it.take(200)},copyText(UiStrings.MOBILE_NAME),enabled=!state.busy,role=FieldRole.NAME)
                BrandField(email,{email=it.take(254)},copyText(UiStrings.MOBILE_EMAIL),Modifier.testTag("accountEmail"),!state.busy,role=FieldRole.EMAIL)
                BrandTextButton(copyText(UiStrings.MOBILE_ADD_PHONE_NUMBER_OPTIONAL),{showPhone=!showPhone},!state.busy)
                if(showPhone)BrandField(phone,{phone=it.take(32)},copyText(UiStrings.MOBILE_PHONE_NUMBER_OPTIONAL),enabled=!state.busy,role=FieldRole.PHONE)
                BrandField(password,{password=it.take(128)},copyText(UiStrings.MOBILE_PASSWORD),Modifier.testTag("accountPassword"),!state.busy,secret=true,role=FieldRole.NEW_PASSWORD)
                BrandField(confirmation,{confirmation=it.take(128)},copyText(UiStrings.MOBILE_CONFIRM_PASSWORD),Modifier.testTag("accountConfirmation"),!state.busy,secret=true,role=FieldRole.NEW_PASSWORD,onDone={if(ready)submit {holder.signUp(name,email,password,confirmation,phone)}})
                BrandButton(if(state.busy)copyText(UiStrings.MOBILE_CREATING_ACCOUNT) else copyText(UiStrings.MOBILE_CREATE_ACCOUNT),{submit {holder.signUp(name,email,password,confirmation,phone)}},ready)
            }
            AccountRoute.FORGOT_PASSWORD -> {
                BrandField(email,{email=it.take(254)},copyText(UiStrings.MOBILE_EMAIL),Modifier.testTag("accountEmail"),!state.busy,role=FieldRole.EMAIL,onDone={if(ready)submit {holder.forgot(email)}})
                BrandButton(if(state.busy)copyText(UiStrings.MOBILE_SENDING) else copyText(UiStrings.MOBILE_REQUEST_RECOVERY_CODE),{submit {holder.forgot(email)}},ready)
            }
            AccountRoute.RESET_PASSWORD -> {
                Text(state.email,style=MaterialTheme.typography.bodyMedium)
                BrandField(code,{code=it.filter {c->c in '0'..'9'}.take(6)},copyText(UiStrings.MOBILE_SIX_DIGIT_CODE),Modifier.testTag("accountCode"),!state.busy,role=FieldRole.ONE_TIME_CODE)
                BrandField(password,{password=it.take(128)},copyText(UiStrings.MOBILE_NEW_PASSWORD),Modifier.testTag("accountPassword"),!state.busy,secret=true,role=FieldRole.NEW_PASSWORD)
                BrandField(confirmation,{confirmation=it.take(128)},copyText(UiStrings.MOBILE_CONFIRM_PASSWORD),Modifier.testTag("accountConfirmation"),!state.busy,secret=true,role=FieldRole.NEW_PASSWORD,onDone={if(ready)submit {holder.reset(code,password,confirmation)}})
                BrandButton(if(state.busy)copyText(UiStrings.MOBILE_UPDATING) else copyText(UiStrings.MOBILE_UPDATE_PASSWORD),{submit {holder.reset(code,password,confirmation)}},ready)
                val seconds=((state.resendAtMillis-now+999)/1000).coerceAtLeast(0)
                BrandTextButton(if(seconds>0)copyText(UiStrings.MOBILE_REQUEST_ANOTHER_CODE_IN_VALUES,seconds) else copyText(UiStrings.MOBILE_REQUEST_ANOTHER_CODE),{submit {holder.forgot(state.email,true)}},ready && seconds==0L)
                BrandTextButton(copyText(UiStrings.MOBILE_CHANGE_EMAIL),{holder.open(AccountRoute.FORGOT_PASSWORD,state.email)},!state.busy)
            }
            AccountRoute.VERIFY_EMAIL -> {
                if(state.email.isNotBlank())Text(state.email)
                BrandField(code,{code=it.filter {c->c in '0'..'9'}.take(6)},copyText(UiStrings.MOBILE_VERIFICATION_CODE),Modifier.testTag("accountCode"),!state.busy,role=FieldRole.ONE_TIME_CODE,onDone={if(ready)submit {holder.verifyEmail(code)}})
                BrandButton(copyText(UiStrings.MOBILE_CONFIRM_EMAIL),{submit {holder.verifyEmail(code)}},ready)
                val seconds=((state.resendAtMillis-now+999)/1000).coerceAtLeast(0)
                BrandTextButton(if(seconds>0)copyText(UiStrings.MOBILE_SEND_ANOTHER_CODE_IN_VALUES,seconds) else copyText(UiStrings.MOBILE_SEND_ANOTHER_CODE),{submit {holder.resendVerification()}},ready && seconds==0L)
            }
            AccountRoute.SIGNUP_ACK,AccountRoute.RESET_SUCCESS -> BrandButton(copyText(UiStrings.MOBILE_GO_TO_SIGN_IN),holder::back)
            AccountRoute.RECOVERY_UNAVAILABLE -> Unit
            null -> Unit
        }
        if(state.busy)LinearProgressIndicator(Modifier)
        BrandError(state.error)
        state.notice?.let {Text(it)}
        if(now<state.retryAtMillis)Text(copyText(UiStrings.MOBILE_TRY_AGAIN_IN_VALUE_SECONDS,retrySecondsRemaining(now,state.retryAtMillis)))
        if(demo)Text(copyText(UiStrings.MOBILE_DEMO_ONLY_NO_EMAIL_IS_SENT_VERIFICATION_CODE_123456),style=MaterialTheme.typography.bodySmall)
        if(state.route!=AccountRoute.SIGNUP_ACK && state.route!=AccountRoute.RESET_SUCCESS)BrandTextButton(if(state.route==AccountRoute.VERIFY_EMAIL)copyText(UiStrings.MOBILE_BACK) else copyText(UiStrings.MOBILE_BACK_TO_SIGN_IN),holder::back,!state.busy)
    }
}
