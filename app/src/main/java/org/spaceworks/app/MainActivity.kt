package org.spaceworks.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import kotlinx.coroutines.launch
import org.spaceworks.app.network.ApiSession
import org.spaceworks.app.theme.DarkScheme
import org.spaceworks.app.theme.LightScheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("deployment",MODE_PRIVATE)
        val fixed = BuildConfig.SERVER_URL
        val holder = ConnectionStateHolder(fixed.ifBlank { prefs.getString("server", "") ?: "" },fixed.isNotBlank(),BuildConfig.DEBUG,
            health = { ApiSession(it).checkHealth() }, save = { prefs.edit().putString("server",it).apply() })
        setContent { SpaceWorksApp(holder) }
    }
}
@Composable
fun SpaceWorksApp(holder: ConnectionStateHolder) {
    var dark by rememberSaveable { mutableStateOf(false) }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.app_name),Modifier.weight(1f).padding(vertical=16.dp),style=MaterialTheme.typography.titleLarge)
                    TextButton(onClick={dark=!dark},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.onPrimaryContainer)) { Text(if (dark) stringResource(R.string.light_mode) else stringResource(R.string.dark_mode)) }
                }
                val connection by holder.state.collectAsStateWithLifecycle()
                LaunchedEffect(holder) { if (holder.state.value.url.isNotBlank()) holder.connect() }
                if (!connection.connected) ServerScreen(holder,connection) else AppShell(holder)
            }
        }
    }
}
@Composable
private fun ServerScreen(holder: ConnectionStateHolder, state: ConnectionState) {
    val scope=rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.connect_title),style=MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.server_help))
        OutlinedTextField(value=state.url,onValueChange=holder::edit,label={Text(stringResource(R.string.server_url))},enabled=!state.connecting && !holder.locked,singleLine=true,colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=MaterialTheme.colorScheme.onPrimaryContainer,focusedLabelColor=MaterialTheme.colorScheme.onPrimaryContainer,cursorColor=MaterialTheme.colorScheme.onPrimaryContainer),modifier=Modifier.fillMaxWidth().testTag("serverUrl"))
        state.error?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("connectionError")) }
        Button(onClick={scope.launch {holder.connect()}},enabled=!state.connecting,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
            Text(stringResource(if(state.connecting) R.string.connecting else R.string.connect))
        }
    }
}
@Composable
private fun AppShell(holder: ConnectionStateHolder) {
    val nav=rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val selected=entry?.destination?.route ?: "Home"
    val tabs=listOf("Home","Inventory","Activity","Inbox","Profile")
    Column(Modifier.fillMaxSize()) {
        NavHost(navController=nav,startDestination="Home",modifier=Modifier.weight(1f)) {
            tabs.forEach { tab -> composable(tab) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
                    Text(tab,style=MaterialTheme.typography.headlineLarge)
                    if(tab=="Home") {
                        Text(stringResource(R.string.sign_in),style=MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.sign_in_placeholder))
                    }
                    Text(stringResource(R.string.empty))
                    if (tab=="Profile" && !holder.locked) TextButton(onClick=holder::changeServer,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.onPrimaryContainer)) { Text(stringResource(R.string.change_server)) }
                }
            } }
        }
        // Staff is deliberately absent until server actions and staff device sessions ship in A1/A6.
        NavigationBar {
            tabs.forEach { tab -> NavigationBarItem(selected=selected==tab,onClick={nav.navigate(tab) {launchSingleTop=true;popUpTo("Home") {saveState=true};restoreState=true}},
                icon={Icon(when(tab){"Home"->Icons.Default.Home;"Inventory"->Icons.Default.List;"Activity"->Icons.Default.DateRange;"Inbox"->Icons.Default.Email;else->Icons.Default.Person}, contentDescription=null)},label={Text(tab)}) }
        }
    }
}
