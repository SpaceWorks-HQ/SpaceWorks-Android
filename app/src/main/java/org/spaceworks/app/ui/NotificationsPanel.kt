package org.spaceworks.app.ui

import org.spaceworks.app.network.retrySecondsRemaining

import org.spaceworks.app.platform.*

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.notifications.PushStateHolder
import org.spaceworks.app.platform.*

/** Setup is an explicit task, independent of server communication preferences. */
@Composable fun NotificationsPanel(holder:PushStateHolder,demo:Boolean,configured:Boolean,
    memberships:Map<Int,String>,preview:org.spaceworks.app.notifications.PushPreviewControl?=null,onPreview:(PushHint)->Unit) {
    val copyText=rememberMobileText()

    val state by holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    var clock by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(state.retryAtMillis) {do {clock=System.currentTimeMillis();if(clock<state.retryAtMillis)kotlinx.coroutines.delay(250)}while(clock<state.retryAtMillis)}
    var previews by remember {mutableStateOf(false)}
    val ready=!state.busy && clock>=state.retryAtMillis
    val request=rememberNotificationPermissionRequest {allowed->scope.launch {holder.enable(allowed)}}
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(copyText(UiStrings.MOBILE_MAKERSPACE_NOTIFICATIONS),style=MaterialTheme.typography.titleMedium)
            Text(if(demo)copyText(UiStrings.MOBILE_DEMO_ONLY_CHANGES_STAY_IN_THIS_PREVIEW_NO_NOTIFICATIONS_WILL_BE) else copyText(UiStrings.MOBILE_GET_UPDATES_ABOUT_YOUR_REQUESTS_BOOKINGS_AND_MAKERSPACE_ACTIVITY))
            when {
                !demo && !configured -> Text(copyText(UiStrings.MOBILE_NOTIFICATIONS_ARE_NOT_CONFIGURED_FOR_THIS_BUILD))
                state.consent && !state.permission -> Text(copyText(UiStrings.MOBILE_NOTIFICATIONS_ARE_BLOCKED_IN_SYSTEM_SETTINGS_INBOX_IS_STILL_AVAI))
                state.registered.isNotEmpty() -> {
                    Text(copyText(UiStrings.MOBILE_NOTIFICATIONS_ARE_ON_FOR))
                    state.registered.sorted().forEach {Text(memberships[it]?:copyText(UiStrings.MOBILE_MAKERSPACE_VALUE,it))}
                }
                state.consent -> Text(copyText(UiStrings.MOBILE_NOTIFICATION_SETUP_IS_WAITING_TO_FINISH))
                else -> Text(copyText(UiStrings.MOBILE_NOTIFICATIONS_ARE_OFF_ON_THIS_DEVICE))
            }
            if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            BrandError(state.error)
            if(clock<state.retryAtMillis)Text(copyText(UiStrings.MOBILE_TRY_AGAIN_IN_VALUES,retrySecondsRemaining(clock,state.retryAtMillis)))
            if(demo || configured) {
                BrandButton(when { !state.consent -> copyText(UiStrings.MOBILE_ENABLE_NOTIFICATIONS); state.error!=null -> copyText(UiStrings.MOBILE_RETRY_NOTIFICATION_SETUP); else -> copyText(UiStrings.MOBILE_REFRESH_NOTIFICATION_SETUP) },{
                    if(demo)scope.launch {holder.enable(true)}else request()
                },ready)
                if(state.consent || state.registered.isNotEmpty())BrandTextButton(copyText(UiStrings.MOBILE_TURN_OFF_NOTIFICATIONS),{scope.launch {holder.disable()}},!state.busy)
                if(!demo && !state.permission)BrandTextButton(copyText(UiStrings.MOBILE_OPEN_NOTIFICATION_SETTINGS),{
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))
                },!state.busy)
            }
            if(demo) {
                BrandTextButton(if(previews)copyText(UiStrings.MOBILE_HIDE_NOTIFICATION_PREVIEWS) else copyText(UiStrings.MOBILE_PREVIEW_NOTIFICATION_SCENARIOS),{previews=!previews})
                if(previews) {
                BrandTextButton(copyText(UiStrings.MOBILE_PREVIEW_DENIED_PERMISSION),{scope.launch {holder.enable(false)}},!state.busy)
                preview?.let {control->
                    fun simulate(value:org.spaceworks.app.notifications.PushPreviewScenario){scope.launch {
                        control.scenario(org.spaceworks.app.notifications.PushPreviewScenario.NORMAL);holder.disable()
                        control.scenario(value);holder.enable(true)
                    }}
                    BrandTextButton(copyText(UiStrings.MOBILE_PREVIEW_NOTIFICATION_SERVICE_UNAVAILABLE),{simulate(org.spaceworks.app.notifications.PushPreviewScenario.UNAVAILABLE)},ready)
                    BrandTextButton(copyText(UiStrings.MOBILE_PREVIEW_NOTIFICATION_RATE_LIMIT),{simulate(org.spaceworks.app.notifications.PushPreviewScenario.THROTTLED)},ready)
                    BrandTextButton(copyText(UiStrings.MOBILE_RESTORE_NOTIFICATION_SERVICE),{control.scenario(org.spaceworks.app.notifications.PushPreviewScenario.NORMAL);scope.launch {holder.enable(true)}},ready)
                }
                Text(copyText(UiStrings.MOBILE_NOTIFICATION_TAP_PREVIEWS),style=MaterialTheme.typography.labelLarge)
                BrandTextButton(copyText(UiStrings.MOBILE_PREVIEW_ANOTHER_MAKERSPACE_UPDATE),{onPreview(PushHint(2,"hardware","changed",null,null,"/makerspaces/2/inventory/201",2))},!state.busy)
                BrandTextButton(copyText(UiStrings.MOBILE_PREVIEW_LEGACY_UPDATE),{onPreview(PushHint(1,"hardware","changed",null,null,null,1))},!state.busy)
                BrandTextButton(copyText(UiStrings.MOBILE_PREVIEW_UNKNOWN_UPDATE_VERSION),{onPreview(PushHint(1,null,null,null,null,null,99))},!state.busy)
                BrandTextButton(copyText(UiStrings.MOBILE_PREVIEW_UNAVAILABLE_MAKERSPACE),{onPreview(PushHint(999,null,null,null,null,"/makerspaces/999/inbox",2))},!state.busy)
                }
            }
        }
    }
}
