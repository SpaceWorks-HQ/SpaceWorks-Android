package org.spaceworks.app.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import org.spaceworks.app.*
import org.spaceworks.app.R
import org.spaceworks.app.theme.BrandMotion

@Composable fun rememberReducedMotion():Boolean {
    val context=LocalContext.current
    fun read()=Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f
    var reduced by remember(context){mutableStateOf(read())}
    DisposableEffect(context) {
        val observer=object:ContentObserver(Handler(Looper.getMainLooper())){override fun onChange(selfChange:Boolean){reduced=read()}}
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer)
        onDispose {context.contentResolver.unregisterContentObserver(observer)}
    }
    return reduced
}
private data class IntroPage(val title:String,val body:String,val action:String)
private val introPages=listOf(
    IntroPage("Make room.","Find tools, book a space, and see what's next at your makerspace.","Find the right tools"),
    IntroPage("Borrow with confidence.","Request what you need and keep an eye on your loans and due dates.","Keep everything together"),
    IntroPage("One place for your making.","Keep bookings, event tickets, payments, and updates together.","Connect your makerspace")
)
@Composable fun OnboardingScreen(onFinish:()->Unit,onDemo:(()->Unit)?=null) {
    var step by rememberSaveable {mutableIntStateOf(0)}
    val reduced=rememberReducedMotion()
    val easing=CubicBezierEasing(BrandMotion.easing[0],BrandMotion.easing[1],BrandMotion.easing[2],BrandMotion.easing[3])
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
        Column(Modifier.widthIn(max=620.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                // The master logo stays static. Only the surrounding content below transitions.
                BrandLogo(compact=true)
                BrandTextButton("Skip introduction",onFinish)
            }
            Text("Step ${step+1} of 3",style=MaterialTheme.typography.labelLarge,modifier=Modifier.semantics {stateDescription="Step ${step+1} of 3"})
            AnimatedContent(targetState=step,label="Introduction panel",transitionSpec={
                if(reduced)EnterTransition.None togetherWith ExitTransition.None
                else (fadeIn(tween(BrandMotion.EXPAND,easing=easing))+slideInVertically(tween(BrandMotion.EXPAND,easing=easing)){12}) togetherWith fadeOut(tween(BrandMotion.OVERLAY_EXIT,easing=easing))
            }) {index ->
                val page=introPages[index]
                Column(verticalArrangement=Arrangement.spacedBy(24.dp)) {
                    Text(page.title,style=MaterialTheme.typography.headlineLarge,fontFamily=BrandAccentFont,modifier=Modifier.semantics {heading();liveRegion=LiveRegionMode.Polite})
                    Text(page.body,style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                            Icon(listOf(Icons.Outlined.Build,Icons.Outlined.DateRange,Icons.Outlined.CheckCircle)[index],null,Modifier.size(40.dp),tint=MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(page.action,style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(listOf("Search by name or category. Availability always comes from your makerspace.","One place for requests, bookings, events and important updates.","Your makerspace will share its server URL. You can change it later in Profile.")[index])
                        }
                    }
                }
            }
            BrandButton(if(step==2)"Get started" else "Next",{if(step==2)onFinish()else step++})
            if(step>0)BrandTextButton("Back",{step--})
            if(step==2)onDemo?.let {BrandTextButton("Explore demo",it)}
        }
    }
}
