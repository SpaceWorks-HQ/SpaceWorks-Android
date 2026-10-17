package org.spaceworks.app.ui

import org.spaceworks.app.network.retrySecondsRemaining

import org.spaceworks.app.platform.*

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
    val copyText=rememberMobileText()

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
        do {retrySeconds=retrySecondsRemaining(System.currentTimeMillis(),state.retryAtMillis);if(retrySeconds>0)kotlinx.coroutines.delay(250)}while(retrySeconds>0)
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
        usernameError=if(username.isBlank())copyText(UiStrings.MOBILE_ENTER_YOUR_EMAIL_OR_USERNAME) else null
        passwordError=if(password.isEmpty())copyText(UiStrings.MOBILE_ENTER_YOUR_PASSWORD) else null
        when {usernameError!=null->usernameFocus.requestFocus();passwordError!=null->passwordFocus.requestFocus();else->{
            focus.clearFocus();keyboard?.hide();scope.launch {holder.signIn(username,password);if(holder.state.value.mfa!=null || holder.state.value.user!=null)password=""}
        }}
    }
    LaunchedEffect(holder) {if(!state.initialized)holder.initialize()}
    when {
        state.gate!=null -> AuthLayout(if(state.gate==AuthGate.WEB_CONSOLE)copyText(UiStrings.MOBILE_USE_THE_WEB_CONSOLE) else copyText(UiStrings.MOBILE_SIGN_IN_AGAIN),
            if(state.gate==AuthGate.WEB_CONSOLE)copyText(UiStrings.MOBILE_SUPERADMIN_ACCOUNTS_USE_THE_WEB_CONSOLE_SIGN_IN_HERE_WITH_A_MEMB)
            else copyText(UiStrings.MOBILE_YOUR_SESSION_ENDED_SIGN_IN_AGAIN_TO_CONTINUE)) {
            BrandButton(copyText(UiStrings.MOBILE_BACK_TO_SIGN_IN),holder::dismissGate,!state.busy)
            onChangeServer?.let {BrandTextButton(copyText(UiStrings.MOBILE_CHANGE_SERVER),it,!state.busy)}
        }
        state.expiredMfa -> AuthLayout(copyText(UiStrings.MOBILE_SIGN_IN_AGAIN),copyText(UiStrings.MOBILE_YOUR_VERIFICATION_SESSION_EXPIRED_SIGN_IN_TO_REQUEST_A_NEW_ONE)) {
            BrandButton(copyText(UiStrings.MOBILE_BACK_TO_SIGN_IN),{scope.launch {holder.cancelMfa()}})
        }
        state.recoveryCodes.isNotEmpty() -> AuthLayout(copyText(UiStrings.MOBILE_SAVE_YOUR_RECOVERY_CODES),copyText(UiStrings.MOBILE_KEEP_THESE_SOMEWHERE_SAFE_YOU_CAN_USE_EACH_CODE_ONCE_IF_YOU_LOSE)) {
            SelectionContainer {Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {state.recoveryCodes.forEach {Text(it)}}}
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(acknowledged,{acknowledged=it},Modifier.semantics {contentDescription=copyText(UiStrings.MOBILE_I_VE_SAVED_MY_RECOVERY_CODES)})
                Text(copyText(UiStrings.MOBILE_I_VE_SAVED_MY_RECOVERY_CODES))
            }
            BrandError(state.error)
            BrandButton(copyText(UiStrings.MOBILE_CONTINUE),{scope.launch {holder.finishMfa()}},!state.busy && acknowledged)
        }
        state.mfa!=null -> {
            val mfa=requireNotNull(state.mfa)
            val enrollment=mfa.enrollmentRequired
            AuthLayout(if(enrollment)copyText(UiStrings.MOBILE_ADD_EXTRA_SECURITY) else if(recoveryEntry)copyText(UiStrings.MOBILE_USE_A_RECOVERY_CODE) else copyText(UiStrings.MOBILE_CHECK_YOUR_AUTHENTICATOR),
                if(enrollment)copyText(UiStrings.MOBILE_ADD_THIS_ACCOUNT_TO_YOUR_AUTHENTICATOR_APP_THEN_ENTER_ITS_SIX_DI)
                else if(recoveryEntry)copyText(UiStrings.MOBILE_ENTER_ONE_OF_THE_RECOVERY_CODES_YOU_SAVED_WHEN_YOU_SET_UP_EXTRA)
                else copyText(UiStrings.MOBILE_ENTER_THE_SIX_DIGIT_CODE_FROM_YOUR_AUTHENTICATOR_APP)) {
                if(enrollment && state.enrollment==null) BrandButton(copyText(UiStrings.MOBILE_SHOW_SETUP_CODE),{scope.launch {holder.enroll()}},!state.busy)
                state.enrollment?.let {setup ->
                    val bitmap=remember(setup.qrDataUrl) {try {val bytes=Base64.decode(setup.qrDataUrl.substringAfter(','),Base64.DEFAULT);BitmapFactory.decodeByteArray(bytes,0,bytes.size)} catch(_:Exception){null}}
                    bitmap?.let {Image(it.asImageBitmap(),copyText(UiStrings.MOBILE_SCAN_TO_ADD_SPACE_WORKS_TO_YOUR_AUTHENTICATOR),Modifier.sizeIn(maxWidth=224.dp,maxHeight=224.dp))}
                    BrandTextButton(copyText(UiStrings.MOBILE_CAN_T_SCAN_THE_CODE),{manualKey=!manualKey},!state.busy)
                    if(manualKey) {
                        SelectionContainer {Text(copyText(UiStrings.MOBILE_SETUP_KEY_VALUE,setup.secret))}
                        BrandTextButton(copyText(UiStrings.MOBILE_COPY_SETUP_KEY),{
                            val clipboard=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip=android.content.ClipData.newPlainText(copyText(UiStrings.MOBILE_AUTHENTICATOR_SETUP_KEY),setup.secret)
                            clip.description.extras=android.os.PersistableBundle().apply {putBoolean("android.content.extra.IS_SENSITIVE",true)}
                            clipboard.setPrimaryClip(clip)
                        },!state.busy)
                    }
                }
                if(!enrollment || state.enrollment!=null) {
                    BrandField(code,{code=if(recoveryEntry)it.take(64) else it.filter {c->c in '0'..'9'}.take(6)},if(recoveryEntry)copyText(UiStrings.MOBILE_RECOVERY_CODE) else copyText(UiStrings.MOBILE_AUTHENTICATION_CODE),Modifier.testTag("mfaCode"),!state.busy,role=if(recoveryEntry)FieldRole.TEXT else FieldRole.CODE)
                    BrandButton(if(state.busy)copyText(UiStrings.MOBILE_VERIFYING) else if(enrollment)copyText(UiStrings.MOBILE_VERIFY_AND_CONTINUE) else copyText(UiStrings.MOBILE_VERIFY),{
                        focus.clearFocus();keyboard?.hide();scope.launch {holder.verify(code);code=""}
                    },!state.busy && if(recoveryEntry)code.trim().length in 6..64 else code.length==6)
                }
                if(!enrollment)BrandTextButton(if(recoveryEntry)copyText(UiStrings.MOBILE_USE_AUTHENTICATOR_INSTEAD) else copyText(UiStrings.MOBILE_USE_A_RECOVERY_CODE),{recoveryEntry=!recoveryEntry;code=""},!state.busy)
                if(bundle.demo)Text(copyText(UiStrings.MOBILE_DEMO_CODE_123456),style=MaterialTheme.typography.bodySmall)
                BrandError(state.error)
                BrandTextButton(copyText(UiStrings.MOBILE_BACK_TO_SIGN_IN),{scope.launch {holder.cancelMfa()}},!state.busy)
            }
        }
        else -> AuthLayout(copyText(UiStrings.MOBILE_WELCOME_BACK),copyText(UiStrings.MOBILE_SIGN_IN_TO_YOUR_MAKERSPACE_ACCOUNT)) {
            if(!state.initialized) {
                if(state.busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(copyText(UiStrings.MOBILE_CHECKING_SIGN_IN_METHODS),Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
                BrandError(state.error)
                if(!state.busy)BrandButton(copyText(UiStrings.MOBILE_TRY_AGAIN),{scope.launch {holder.initialize()}})
            } else {
                state.googleClientId?.let {
                    if(holder.nativeSocialReady && !bundle.demo)GoogleSignInButton(!state.busy && retrySeconds==0L) {
                        scope.launch {holder.googleSignIn {client,nonce->GoogleIdentity.credential(context.activity() ?: throw AuthMessage(copyText(UiStrings.MOBILE_THIS_SIGN_IN_OPTION_ISN_T_AVAILABLE_ON_THIS_DEVICE)),client,nonce)}}
                    }
                    else if(!bundle.demo)Text(copyText(UiStrings.MOBILE_GOOGLE_DEVICE_SIGN_IN_IS_NOT_CONFIGURED_FOR_THIS_BUILD_CONTACT_Y),style=MaterialTheme.typography.bodySmall)
                    if(state.passwordEnabled && holder.nativeSocialReady)Text(copyText(UiStrings.MOBILE_OR_SIGN_IN_WITH_A_PASSWORD),style=MaterialTheme.typography.labelLarge)
                }
                if(state.passwordEnabled) {
                    BrandField(username,{username=it.take(254);usernameError=null},copyText(UiStrings.MOBILE_EMAIL_OR_USERNAME),Modifier.testTag("username").focusRequester(usernameFocus),!state.busy,role=FieldRole.IDENTITY,errorMessage=usernameError)
                    BrandField(password,{password=it.take(1024);passwordError=null},copyText(UiStrings.MOBILE_PASSWORD),Modifier.testTag("password").focusRequester(passwordFocus),!state.busy,secret=true,onDone={if(!state.busy && retrySeconds==0L)submit()},errorMessage=passwordError)
                    bundle.accounts?.let {accounts->BrandTextButton(copyText(UiStrings.MOBILE_FORGOT_PASSWORD),{accounts.open(AccountRoute.FORGOT_PASSWORD,username.takeIf {it.contains('@')}.orEmpty())},!state.busy)}
                    BrandError(state.error)
                    state.notice?.let {Text(it)}
                    account?.notice?.let {Text(it)}
                    if(retrySeconds>0)Text(copyText(UiStrings.MOBILE_TRY_AGAIN_IN_VALUES,retrySeconds),style=MaterialTheme.typography.bodySmall)
                    BrandButton(if(state.busy)copyText(UiStrings.MOBILE_SIGNING_IN) else copyText(UiStrings.MOBILE_SIGN_IN),submit,!state.busy && retrySeconds==0L,Modifier.testTag("submitSignIn"))
                    if(state.selfRegistrationEnabled)bundle.accounts?.let {accounts ->
                        Text(copyText(UiStrings.MOBILE_NEW_HERE),style=MaterialTheme.typography.bodySmall)
                        BrandTextButton(copyText(UiStrings.MOBILE_CREATE_ACCOUNT),{accounts.open(AccountRoute.SIGN_UP)},!state.busy)
                    }
                } else if(state.googleClientId==null || !holder.nativeSocialReady) {
                    Text(copyText(UiStrings.MOBILE_SIGN_IN_ISN_T_AVAILABLE_HERE),style=MaterialTheme.typography.titleMedium)
                    Text(copyText(UiStrings.MOBILE_ASK_YOUR_MAKERSPACE_FOR_HELP_OR_CHECK_THE_SERVER_ADDRESS))
                    BrandButton(copyText(UiStrings.MOBILE_TRY_AGAIN),{scope.launch {holder.initialize()}},!state.busy)
                }
            }
            if(bundle.demo) {
                bundle.previewAuth?.let {BrandTextButton(copyText(UiStrings.MOBILE_PREVIEW_SIGN_IN_OPTIONS),it::open,!state.busy)}
                BrandTextButton(copyText(UiStrings.MOBILE_DEMO_SCENARIOS),{scenarios=!scenarios},!state.busy)
                if(scenarios) {
                    listOf(copyText(UiStrings.MOBILE_MEMBER) to "meera.demo",copyText(UiStrings.MOBILE_STAFF) to "staff.demo",copyText(UiStrings.MOBILE_SINGLE_MAKERSPACE) to "single.demo",copyText(UiStrings.MOBILE_TWO_STEP) to "mfa.demo",copyText(UiStrings.MOBILE_ENROLLMENT) to "enroll.demo",copyText(UiStrings.MOBILE_EXPIRED_VERIFICATION) to "expired.demo",copyText(UiStrings.MOBILE_UNVERIFIED_EMAIL) to "unverified.demo",copyText(UiStrings.MOBILE_NO_MEMBERSHIP) to "empty.demo",copyText(UiStrings.MOBILE_PASSWORD_RENEWAL) to "temporary.demo",copyText(UiStrings.MOBILE_OFFLINE) to "offline.demo",copyText(UiStrings.MOBILE_COOLDOWN) to "throttle.demo",copyText(UiStrings.MOBILE_SUPERADMIN_REFUSAL) to "superadmin.demo").forEach {(label,identity)->
                        BrandTextButton(label,{username=identity;password="demo-password";scenarios=false},!state.busy)
                    }
                }
                Text(copyText(UiStrings.MOBILE_SAMPLE_DATA_ONLY_NO_ACCOUNT_OR_SERVER_CONNECTION_IS_NEEDED),style=MaterialTheme.typography.bodySmall)
            }
            onChangeServer?.let {BrandTextButton(copyText(UiStrings.MOBILE_CHANGE_SERVER),it,!state.busy)}
        }
    }
}
