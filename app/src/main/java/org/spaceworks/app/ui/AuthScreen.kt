package org.spaceworks.app.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.auth.*
import org.spaceworks.app.platform.GoogleIdentity

@Composable fun AuthScreen(bundle:AuthBundle,onChangeServer:(()->Unit)?=null) {
    val holder=bundle.holder
    val state by holder.state.collectAsStateWithLifecycle()
    val account=bundle.accounts?.state?.collectAsStateWithLifecycle()?.value
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val usernameFocus=remember {FocusRequester()}
    val passwordFocus=remember {FocusRequester()}
    var username by remember {mutableStateOf(account?.email?.takeIf {it.isNotBlank()} ?: if(bundle.demo)"meera.demo" else "")}
    var password by remember {mutableStateOf(if(bundle.demo)"demo-password" else "")}
    var code by remember {mutableStateOf("")}
    var recoveryEntry by remember {mutableStateOf(false)}
    var manualKey by remember {mutableStateOf(false)}
    var acknowledged by remember {mutableStateOf(false)}
    var usernameError by remember {mutableStateOf<String?>(null)}
    var passwordError by remember {mutableStateOf<String?>(null)}
    var scenarios by remember {mutableStateOf(false)}
    var retrySeconds by remember {mutableLongStateOf(0)}
    ProtectSecrets(!bundle.demo && (state.mfa!=null || state.recoveryCodes.isNotEmpty()))
    LaunchedEffect(state.retryAtMillis) {
        do {retrySeconds=((state.retryAtMillis-System.currentTimeMillis()+999)/1000).coerceAtLeast(0);if(retrySeconds>0)kotlinx.coroutines.delay(250)}while(retrySeconds>0)
    }
    LaunchedEffect(state.mfaExpiresAtMillis,state.recoveryCodes.isNotEmpty()) {
        if(state.mfaExpiresAtMillis>0 && state.recoveryCodes.isEmpty()) {
            kotlinx.coroutines.delay((state.mfaExpiresAtMillis-System.currentTimeMillis()).coerceAtLeast(0))
            while(holder.state.value.busy)kotlinx.coroutines.delay(100)
            holder.expireMfa()
        }
    }
    BackHandler(state.mfa!=null || state.recoveryCodes.isNotEmpty()) {
        if(!state.busy && state.recoveryCodes.isEmpty())scope.launch {holder.cancelMfa()}
    }
    val submit:()->Unit={
        usernameError=if(username.isBlank())"Enter your email or username." else null
        passwordError=if(password.isEmpty())"Enter your password." else null
        when {usernameError!=null->usernameFocus.requestFocus();passwordError!=null->passwordFocus.requestFocus();else->{
            focus.clearFocus();keyboard?.hide();scope.launch {holder.signIn(username,password);if(holder.state.value.mfa!=null || holder.state.value.user!=null)password=""}
        }}
    }
    LaunchedEffect(holder) {if(!state.initialized)holder.initialize()}
    when {
        state.gate!=null -> AuthLayout(if(state.gate==AuthGate.WEB_CONSOLE)"Use the web console" else "Sign in again",
            if(state.gate==AuthGate.WEB_CONSOLE)"Superadmin accounts use the web console. Sign in here with a member account."
            else "Your session ended. Sign in again to continue.") {
            BrandButton("Back to sign in",holder::dismissGate,!state.busy)
            onChangeServer?.let {BrandTextButton("Change server",it,!state.busy)}
        }
        state.expiredMfa -> AuthLayout("Sign in again","Your verification session expired. Sign in to request a new one.") {
            BrandButton("Back to sign in",{scope.launch {holder.cancelMfa()}})
        }
        state.recoveryCodes.isNotEmpty() -> AuthLayout("Save your recovery codes","Keep these somewhere safe. You can use each code once if you lose access to your authenticator.") {
            SelectionContainer {Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {state.recoveryCodes.forEach {Text(it)}}}
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(acknowledged,{acknowledged=it},Modifier.semantics {contentDescription="I've saved my recovery codes"})
                Text("I've saved my recovery codes")
            }
            BrandError(state.error)
            BrandButton("Continue",{scope.launch {holder.finishMfa()}},!state.busy && acknowledged)
        }
        state.mfa!=null -> {
            val mfa=requireNotNull(state.mfa)
            val enrollment=mfa.enrollmentRequired
            AuthLayout(if(enrollment)"Add extra security" else if(recoveryEntry)"Use a recovery code" else "Check your authenticator",
                if(enrollment)"Add this account to your authenticator app, then enter its six-digit code."
                else if(recoveryEntry)"Enter one of the recovery codes you saved when you set up extra security."
                else "Enter the six-digit code from your authenticator app.") {
                if(enrollment && state.enrollment==null) BrandButton("Show setup code",{scope.launch {holder.enroll()}},!state.busy)
                state.enrollment?.let {setup ->
                    val bitmap=remember(setup.qrDataUrl) {try {val bytes=Base64.decode(setup.qrDataUrl.substringAfter(','),Base64.DEFAULT);BitmapFactory.decodeByteArray(bytes,0,bytes.size)} catch(_:Exception){null}}
                    bitmap?.let {Image(it.asImageBitmap(),"Scan to add Space Works to your authenticator",Modifier.sizeIn(maxWidth=224.dp,maxHeight=224.dp))}
                    BrandTextButton("Can't scan the code?",{manualKey=!manualKey},!state.busy)
                    if(manualKey) {
                        SelectionContainer {Text("Setup key: ${setup.secret}")}
                        BrandTextButton("Copy setup key",{
                            val clipboard=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip=android.content.ClipData.newPlainText("Authenticator setup key",setup.secret)
                            clip.description.extras=android.os.PersistableBundle().apply {putBoolean("android.content.extra.IS_SENSITIVE",true)}
                            clipboard.setPrimaryClip(clip)
                        },!state.busy)
                    }
                }
                if(!enrollment || state.enrollment!=null) {
                    BrandField(code,{code=if(recoveryEntry)it.take(64) else it.filter {c->c in '0'..'9'}.take(6)},if(recoveryEntry)"Recovery code" else "Authentication code",Modifier.testTag("mfaCode"),!state.busy,role=if(recoveryEntry)FieldRole.TEXT else FieldRole.CODE)
                    BrandButton(if(state.busy)"Verifying…" else if(enrollment)"Verify and continue" else "Verify",{
                        focus.clearFocus();keyboard?.hide();scope.launch {holder.verify(code);code=""}
                    },!state.busy && if(recoveryEntry)code.trim().length in 6..64 else code.length==6)
                }
                if(!enrollment)BrandTextButton(if(recoveryEntry)"Use authenticator instead" else "Use a recovery code",{recoveryEntry=!recoveryEntry;code=""},!state.busy)
                if(bundle.demo)Text("Demo code: 123456",style=MaterialTheme.typography.bodySmall)
                BrandError(state.error)
                BrandTextButton("Back to sign in",{scope.launch {holder.cancelMfa()}},!state.busy)
            }
        }
        else -> AuthLayout("Welcome back.","Sign in to your makerspace account.") {
            if(!state.initialized) {
                if(state.busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Checking sign-in methods…",Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
                BrandError(state.error)
                if(!state.busy)BrandButton("Try again",{scope.launch {holder.initialize()}})
            } else {
                state.googleClientId?.let {
                    if(holder.nativeSocialReady && !bundle.demo)GoogleSignInButton(!state.busy && retrySeconds==0L) {
                        scope.launch {holder.googleSignIn {client,nonce->GoogleIdentity.credential(context.activity() ?: throw AuthMessage("This sign-in option isn't available on this device."),client,nonce)}}
                    }
                    else if(!bundle.demo)Text("Google device sign-in is not configured for this build. Contact your makerspace.",style=MaterialTheme.typography.bodySmall)
                    if(state.passwordEnabled && holder.nativeSocialReady)Text("or sign in with a password",style=MaterialTheme.typography.labelLarge)
                }
                if(state.passwordEnabled) {
                    BrandField(username,{username=it.take(254);usernameError=null},"Email or username",Modifier.testTag("username").focusRequester(usernameFocus),!state.busy,role=FieldRole.IDENTITY,errorMessage=usernameError)
                    BrandField(password,{password=it.take(1024);passwordError=null},"Password",Modifier.testTag("password").focusRequester(passwordFocus),!state.busy,secret=true,onDone={if(!state.busy && retrySeconds==0L)submit()},errorMessage=passwordError)
                    bundle.accounts?.let {accounts->BrandTextButton("Forgot password?",{accounts.open(AccountRoute.FORGOT_PASSWORD,username.takeIf {it.contains('@')}.orEmpty())},!state.busy)}
                    BrandError(state.error)
                    state.notice?.let {Text(it)}
                    account?.notice?.let {Text(it)}
                    if(retrySeconds>0)Text("Try again in ${retrySeconds}s.",style=MaterialTheme.typography.bodySmall)
                    BrandButton(if(state.busy)"Signing in…" else "Sign in",submit,!state.busy && retrySeconds==0L,Modifier.testTag("submitSignIn"))
                    if(state.selfRegistrationEnabled)bundle.accounts?.let {accounts ->
                        Text("New here?",style=MaterialTheme.typography.bodySmall)
                        BrandTextButton("Create account",{accounts.open(AccountRoute.SIGN_UP)},!state.busy)
                    }
                } else if(state.googleClientId==null || !holder.nativeSocialReady) {
                    Text("Sign-in isn't available here",style=MaterialTheme.typography.titleMedium)
                    Text("Ask your makerspace for help, or check the server address.")
                    BrandButton("Try again",{scope.launch {holder.initialize()}},!state.busy)
                }
            }
            if(bundle.demo) {
                bundle.previewAuth?.let {BrandTextButton("Preview sign-in options",it::open,!state.busy)}
                BrandTextButton("Demo scenarios",{scenarios=!scenarios},!state.busy)
                if(scenarios) {
                    listOf("Member" to "meera.demo","Staff" to "staff.demo","Single makerspace" to "single.demo","Two-step" to "mfa.demo","Enrollment" to "enroll.demo","Expired verification" to "expired.demo","Unverified email" to "unverified.demo","No membership" to "empty.demo","Password renewal" to "temporary.demo","Offline" to "offline.demo","Cooldown" to "throttle.demo","Superadmin refusal" to "superadmin.demo").forEach {(label,identity)->
                        BrandTextButton(label,{username=identity;password="demo-password";scenarios=false},!state.busy)
                    }
                }
                Text("Sample data only. No account or server connection is needed.",style=MaterialTheme.typography.bodySmall)
            }
            onChangeServer?.let {BrandTextButton("Change server",it,!state.busy)}
        }
    }
}
