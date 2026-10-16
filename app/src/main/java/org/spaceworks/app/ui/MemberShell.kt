package org.spaceworks.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.auth.AuthBundle

@Composable fun MakerspacePicker(bundle:AuthBundle) {
    val state by bundle.holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    AuthLayout("Choose a makerspace",if(state.memberships.isEmpty())"You haven’t joined a makerspace yet." else "Where would you like to make today?") {
        state.memberships.forEach {membership ->
            OutlinedCard(onClick={bundle.holder.select(membership.id)},modifier=Modifier.fillMaxWidth().heightIn(min=72.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(membership.slug.replace('-',' ').replaceFirstChar {it.uppercase()},style=MaterialTheme.typography.titleMedium)
                    Text(membership.roleName,style=MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if(state.memberships.isEmpty())Text("Ask your makerspace team about joining. Your account is signed in, and you can check again after they add your membership.")
        BrandError(state.error)
        BrandTextButton("Refresh memberships",{scope.launch {bundle.holder.foreground()}},!state.busy)
        BrandTextButton("Sign out",{scope.launch {bundle.holder.signOut()}},!state.busy)
    }
}

private val destinations=listOf("Home","Inventory","Activity","Inbox","Profile")
private val destinationIcons=listOf(Icons.Outlined.Home,Icons.Outlined.Build,Icons.Outlined.DateRange,Icons.Outlined.Email,Icons.Outlined.Person)
@Composable fun MemberShell(bundle:AuthBundle,onChangeServer:(()->Unit)?) {
    val state by bundle.holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var destination by rememberSaveable(state.selectedId){mutableIntStateOf(0)}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded=maxWidth>=600.dp
        Row {
            if(expanded) NavigationRail(Modifier.fillMaxHeight()) {
                destinations.forEachIndexed {index,title -> NavigationRailItem(selected=destination==index,onClick={destination=index},icon={Icon(destinationIcons[index],null)},label={Text(title)}) }
            }
            Column(Modifier.weight(1f)) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text(destinations[destination],style=MaterialTheme.typography.headlineLarge)
                    Text(state.selected?.slug.orEmpty().replace('-',' '),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    when(destination) {
                        0 -> {
                            Text("Welcome, ${state.user?.displayName.orEmpty().ifBlank {state.user?.username.orEmpty()}}",style=MaterialTheme.typography.titleLarge)
                            Text("Your makerspace is ready when you are.")
                            OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text("Make something meaningful",style=MaterialTheme.typography.titleMedium)
                                Text("Find tools, plan a visit, and keep track of your activity here.")
                                BrandTextButton("Browse inventory",{destination=1})
                            }}
                        }
                        4 -> {
                            Text(state.user?.displayName.orEmpty(),style=MaterialTheme.typography.titleLarge)
                            Text(state.user?.email.orEmpty())
                            Text("${state.selected?.roleName.orEmpty()} · Device session",style=MaterialTheme.typography.bodyMedium)
                            if(state.memberships.size>1)state.memberships.filter {it.id!=state.selectedId}.forEach {membership ->
                                BrandTextButton("Switch to ${membership.slug.replace('-',' ')}",{bundle.holder.select(membership.id)})
                            }
                            BrandTextButton("Refresh account",{scope.launch {bundle.holder.foreground()}},!state.busy)
                            BrandButton("Sign out",{scope.launch {bundle.holder.signOut()}},!state.busy)
                            onChangeServer?.let {BrandTextButton("Change server",{scope.launch {bundle.holder.signOut();it()}},!state.busy)}
                        }
                        else -> Text("This feature will connect to your makerspace in the next app phase.")
                    }
                    BrandError(state.error)
                }
                if(!expanded) NavigationBar {
                    destinations.forEachIndexed {index,title -> NavigationBarItem(selected=destination==index,onClick={destination=index},icon={Icon(destinationIcons[index],null)},label={Text(title)}) }
                }
            }
        }
    }
}
