package org.spaceworks.app.ui

import org.spaceworks.app.platform.*

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import org.spaceworks.app.*
import org.spaceworks.app.R
import org.spaceworks.app.theme.BrandMotion

private data class IntroPage(val title:MobileCopyKey,val body:MobileCopyKey,val action:MobileCopyKey)
private val introPages=listOf(
    IntroPage(UiStrings.MOBILE_MAKE_ROOM,UiStrings.MOBILE_FIND_TOOLS_BOOK_A_SPACE_AND_SEE_WHAT_S_NEXT_AT_YOUR_MAKERSPACE,UiStrings.MOBILE_FIND_THE_RIGHT_TOOLS),
    IntroPage(UiStrings.MOBILE_BORROW_WITH_CONFIDENCE,UiStrings.MOBILE_REQUEST_WHAT_YOU_NEED_AND_KEEP_AN_EYE_ON_YOUR_LOANS_AND_DUE_DATE,UiStrings.MOBILE_KEEP_EVERYTHING_TOGETHER),
    IntroPage(UiStrings.MOBILE_ONE_PLACE_FOR_YOUR_MAKING,UiStrings.MOBILE_KEEP_BOOKINGS_EVENT_TICKETS_PAYMENTS_AND_UPDATES_TOGETHER,UiStrings.MOBILE_CONNECT_YOUR_MAKERSPACE)
)
@Composable fun OnboardingScreen(onFinish:()->Unit,onDemo:(()->Unit)?=null) {
    val copyText=rememberMobileText()

    var step by rememberSaveable {mutableIntStateOf(0)}
    val reduced=LocalReducedMotion.current
    val feedback=rememberActionFeedback(Unit)
    val slideOffset=with(LocalDensity.current){12.dp.roundToPx()}
    val easing=CubicBezierEasing(BrandMotion.easing[0],BrandMotion.easing[1],BrandMotion.easing[2],BrandMotion.easing[3])
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
        Column(Modifier.widthIn(max=620.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                // The master logo stays static. Only the surrounding content below transitions.
                BrandLogo(compact=true)
                BrandTextButton(copyText(UiStrings.MOBILE_SKIP_INTRODUCTION),{feedback.perform(FeedbackKind.SELECTION);onFinish()})
            }
            val stepDescription=copyText(UiStrings.MOBILE_STEP_VALUE_OF_3,step+1)
            Text(stepDescription,style=MaterialTheme.typography.labelLarge,modifier=Modifier.semantics {stateDescription=stepDescription})
            AnimatedContent(targetState=step,label=copyText(UiStrings.MOBILE_INTRODUCTION_PANEL),transitionSpec={
                if(reduced)EnterTransition.None togetherWith ExitTransition.None
                else (fadeIn(tween(BrandMotion.EXPAND,easing=easing))+slideInVertically(tween(BrandMotion.EXPAND,easing=easing)){slideOffset}) togetherWith fadeOut(tween(BrandMotion.OVERLAY_EXIT,easing=easing))
            }) {index ->
                val page=introPages[index]
                Column(verticalArrangement=Arrangement.spacedBy(24.dp)) {
                    Text(copyText(page.title),style=MaterialTheme.typography.headlineLarge,fontFamily=BrandAccentFont,modifier=Modifier.semantics {heading();liveRegion=LiveRegionMode.Polite})
                    Text(copyText(page.body),style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                            Icon(listOf(Icons.Outlined.Build,Icons.Outlined.DateRange,Icons.Outlined.CheckCircle)[index],null,Modifier.size(40.dp),tint=MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(copyText(page.action),style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(listOf(copyText(UiStrings.MOBILE_SEARCH_BY_NAME_OR_CATEGORY_AVAILABILITY_ALWAYS_COMES_FROM_YOUR_M),copyText(UiStrings.MOBILE_ONE_PLACE_FOR_REQUESTS_BOOKINGS_EVENTS_AND_IMPORTANT_UPDATES),copyText(UiStrings.MOBILE_YOUR_MAKERSPACE_WILL_SHARE_ITS_SERVER_URL_YOU_CAN_CHANGE_IT_LATE))[index])
                        }
                    }
                }
            }
            BrandButton(if(step==2)copyText(UiStrings.MOBILE_GET_STARTED) else copyText(UiStrings.MOBILE_NEXT),{if(step==2){feedback.perform(FeedbackKind.CONFIRM);onFinish()}else{feedback.perform(FeedbackKind.SELECTION);step++}})
            if(step>0)BrandTextButton(copyText(UiStrings.MOBILE_BACK),{feedback.perform(FeedbackKind.SELECTION);step--})
            if(step==2)onDemo?.let {BrandTextButton(copyText(UiStrings.MOBILE_EXPLORE_DEMO),it)}
        }
    }
}
