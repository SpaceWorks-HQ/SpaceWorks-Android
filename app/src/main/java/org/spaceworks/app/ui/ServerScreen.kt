package org.spaceworks.app.ui

import org.spaceworks.app.platform.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.launch
import org.spaceworks.app.*

@Composable fun ServerScreen(holder:ConnectionStateHolder,state:ConnectionState,onDemo:(()->Unit)?=null) {
    val copyText=rememberMobileText()

    val scope=rememberCoroutineScope()
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val connect:()->Unit={focus.clearFocus();keyboard?.hide();scope.launch {holder.connect()}}
    AuthLayout(copyText(UiStrings.MOBILE_CONNECT_TO_YOUR_MAKERSPACE),copyText(UiStrings.MOBILE_ENTER_THE_SERVER_ADDRESS_PROVIDED_BY_YOUR_MAKERSPACE)) {
        BrandField(state.url,holder::edit,copyText(UiStrings.MOBILE_SERVER_ADDRESS),Modifier.testTag("serverUrl"),!state.connecting,readOnly=holder.locked,role=FieldRole.SERVER,onDone={if(!state.connecting)connect()})
        state.error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("connectionError").semantics {liveRegion=LiveRegionMode.Polite})}
        BrandButton(if(state.connecting)copyText(UiStrings.MOBILE_CONNECTING) else copyText(UiStrings.MOBILE_CONNECT),connect,!state.connecting)
        onDemo?.let {BrandTextButton(copyText(UiStrings.MOBILE_EXPLORE_DEMO),it)}
    }
}
