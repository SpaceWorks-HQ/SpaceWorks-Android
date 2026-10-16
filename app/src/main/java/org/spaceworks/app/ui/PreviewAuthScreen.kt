package org.spaceworks.app.ui

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
    val state by controller.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    var code by remember {mutableStateOf("")}
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    var countries by remember {mutableStateOf(false)}
    LaunchedEffect(state.resendAt){while(true){now=System.currentTimeMillis();kotlinx.coroutines.delay(500)}}
    BackHandler {if(!state.busy)controller.cancel()}
    val title=when(state.page){PreviewAuthPage.METHODS->"Preview sign-in options";PreviewAuthPage.PROVIDER->"${state.provider} sign-in preview";PreviewAuthPage.PHONE->"Continue with phone";PreviewAuthPage.UPDATE_REQUIRED->"Update required";PreviewAuthPage.SESSION_ENDED->"Sign in again";else->"Enter your verification code"}
    AuthLayout(title,"Demo only. No provider account, SMS or live server is used.") {
        when(state.page) {
            PreviewAuthPage.METHODS -> {
                listOf("Google","Apple","Makerspace SSO","Phone").forEach {provider->BrandButton("Preview $provider",{controller.choose(provider)})}
                BrandTextButton("Preview update required",controller::updateRequired)
                BrandTextButton("Preview ended session",controller::sessionEnded)
            }
            PreviewAuthPage.UPDATE_REQUIRED -> {
                Text("Update Space Works to continue using your makerspace.")
                Text("Preview only. The current server contract does not publish a minimum app version or store update URL.",style=MaterialTheme.typography.bodySmall)
                BrandButton("Back to preview options",controller::open)
            }
            PreviewAuthPage.SESSION_ENDED -> {
                Text("Your session ended. Sign in again to continue.")
                BrandButton("Back to sign in",controller::cancel)
            }
            PreviewAuthPage.PROVIDER -> {
                Text("Sample account",style=MaterialTheme.typography.labelLarge)
                Text("Meera Nair",style=MaterialTheme.typography.titleLarge)
                Text("meera@example.invalid")
                Row(Modifier.fillMaxWidth().toggleable(state.requireMfa,enabled=!state.busy,
                    role=androidx.compose.ui.semantics.Role.Checkbox,onValueChange=controller::requireMfa),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(state.requireMfa,null,enabled=!state.busy)
                    Text("Require extra security in this preview")
                }
                BrandButton(if(state.busy)"Signing in…" else "Continue with sample account",{scope.launch {controller.continueProvider()}},!state.busy)
                BrandTextButton("Simulate unavailable",controller::unavailable,!state.busy)
            }
            PreviewAuthPage.PHONE -> {
                Box {
                    BrandTextButton("Country code: ${state.country}",{countries=true},!state.busy)
                    DropdownMenu(countries,{countries=false}) {listOf("India +91" to "+91","United States +1" to "+1","United Kingdom +44" to "+44").forEach {(label,value)->DropdownMenuItem(text={Text(label)},onClick={controller.country(value);countries=false})}}
                }
                BrandField(state.phone,controller::phone,"Phone number",Modifier.testTag("previewPhone"),!state.busy,role=FieldRole.PHONE,
                    onDone={if(!state.busy && now>=state.resendAt){focus.clearFocus();keyboard?.hide();controller.sendCode()}})
                Text("We'll send a verification code. In this demo, no SMS is sent.")
                BrandButton("Send code",{focus.clearFocus();keyboard?.hide();controller.sendCode()},!state.busy && now>=state.resendAt)
                if(now<state.resendAt)Text("Try again in ${((state.resendAt-now+999)/1000).coerceAtLeast(0)}s.")
            }
            PreviewAuthPage.CODE -> {
                Text("${state.country} ${state.phone}")
                BrandField(code,{code=it.filter {c->c in '0'..'9'}.take(6)},"Verification code",Modifier.testTag("previewCode"),!state.busy,role=FieldRole.ONE_TIME_CODE,errorMessage=state.error)
                Text("Demo code: 123456",style=MaterialTheme.typography.bodySmall)
                BrandButton(if(state.busy)"Verifying…" else "Verify",{focus.clearFocus();keyboard?.hide();scope.launch {controller.verify(code);code=""}},!state.busy)
                val seconds=((state.resendAt-now+999)/1000).coerceAtLeast(0)
                BrandTextButton(if(seconds>0)"Resend in ${seconds}s" else "Resend code",controller::sendCode,!state.busy && seconds==0L)
                BrandTextButton("Change number",{controller.choose("Phone")},!state.busy)
                BrandTextButton("Simulate expired code",controller::expire,!state.busy)
            }
            null -> Unit
        }
        if(state.page!=PreviewAuthPage.CODE)BrandError(state.error)
        state.notice?.let {Text(it)}
        BrandTextButton("Cancel preview",controller::cancel,!state.busy)
    }
}
