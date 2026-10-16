package org.spaceworks.app.ui

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
            Text("Makerspace notifications",style=MaterialTheme.typography.titleMedium)
            Text(if(demo)"Demo only. These controls use local fixtures. No device token or system permission is used." else "Get updates about your requests, bookings and makerspace activity. You can keep using Inbox without notifications.")
            when {
                !demo && !configured -> Text("Notifications are not configured for this build.")
                state.consent && !state.permission -> Text("Notifications are blocked in system settings. Inbox is still available.")
                state.registered.isNotEmpty() -> {
                    Text("Notifications are on for:")
                    state.registered.sorted().forEach {Text(memberships[it]?:"Makerspace $it")}
                }
                state.consent -> Text("Notification setup is waiting to finish.")
                else -> Text("Notifications are off on this device.")
            }
            if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            BrandError(state.error)
            if(clock<state.retryAtMillis)Text("Try again in ${((state.retryAtMillis-clock+999)/1000).coerceAtLeast(0)}s.")
            if(demo || configured) {
                BrandButton(if(state.consent)"Retry notification setup" else "Enable notifications",{
                    if(demo)scope.launch {holder.enable(true)}else request()
                },ready)
                if(state.consent || state.registered.isNotEmpty())BrandTextButton("Turn off notifications",{scope.launch {holder.disable()}},!state.busy)
                if(!demo && !state.permission)BrandTextButton("Open notification settings",{
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))
                },!state.busy)
            }
            if(demo) {
                BrandTextButton(if(previews)"Hide notification previews"else"Preview notification scenarios",{previews=!previews})
                if(previews) {
                BrandTextButton("Preview denied permission",{scope.launch {holder.enable(false)}},!state.busy)
                preview?.let {control->
                    fun simulate(value:org.spaceworks.app.notifications.PushPreviewScenario){scope.launch {
                        control.scenario(org.spaceworks.app.notifications.PushPreviewScenario.NORMAL);holder.disable()
                        control.scenario(value);holder.enable(true)
                    }}
                    BrandTextButton("Preview notification service unavailable",{simulate(org.spaceworks.app.notifications.PushPreviewScenario.UNAVAILABLE)},ready)
                    BrandTextButton("Preview notification rate limit",{simulate(org.spaceworks.app.notifications.PushPreviewScenario.THROTTLED)},ready)
                    BrandTextButton("Restore notification service",{control.scenario(org.spaceworks.app.notifications.PushPreviewScenario.NORMAL);scope.launch {holder.enable(true)}},ready)
                }
                Text("Notification tap previews",style=MaterialTheme.typography.labelLarge)
                BrandTextButton("Preview another makerspace update",{onPreview(PushHint(2,"hardware","changed",null,null,"/makerspaces/2/inventory/201",2))},!state.busy)
                BrandTextButton("Preview legacy update",{onPreview(PushHint(1,"hardware","changed",null,null,null,1))},!state.busy)
                BrandTextButton("Preview unknown update version",{onPreview(PushHint(1,null,null,null,null,null,99))},!state.busy)
                BrandTextButton("Preview unavailable makerspace",{onPreview(PushHint(999,null,null,null,null,"/makerspaces/999/inbox",2))},!state.busy)
                }
            }
        }
    }
}
