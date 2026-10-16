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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.auth.AuthBundle
import org.spaceworks.app.auth.AccountRoute

fun makerspaceLabel(slug:String)=slug.split('-').joinToString(" "){it.replaceFirstChar(Char::uppercase)}

@Composable fun MakerspacePicker(bundle:AuthBundle) {
    val state by bundle.holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var candidate by remember(state.memberships.map {it.id}) {mutableStateOf<Int?>(null)}
    var signingOut by remember {mutableStateOf(false)}
    if(signingOut)SignOutConfirmation({signingOut=false},{signingOut=false;scope.launch {bundle.holder.signOut()}})
    AuthLayout(if(state.memberships.isEmpty())"No makerspace access yet" else "Choose your makerspace",if(state.memberships.isEmpty())"Ask your makerspace to add or reactivate your membership." else "Where would you like to make today?") {
        state.memberships.forEach {membership ->
            OutlinedCard(onClick={candidate=membership.id},modifier=Modifier.fillMaxWidth().heightIn(min=72.dp).semantics {selected=candidate==membership.id;role=Role.RadioButton}) {
                Column(Modifier.padding(16.dp)) {
                    Text(makerspaceLabel(membership.slug),style=MaterialTheme.typography.titleMedium)
                    Text(membership.roleName,style=MaterialTheme.typography.bodyMedium)
                    if(candidate==membership.id)Text("Selected",style=MaterialTheme.typography.labelMedium)
                }
            }
        }
        candidate?.let {BrandButton("Continue",{bundle.holder.select(it)},!state.busy)}
        if(state.user?.emailVerified==false && state.config?.emailEnabled==true)bundle.accounts?.let {BrandTextButton("Confirm your email",{it.open(AccountRoute.VERIFY_EMAIL,state.user?.email.orEmpty())})}
        BrandError(state.error)
        BrandTextButton("Refresh access",{scope.launch {bundle.holder.foreground()}},!state.busy)
        if(state.memberships.isEmpty())BrandTextButton("Use another account",{signingOut=true},!state.busy)
        BrandTextButton("Sign out",{signingOut=true},!state.busy)
    }
}

private val destinations=listOf("Home","Inventory","Activity","Inbox","Profile")
private val destinationIcons=listOf(Icons.Outlined.Home,Icons.Outlined.Build,Icons.Outlined.DateRange,Icons.Outlined.Email,Icons.Outlined.Person)
@Composable fun MemberShell(bundle:AuthBundle,onChangeServer:(()->Unit)?,onShowIntro:(()->Unit)?=null) {
    val state by bundle.holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var destination by rememberSaveable(state.selectedId){mutableIntStateOf(0)}
    var signingOut by remember {mutableStateOf(false)}
    var changingServer by remember {mutableStateOf(false)}
    val member=bundle.member
    val content=member?.state?.collectAsStateWithLifecycle()?.value
    var clock by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(content?.retryAtMillis) {
        do {clock=System.currentTimeMillis();if(clock<(content?.retryAtMillis?:0))kotlinx.coroutines.delay(250)}while(clock<(content?.retryAtMillis?:0))
    }
    LaunchedEffect(member,state.selectedId) {state.selected?.let {member?.activate(org.spaceworks.app.member.MemberScope(it.id,it.slug))}}
    if(signingOut || changingServer)SignOutConfirmation({signingOut=false;changingServer=false},{
        val change=changingServer;signingOut=false;changingServer=false
        scope.launch {member?.clear();bundle.holder.signOut();if(change)onChangeServer?.invoke()}
    },changingServer)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded=maxWidth>=600.dp
        Row {
            if(expanded) NavigationRail(Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
                destinations.forEachIndexed {index,title -> NavigationRailItem(selected=destination==index,onClick={destination=index},icon={Icon(destinationIcons[index],null)},label={Text(title)},modifier=Modifier.heightIn(min=if(LocalDensity.current.fontScale>=1.6f)120.dp else 80.dp),colors=NavigationRailItemDefaults.colors(selectedIconColor=MaterialTheme.colorScheme.onPrimaryContainer,selectedTextColor=MaterialTheme.colorScheme.onPrimaryContainer,indicatorColor=MaterialTheme.colorScheme.primaryContainer)) }
            }
            Column(Modifier.weight(1f)) {
                if(clock<(content?.retryAtMillis?:0))Text("Try again in ${((requireNotNull(content).retryAtMillis-clock+999)/1000).coerceAtLeast(0)}s.",Modifier.padding(16.dp),color=MaterialTheme.colorScheme.onPrimaryContainer)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        destination==4 -> Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.TopCenter) {
                            Column(Modifier.widthIn(max=640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                                Text("Profile",style=MaterialTheme.typography.headlineLarge)
                                Text(state.user?.displayName.orEmpty(),style=MaterialTheme.typography.titleLarge)
                                Text(state.user?.email.orEmpty())
                                Text("${state.selected?.roleName.orEmpty()} · ${makerspaceLabel(state.selected?.slug.orEmpty())}")
                                if(content?.scope?.id==state.selectedId && member!=null)MemberProfileSummary(member)
                                state.memberships.filter {it.id!=state.selectedId}.forEach {membership ->
                                    BrandTextButton("Switch to ${makerspaceLabel(membership.slug)}",{bundle.holder.select(membership.id)},!state.busy)
                                }
                                BrandTextButton("Refresh account",{scope.launch {bundle.holder.foreground()}},!state.busy)
                                if(state.user?.emailVerified==false && state.config?.emailEnabled==true)bundle.accounts?.let {BrandTextButton("Confirm your email",{it.open(AccountRoute.VERIFY_EMAIL,state.user?.email.orEmpty())})}
                                onShowIntro?.let {BrandTextButton("Show introduction",it,!state.busy)}
                                BrandButton("Sign out",{signingOut=true},!state.busy)
                                onChangeServer?.let {BrandTextButton("Change server",{changingServer=true},!state.busy)}
                            }
                        }
                        member==null -> Text("Member information is unavailable for this build.",Modifier.padding(24.dp))
                        content?.scope?.id!=state.selectedId -> Column(Modifier.padding(24.dp)) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Loading your makerspace…")}
                        destination==0 -> MemberHome(member,state.user?.displayName.orEmpty().ifBlank {state.user?.username.orEmpty()},{destination=1},bundle.demo)
                        destination==1 -> MemberInventory(member)
                        destination==2 -> MemberActivityScreen(member)
                        else -> MemberInboxScreen(member)
                    }
                }
                state.error?.let {Box(Modifier.padding(horizontal=24.dp)){BrandError(it)}}
                if(!expanded) NavigationBar(Modifier.heightIn(min=if(LocalDensity.current.fontScale>=1.6f)112.dp else 80.dp)) {
                    destinations.forEachIndexed {index,title -> NavigationBarItem(selected=destination==index,onClick={destination=index},icon={Icon(destinationIcons[index],null)},label={Text(title)},colors=NavigationBarItemDefaults.colors(selectedIconColor=MaterialTheme.colorScheme.onPrimaryContainer,selectedTextColor=MaterialTheme.colorScheme.onPrimaryContainer,indicatorColor=MaterialTheme.colorScheme.primaryContainer)) }
                }
            }
        }
    }
}

@Composable private fun SignOutConfirmation(onDismiss:()->Unit,onConfirm:()->Unit,changeServer:Boolean=false) {
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(changeServer)"Change server?" else "Sign out?")},
        text={Text(if(changeServer)"Continuing signs out this account before choosing another server." else "You'll need to sign in again to use this account.")},
        confirmButton={TextButton(onClick=onConfirm,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text(if(changeServer)"Continue" else "Sign out")}},
        dismissButton={BrandTextButton("Cancel",onDismiss)})
}
