package org.spaceworks.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.spaceworks.app.auth.*
import org.spaceworks.app.theme.*
import org.spaceworks.app.ui.*

class MainActivity:ComponentActivity() {
    private val controller:AppController by viewModels()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {SpaceWorksApp(controller.connection,controller::realSession,controller::demoSession,controller::clearDemo)}
    }
}
@Composable fun SpaceWorksApp(holder:ConnectionStateHolder,sessionFactory:((String)->AuthBundle)?=null,
    demoFactory:()->AuthBundle?={DemoSupport.create()},onExitDemo:()->Unit={}) {
    var dark by rememberSaveable {mutableStateOf(false)}
    var demo by rememberSaveable {mutableStateOf(false)}
    val connection by holder.state.collectAsStateWithLifecycle()
    val bundle=remember(connection.connected,connection.url,demo) {
        if(demo)demoFactory() else if(connection.connected)sessionFactory?.invoke(connection.url) else null
    }
    MaterialTheme(colorScheme=if(dark)DarkScheme else LightScheme,
        shapes=Shapes(small=androidx.compose.foundation.shape.RoundedCornerShape(10.dp),medium=androidx.compose.foundation.shape.RoundedCornerShape(16.dp))) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.app_name),Modifier.weight(1f).padding(vertical=16.dp),style=MaterialTheme.typography.titleLarge)
                    BrandTextButton(if(dark)"Light mode" else "Dark mode",{dark=!dark})
                }
                if(demo) Surface(color=MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("Demo mode · sample data",Modifier.weight(1f).padding(vertical=16.dp),style=MaterialTheme.typography.labelLarge)
                        BrandTextButton("Exit demo",{demo=false;onExitDemo()})
                    }
                }
                LaunchedEffect(holder) {if(holder.state.value.url.isNotBlank() && !demo)holder.connect()}
                when {
                    !demo && !connection.connected -> ServerScreen(holder,connection,onDemo=if(BuildConfig.DEBUG){{demo=true}}else null)
                    bundle!=null -> SessionContent(bundle,if(!holder.locked && !demo){{holder.changeServer()}} else null)
                    else -> Text("Sign-in is not configured for this build.",Modifier.padding(24.dp))
                }
            }
        }
    }
}
@Composable private fun SessionContent(bundle:AuthBundle,onChangeServer:(()->Unit)?) {
    val state by bundle.holder.state.collectAsStateWithLifecycle()
    val lifecycle=LocalLifecycleOwner.current
    LaunchedEffect(bundle) {if(!state.initialized)bundle.holder.initialize()}
    LaunchedEffect(lifecycle,bundle) {lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {bundle.holder.foreground();kotlinx.coroutines.awaitCancellation()}}
    when {
        state.user==null -> AuthScreen(bundle,onChangeServer)
        state.selected==null -> MakerspacePicker(bundle)
        else -> MemberShell(bundle,onChangeServer)
    }
}
