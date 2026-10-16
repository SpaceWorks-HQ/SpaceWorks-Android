package org.spaceworks.app.ui

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.auth.AuthBundle

@Composable fun ChangePasswordScreen(bundle:AuthBundle) {
    val state by bundle.holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var current by remember {mutableStateOf("")}
    var replacement by remember {mutableStateOf("")}
    var confirmation by remember {mutableStateOf("")}
    ProtectSecrets(!bundle.demo)
    AuthLayout("Choose a new password","Replace your temporary password to continue.") {
        BrandField(current,{current=it},"Current password",enabled=!state.busy,secret=true)
        BrandField(replacement,{replacement=it},"New password",enabled=!state.busy,secret=true,role=FieldRole.NEW_PASSWORD)
        BrandField(confirmation,{confirmation=it},"Confirm password",enabled=!state.busy,secret=true,role=FieldRole.NEW_PASSWORD)
        BrandError(state.error)
        BrandButton("Update password",{scope.launch {bundle.holder.changePassword(current,replacement,confirmation);if(bundle.holder.state.value.user==null){current="";replacement="";confirmation=""}}},!state.busy)
        BrandTextButton("Sign out",{scope.launch {bundle.holder.signOut()}},!state.busy)
    }
}
