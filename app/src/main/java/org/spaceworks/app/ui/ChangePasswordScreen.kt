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
    AuthLayout("Choose a new password","Update your temporary password before opening your makerspace.") {
        BrandField(current,{current=it},"Current password",enabled=!state.busy,secret=true)
        BrandField(replacement,{replacement=it},"New password",enabled=!state.busy,secret=true)
        BrandField(confirmation,{confirmation=it},"Confirm new password",enabled=!state.busy,secret=true)
        BrandError(state.error)
        BrandButton("Update password",{scope.launch {bundle.holder.changePassword(current,replacement,confirmation);current="";replacement="";confirmation=""}},!state.busy)
        BrandTextButton("Sign out",{scope.launch {bundle.holder.signOut()}},!state.busy)
    }
}
