package org.spaceworks.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.auth.*

@Composable fun AccountFlowScreen(holder:AccountStateHolder,demo:Boolean) {
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
    val title=when(state.route){AccountRoute.SIGN_UP->"Create your account";AccountRoute.SIGNUP_ACK->"Account created";AccountRoute.FORGOT_PASSWORD->"Reset password";AccountRoute.RESET_PASSWORD->"Enter your recovery code";AccountRoute.RESET_SUCCESS->"Password updated";AccountRoute.RECOVERY_UNAVAILABLE->"Password recovery unavailable";else->"Confirm your email"}
    val subtitle=when(state.route){AccountRoute.SIGN_UP->"Use the email you want to use with your makerspace.";AccountRoute.SIGNUP_ACK->"Sign in to continue. You may need to confirm your email.";AccountRoute.FORGOT_PASSWORD->"Enter your account email to request a six-digit recovery code.";AccountRoute.RESET_PASSWORD->"If an account matches this email, a recovery code will arrive. Delivery may take a few minutes.";AccountRoute.RESET_SUCCESS->"Your password has been updated. You can now sign in.";AccountRoute.RECOVERY_UNAVAILABLE->"Contact your makerspace staff to reset your password.";else->"Enter the six-digit code sent to your email."}
    AuthLayout(title,subtitle) {
        when(state.route) {
            AccountRoute.SIGN_UP -> {
                BrandField(name,{name=it.take(200)},"Name",enabled=!state.busy,role=FieldRole.NAME)
                BrandField(email,{email=it.take(254)},"Email",Modifier.testTag("accountEmail"),!state.busy,role=FieldRole.EMAIL)
                BrandTextButton("Add phone number (optional)",{showPhone=!showPhone},!state.busy)
                if(showPhone)BrandField(phone,{phone=it.take(32)},"Phone number (optional)",enabled=!state.busy,role=FieldRole.PHONE)
                BrandField(password,{password=it.take(128)},"Password",Modifier.testTag("accountPassword"),!state.busy,secret=true,role=FieldRole.NEW_PASSWORD)
                BrandField(confirmation,{confirmation=it.take(128)},"Confirm password",Modifier.testTag("accountConfirmation"),!state.busy,secret=true,role=FieldRole.NEW_PASSWORD)
                BrandButton(if(state.busy)"Creating account…" else "Create account",{submit {holder.signUp(name,email,password,confirmation,phone)}},ready)
            }
            AccountRoute.FORGOT_PASSWORD -> {
                BrandField(email,{email=it.take(254)},"Email",Modifier.testTag("accountEmail"),!state.busy,role=FieldRole.EMAIL,onDone={if(ready)submit {holder.forgot(email)}})
                BrandButton(if(state.busy)"Sending…" else "Request recovery code",{submit {holder.forgot(email)}},ready)
            }
            AccountRoute.RESET_PASSWORD -> {
                Text(state.email,style=MaterialTheme.typography.bodyMedium)
                BrandField(code,{code=it.filter {c->c in '0'..'9'}.take(6)},"Six-digit code",Modifier.testTag("accountCode"),!state.busy,role=FieldRole.ONE_TIME_CODE)
                BrandField(password,{password=it.take(128)},"New password",Modifier.testTag("accountPassword"),!state.busy,secret=true,role=FieldRole.NEW_PASSWORD)
                BrandField(confirmation,{confirmation=it.take(128)},"Confirm password",Modifier.testTag("accountConfirmation"),!state.busy,secret=true,role=FieldRole.NEW_PASSWORD)
                BrandButton(if(state.busy)"Updating…" else "Update password",{submit {holder.reset(code,password,confirmation)}},ready)
                val seconds=((state.resendAtMillis-now+999)/1000).coerceAtLeast(0)
                BrandTextButton(if(seconds>0)"Request another code in ${seconds}s" else "Request another code",{submit {holder.forgot(state.email,true)}},ready && seconds==0L)
                BrandTextButton("Change email",{holder.open(AccountRoute.FORGOT_PASSWORD,state.email)},!state.busy)
            }
            AccountRoute.VERIFY_EMAIL -> {
                if(state.email.isNotBlank())Text(state.email)
                BrandField(code,{code=it.filter {c->c in '0'..'9'}.take(6)},"Verification code",Modifier.testTag("accountCode"),!state.busy,role=FieldRole.ONE_TIME_CODE)
                BrandButton("Confirm email",{submit {holder.verifyEmail(code)}},ready)
                val seconds=((state.resendAtMillis-now+999)/1000).coerceAtLeast(0)
                BrandTextButton(if(seconds>0)"Send another code in ${seconds}s" else "Send another code",{submit {holder.resendVerification()}},ready && seconds==0L)
            }
            AccountRoute.SIGNUP_ACK,AccountRoute.RESET_SUCCESS -> BrandButton("Go to sign in",holder::back)
            AccountRoute.RECOVERY_UNAVAILABLE -> Unit
            null -> Unit
        }
        if(state.busy)LinearProgressIndicator(Modifier)
        BrandError(state.error)
        state.notice?.let {Text(it)}
        if(now<state.retryAtMillis)Text("Try again in ${((state.retryAtMillis-now+999)/1000).coerceAtLeast(0)} seconds.")
        if(demo)Text("Demo only. No email is sent. Verification code: 123456",style=MaterialTheme.typography.bodySmall)
        if(state.route!=AccountRoute.SIGNUP_ACK && state.route!=AccountRoute.RESET_SUCCESS)BrandTextButton(if(state.route==AccountRoute.VERIFY_EMAIL)"Back" else "Back to sign in",holder::back,!state.busy)
    }
}
