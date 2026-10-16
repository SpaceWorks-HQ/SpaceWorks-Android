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
import org.spaceworks.app.notifications.*
import org.spaceworks.app.member.MemberScope

fun makerspaceLabel(slug:String)=slug.split('-').joinToString(" "){it.replaceFirstChar(Char::uppercase)}

@Composable fun MakerspacePicker(bundle:AuthBundle) {
    val state by bundle.holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var candidate by remember(state.memberships.map {it.id}) {mutableStateOf<Int?>(null)}
    var signingOut by remember {mutableStateOf(false)}
    if(signingOut)SignOutConfirmation({signingOut=false},{signingOut=false;scope.launch {bundle.holder.signOut()}})
    AuthLayout(if(state.memberships.isEmpty())"No makerspace access yet" else "Choose your makerspace",if(state.memberships.isEmpty())"Ask your makerspace to add or reactivate your membership." else "Where would you like to make today?") {
        state.memberships.forEach {membership ->
            val picked=candidate==membership.id
            OutlinedCard(onClick={candidate=membership.id},modifier=Modifier.fillMaxWidth().heightIn(min=72.dp).semantics {selected=picked;role=Role.RadioButton}) {
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
    val actions=bundle.actions
    val actionState=actions?.state?.collectAsStateWithLifecycle()?.value
    val member=bundle.member
    val content=member?.state?.collectAsStateWithLifecycle()?.value
    val links=bundle.links?.state?.collectAsStateWithLifecycle()?.value
    var readyScope by remember(bundle){mutableStateOf<MemberScope?>(null)}
    var navigationError by remember(bundle){mutableStateOf<String?>(null)}
    var homeExtra by remember(state.selectedId){mutableStateOf<Pair<Long,String>?>(null)}
    var inboxNavigationSerial by remember(state.selectedId){mutableLongStateOf(0)}
    fun navigate(index:Int){destination=index;homeExtra=null;if(index==3)inboxNavigationSerial++}
    LaunchedEffect(links?.delivered?.sequence,readyScope,actionState?.scope) {
        val route=links?.delivered?:return@LaunchedEffect
        val selected=state.selected?:return@LaunchedEffect
        val expected=MemberScope(selected.id,selected.slug)
        if(readyScope!=expected || actionState?.scope!=expected || member==null || actions==null || route.destination.makerspaceId!=expected.id)return@LaunchedEffect
        fun current()=bundle.links?.state?.value?.delivered?.sequence==route.sequence && bundle.holder.state.value.selectedId==expected.id && bundle.holder.state.value.user!=null
        val applier=MemberRouteApplier(member,actions,{navigate(it)},{homeExtra=route.sequence to it;destination=0})
        val error=applier.open(route,expected,::current)
        if(current()) {
            navigationError=error
            if(error?.startsWith("Finish your current action")!=true)bundle.links?.consumed(route.sequence)
        }
    }
    var clock by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(content?.retryAtMillis) {
        do {clock=System.currentTimeMillis();if(clock<(content?.retryAtMillis?:0))kotlinx.coroutines.delay(250)}while(clock<(content?.retryAtMillis?:0))
    }
    LaunchedEffect(actions,state.selectedId) {state.selected?.let {actions?.activate(org.spaceworks.app.member.MemberScope(it.id,it.slug));actions?.refreshCapabilities()}}
    LaunchedEffect(member,state.selectedId) {
        readyScope=null
        state.selected?.let {selected->
            val expected=MemberScope(selected.id,selected.slug)
            member?.activate(expected)
            if(bundle.holder.state.value.selectedId==expected.id)readyScope=expected
        }
    }
    if(signingOut || changingServer)SignOutConfirmation({signingOut=false;changingServer=false},{
        val change=changingServer;signingOut=false;changingServer=false
        scope.launch {member?.clear();actions?.clear();bundle.holder.signOut();if(change)onChangeServer?.invoke()}
    },changingServer)
    CompositionLocalProvider(LocalMemberActions provides actions,LocalMemberActionScope provides scope) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded=maxWidth>=600.dp
        Row {
            if(expanded && actionState?.route==null) NavigationRail(Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
                destinations.forEachIndexed {index,title -> NavigationRailItem(selected=destination==index,onClick={navigate(index)},icon={Icon(destinationIcons[index],null)},label={Text(title)},modifier=Modifier.heightIn(min=if(LocalDensity.current.fontScale>=1.6f)120.dp else 80.dp),colors=NavigationRailItemDefaults.colors(selectedIconColor=MaterialTheme.colorScheme.onPrimaryContainer,selectedTextColor=MaterialTheme.colorScheme.onPrimaryContainer,indicatorColor=MaterialTheme.colorScheme.primaryContainer)) }
            }
            Column(Modifier.weight(1f)) {
                navigationError?.let {message->Column(Modifier.padding(16.dp)) {
                    BrandError(message)
                    if(links?.delivered!=null) {
                        BrandTextButton("Open pending update",{scope.launch {
                            val pending=bundle.links?.state?.value?.delivered?:return@launch
                            val expected=readyScope?:return@launch
                            if(member!=null && actions!=null) {
                                val applier=MemberRouteApplier(member,actions,{navigate(it)},{homeExtra=pending.sequence to it;destination=0})
                                navigationError=applier.open(pending,expected){bundle.links?.state?.value?.delivered?.sequence==pending.sequence && bundle.holder.state.value.selectedId==expected.id}
                                if(navigationError?.startsWith("Finish your current action")!=true)bundle.links?.consumed(pending.sequence)
                            }
                        }},actionState?.route==null && actionState?.busy!=true)
                        BrandTextButton("Dismiss update",{links?.delivered?.let {bundle.links?.consumed(it.sequence)};navigationError=null})
                    }
                }}
                if(clock<(content?.retryAtMillis?:0))Text("Try again in ${((requireNotNull(content).retryAtMillis-clock+999)/1000).coerceAtLeast(0)}s.",Modifier.padding(16.dp),color=MaterialTheme.colorScheme.onPrimaryContainer)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        actionState?.route!=null && actionState.scope?.id!=state.selectedId -> Text("Loading your makerspace…",Modifier.padding(24.dp))
                        actionState?.route!=null && actions!=null -> MemberActionsScreen(actions,bundle.demo,{})
                        destination==4 -> Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.TopCenter) {
                            Column(Modifier.widthIn(max=640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                                Text("Profile",style=MaterialTheme.typography.headlineLarge)
                                Text(state.user?.displayName.orEmpty(),style=MaterialTheme.typography.titleLarge)
                                Text(state.user?.email.orEmpty())
                                Text("${state.selected?.roleName.orEmpty()} · ${makerspaceLabel(state.selected?.slug.orEmpty())}")
                                if(content?.scope?.id==state.selectedId && member!=null)MemberProfileSummary(member)
                                state.memberships.filter {it.id!=state.selectedId}.forEach {membership ->
                                    BrandTextButton("Switch to ${makerspaceLabel(membership.slug)}",{bundle.links?.clear();bundle.holder.select(membership.id)},!state.busy)
                                }
                                actions?.takeIf {actionState?.scope?.id==state.selectedId}?.let {action->
                                    if(actionState?.capabilities?.value?.selfService==true)BrandTextButton("Edit profile",{scope.launch {action.openProfile()}})
                                    if(actionState?.capabilities?.value?.memberPreferences==true)BrandTextButton("Communication preferences",{scope.launch {action.openPreferences()}})
                                    if(actionState?.capabilities?.loading==true)LinearProgressIndicator(Modifier.fillMaxWidth())
                                    actionState?.capabilities?.error?.let {BrandError(it);BrandTextButton("Retry makerspace settings",{scope.launch {action.refreshCapabilities()}})}
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
                        destination==0 -> MemberHome(member,state.user?.displayName.orEmpty().ifBlank {state.user?.username.orEmpty()},{navigate(1)},bundle.demo,homeExtra)
                        destination==1 -> MemberInventory(member)
                        destination==2 -> MemberActivityScreen(member)
                        else -> MemberInboxScreen(member,notifications={bundle.push?.let {push->NotificationsPanel(push,bundle.demo,bundle.pushConfigured,state.memberships.associate {it.id to makerspaceLabel(it.slug)},bundle.pushPreview){hint->bundle.links?.offer(hint)}}},onOpenUpdate={bundle.links?.offer(it)},navigationSequence=inboxNavigationSerial)
                    }
                }
                state.error?.let {Box(Modifier.padding(horizontal=24.dp)){BrandError(it)}}
                if(!expanded && actionState?.route==null) NavigationBar(Modifier.heightIn(min=if(LocalDensity.current.fontScale>=1.6f)112.dp else 80.dp)) {
                    destinations.forEachIndexed {index,title -> NavigationBarItem(selected=destination==index,onClick={navigate(index)},icon={Icon(destinationIcons[index],null)},label={Text(title)},colors=NavigationBarItemDefaults.colors(selectedIconColor=MaterialTheme.colorScheme.onPrimaryContainer,selectedTextColor=MaterialTheme.colorScheme.onPrimaryContainer,indicatorColor=MaterialTheme.colorScheme.primaryContainer)) }
                }
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
