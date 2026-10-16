package org.spaceworks.app.ui
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
    val scope=rememberCoroutineScope()
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val connect:()->Unit={focus.clearFocus();keyboard?.hide();scope.launch {holder.connect()}}
    AuthLayout("Connect to your makerspace","Enter the server address provided by your makerspace.") {
        BrandField(state.url,holder::edit,"Server address",Modifier.testTag("serverUrl"),!state.connecting,readOnly=holder.locked,role=FieldRole.SERVER,onDone={if(!state.connecting)connect()})
        state.error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("connectionError").semantics {liveRegion=LiveRegionMode.Polite})}
        BrandButton(if(state.connecting)"Connecting…" else "Connect",connect,!state.connecting)
        onDemo?.let {BrandTextButton("Explore demo",it)}
    }
}
