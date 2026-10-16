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
import kotlinx.coroutines.launch
import org.spaceworks.app.auth.*
import org.spaceworks.app.theme.*
import org.spaceworks.app.ui.*

class MainActivity:ComponentActivity() {
    private val controller:AppController by viewModels()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {SpaceWorksApp(controller.connection,controller::realSession,controller::demoSession,controller::clearDemo,controller.onboarding,controller.appearance)}
    }
}
@Composable fun SpaceWorksApp(holder:ConnectionStateHolder,sessionFactory:((String)->AuthBundle)?=null,
    demoFactory:()->AuthBundle?={DemoSupport.create()},onExitDemo:()->Unit={},onboarding:OnboardingStateHolder?=null,appearance:AppearanceStateHolder?=null) {
    var localDark by rememberSaveable {mutableStateOf(false)}
    val dark=appearance?.dark?.collectAsStateWithLifecycle()?.value ?: localDark
    var demo by rememberSaveable {mutableStateOf(false)}
    val connection by holder.state.collectAsStateWithLifecycle()
    val introComplete=onboarding?.completed?.collectAsStateWithLifecycle()?.value ?: true
    val bundle=remember(connection.connected,connection.url,demo) {
        if(demo)demoFactory() else if(connection.connected)sessionFactory?.invoke(connection.url) else null
    }
    MaterialTheme(colorScheme=if(dark)DarkScheme else LightScheme,
        shapes=Shapes(small=androidx.compose.foundation.shape.RoundedCornerShape(10.dp),medium=androidx.compose.foundation.shape.RoundedCornerShape(16.dp))) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.app_name),Modifier.weight(1f).padding(vertical=16.dp),style=MaterialTheme.typography.titleLarge)
                    BrandTextButton(if(dark)"Light mode" else "Dark mode",{localDark=!dark;appearance?.setDark(localDark)})
                }
                if(demo) Surface(color=MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("Demo mode · sample data",Modifier.weight(1f).padding(vertical=16.dp),style=MaterialTheme.typography.labelLarge)
                        BrandTextButton("Exit demo",{demo=false;onExitDemo()})
                    }
                }
                LaunchedEffect(holder,introComplete) {if(introComplete && holder.state.value.url.isNotBlank() && !demo)holder.connect()}
                when {
                    !introComplete -> OnboardingScreen({onboarding?.finish()},if(BuildConfig.DEBUG){{onboarding?.finish();demo=true}}else null)
                    !demo && !connection.connected -> ServerScreen(holder,connection,onDemo=if(BuildConfig.DEBUG){{demo=true}}else null)
                    bundle!=null -> SessionContent(bundle,if(!holder.locked && !demo){{holder.changeServer()}} else null,onboarding?.let {{it.replay()}})
                    else -> Text("Sign-in is not configured for this build.",Modifier.padding(24.dp))
                }
            }
        }
    }
}
@Composable private fun SessionContent(bundle:AuthBundle,onChangeServer:(()->Unit)?,onShowIntro:(()->Unit)?) {
    val state by bundle.holder.state.collectAsStateWithLifecycle()
    val account=bundle.accounts?.state?.collectAsStateWithLifecycle()?.value
    val preview=bundle.previewAuth?.state?.collectAsStateWithLifecycle()?.value
    val scope=rememberCoroutineScope()
    val lifecycle=LocalLifecycleOwner.current
    LaunchedEffect(bundle) {if(!state.initialized)bundle.holder.initialize()}
    LaunchedEffect(lifecycle,bundle) {lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {bundle.holder.foreground();kotlinx.coroutines.awaitCancellation()}}
    when {
        bundle.demo && preview?.page!=null -> PreviewAuthScreen(requireNotNull(bundle.previewAuth))
        account?.route!=null -> AccountFlowScreen(requireNotNull(bundle.accounts),bundle.demo)
        state.user==null -> AuthScreen(bundle,onChangeServer?.let {{scope.launch {bundle.holder.signOut();it()}}})
        state.user?.mustChangePassword==true -> ChangePasswordScreen(bundle)
        state.selected==null -> MakerspacePicker(bundle)
        else -> MemberShell(bundle,onChangeServer,onShowIntro)
    }
}
