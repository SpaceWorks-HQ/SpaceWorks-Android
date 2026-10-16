package org.spaceworks.app.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.auth.*

@Composable fun AuthScreen(bundle:AuthBundle,onChangeServer:(()->Unit)?=null) {
    val holder=bundle.holder
    val state by holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var username by remember {mutableStateOf(if(bundle.demo)"meera.demo" else "")}
    var password by remember {mutableStateOf(if(bundle.demo)"demo-password" else "")}
    var code by remember {mutableStateOf("")}
    LaunchedEffect(holder) {if(!state.initialized)holder.initialize()}
    when {
        state.recoveryCodes.isNotEmpty() -> AuthLayout("Save your recovery codes","Each code works once. Save these somewhere secure; they will not be shown again.") {
            SelectionContainer {Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {state.recoveryCodes.forEach {Text(it)}}}
            BrandError(state.error)
            BrandButton("I saved my codes. Continue",{scope.launch {holder.finishMfa()}},!state.busy)
        }
        state.mfa!=null -> {
            val mfa=requireNotNull(state.mfa)
            AuthLayout(if(mfa.enrollmentRequired)"Set up two-step sign-in" else "Verify your sign-in",
                if(mfa.enrollmentRequired)"Add Space Works to your authenticator app to secure this account." else "Enter the code from your authenticator app, or one of your recovery codes.") {
                if(mfa.enrollmentRequired && state.enrollment==null) BrandButton("Show enrollment QR and key",{scope.launch {holder.enroll()}},!state.busy)
                state.enrollment?.let {enrollment ->
                    val bitmap=remember(enrollment.qrDataUrl) {try {val bytes=Base64.decode(enrollment.qrDataUrl.substringAfter(','),Base64.DEFAULT);BitmapFactory.decodeByteArray(bytes,0,bytes.size)} catch(_:Exception){null}}
                    bitmap?.let {Image(it.asImageBitmap(),"Scan to add Space Works to your authenticator",Modifier.size(224.dp))}
                    SelectionContainer {Text("Manual key: ${enrollment.secret}")}
                }
                if(!mfa.enrollmentRequired || state.enrollment!=null) {
                    BrandField(code,{code=it.take(64)},"Authenticator or recovery code",Modifier.testTag("mfaCode"),!state.busy)
                    BrandButton(if(state.busy)"Verifying…" else "Verify",{scope.launch {holder.verify(code);code=""}},!state.busy)
                }
                if(bundle.demo) Text("Demo code: 123456",style=MaterialTheme.typography.bodySmall)
                BrandError(state.error)
                BrandTextButton("Back to sign in",{scope.launch {holder.cancelMfa()}},!state.busy)
            }
        }
        else -> AuthLayout("Sign in","Your makerspace, in your pocket.") {
            if(!state.initialized) {
                if(state.busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Checking sign-in methods…")}
                BrandError(state.error)
                if(!state.busy)BrandButton("Try again",{scope.launch {holder.initialize()}})
            } else if(state.passwordEnabled) {
                BrandField(username,{username=it.take(254)},"Email or username",Modifier.testTag("username"),!state.busy)
                BrandField(password,{password=it.take(1024)},"Password",Modifier.testTag("password"),!state.busy,secret=true)
                BrandError(state.error)
                state.notice?.let {Text(it)}
                BrandButton(if(state.busy)"Signing in…" else "Sign in",{scope.launch {holder.signIn(username,password);password=""}},!state.busy,Modifier.testTag("submitSignIn"))
            } else {
                Text("This server signs members in through an identity provider.")
                Text("Native identity-provider sign-in is not configured on this server. Contact your makerspace.")
            }
            if(bundle.demo) {
                Text("Explore sign-in states",style=MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={username="mfa.demo";password="demo-password"},enabled=!state.busy,modifier=Modifier.weight(1f).heightIn(min=48.dp)) {Text("Two-step")}
                    OutlinedButton(onClick={username="enroll.demo";password="demo-password"},enabled=!state.busy,modifier=Modifier.weight(1f).heightIn(min=48.dp)) {Text("Enrollment")}
                }
                Text("Sample data only. No account or server connection is needed.",style=MaterialTheme.typography.bodySmall)
            }
            onChangeServer?.let {BrandTextButton("Change server",it,!state.busy)}
        }
    }
}
