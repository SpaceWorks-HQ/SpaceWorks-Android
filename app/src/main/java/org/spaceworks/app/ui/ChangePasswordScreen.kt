package org.spaceworks.app.ui

import org.spaceworks.app.platform.*

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.auth.AuthBundle

@Composable fun ChangePasswordScreen(bundle:AuthBundle) {
    val copyText=rememberMobileText()

    val state by bundle.holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var current by remember {mutableStateOf("")}
    var replacement by remember {mutableStateOf("")}
    var confirmation by remember {mutableStateOf("")}
    ProtectSecrets(!bundle.demo)
    AuthLayout(copyText(UiStrings.MOBILE_CHOOSE_A_NEW_PASSWORD),copyText(UiStrings.MOBILE_REPLACE_YOUR_TEMPORARY_PASSWORD_TO_CONTINUE)) {
        BrandField(current,{current=it},copyText(UiStrings.MOBILE_CURRENT_PASSWORD),enabled=!state.busy,secret=true)
        BrandField(replacement,{replacement=it},copyText(UiStrings.MOBILE_NEW_PASSWORD),enabled=!state.busy,secret=true,role=FieldRole.NEW_PASSWORD)
        BrandField(confirmation,{confirmation=it},copyText(UiStrings.MOBILE_CONFIRM_PASSWORD),enabled=!state.busy,secret=true,role=FieldRole.NEW_PASSWORD)
        BrandError(state.error)
        BrandButton(copyText(UiStrings.MOBILE_UPDATE_PASSWORD),{scope.launch {bundle.holder.changePassword(current,replacement,confirmation);if(bundle.holder.state.value.user==null){current="";replacement="";confirmation=""}}},!state.busy)
        BrandTextButton(copyText(UiStrings.MOBILE_SIGN_OUT),{scope.launch {bundle.holder.signOut()}},!state.busy)
    }
}
