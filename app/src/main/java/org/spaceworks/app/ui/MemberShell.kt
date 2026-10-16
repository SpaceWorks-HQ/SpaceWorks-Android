package org.spaceworks.app.ui

import org.spaceworks.app.network.retrySecondsRemaining

import org.spaceworks.app.platform.*

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
import org.spaceworks.app.payments.*
import org.spaceworks.app.guidance.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext

fun makerspaceLabel(slug:String)=slug.split('-').joinToString(" "){it.replaceFirstChar(Char::uppercase)}

@Composable fun MakerspacePicker(bundle:AuthBundle,onAccount:(()->Unit)?=null) {
    val copyText=rememberMobileText()

    val state by bundle.holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var candidate by remember(state.memberships.map {it.id}) {mutableStateOf<Int?>(null)}
    var signingOut by remember {mutableStateOf(false)}
    if(signingOut)SignOutConfirmation({signingOut=false},{signingOut=false;scope.launch {bundle.holder.signOut()}})
    AuthLayout(if(state.memberships.isEmpty())copyText(UiStrings.MOBILE_NO_MAKERSPACE_ACCESS_YET) else copyText(UiStrings.MOBILE_CHOOSE_YOUR_MAKERSPACE),if(state.memberships.isEmpty())copyText(UiStrings.MOBILE_ASK_YOUR_MAKERSPACE_TO_ADD_OR_REACTIVATE_YOUR_MEMBERSHIP) else copyText(UiStrings.MOBILE_WHERE_WOULD_YOU_LIKE_TO_MAKE_TODAY)) {
        state.memberships.forEach {membership ->
            val picked=candidate==membership.id
            OutlinedCard(onClick={candidate=membership.id},modifier=Modifier.fillMaxWidth().heightIn(min=72.dp).semantics {selected=picked;role=Role.RadioButton}) {
                Column(Modifier.padding(16.dp)) {
                    Text(makerspaceLabel(membership.slug),style=MaterialTheme.typography.titleMedium)
                    Text(membership.roleName,style=MaterialTheme.typography.bodyMedium)
                    if(candidate==membership.id)Text(copyText(UiStrings.MOBILE_SELECTED),style=MaterialTheme.typography.labelMedium)
                }
            }
        }
        candidate?.let {BrandButton(copyText(UiStrings.MOBILE_CONTINUE),{bundle.holder.select(it)},!state.busy)}
        if(state.user?.emailVerified==false && state.config?.emailEnabled==true)bundle.accounts?.let {BrandTextButton(copyText(UiStrings.MOBILE_CONFIRM_YOUR_EMAIL),{it.open(AccountRoute.VERIFY_EMAIL,state.user?.email.orEmpty())})}
        onAccount?.let {BrandTextButton(androidx.compose.ui.res.stringResource(org.spaceworks.app.R.string.account_access),it,!state.busy,modifier=Modifier.semantics {testTag="account-entry"})}
        BrandError(state.error)
        BrandTextButton(copyText(UiStrings.MOBILE_REFRESH_ACCESS),{scope.launch {bundle.holder.foreground()}},!state.busy)
        if(state.memberships.isEmpty())BrandTextButton(copyText(UiStrings.MOBILE_USE_ANOTHER_ACCOUNT),{signingOut=true},!state.busy)
        BrandTextButton(copyText(UiStrings.MOBILE_SIGN_OUT),{signingOut=true},!state.busy)
    }
}

private val destinations=listOf(UiStrings.MOBILE_HOME,UiStrings.MOBILE_INVENTORY,UiStrings.MOBILE_ACTIVITY,UiStrings.MOBILE_INBOX,UiStrings.MOBILE_PROFILE)
private val destinationIcons=listOf(Icons.Outlined.Home,Icons.Outlined.Build,Icons.Outlined.DateRange,Icons.Outlined.Email,Icons.Outlined.Person)
@Composable fun MemberShell(bundle:AuthBundle,onChangeServer:(()->Unit)?,onShowIntro:(()->Unit)?=null,onAccount:(()->Unit)?=null,
    openStaff:Boolean=false,onStaffOpened:()->Unit={}) {
    val copyText=rememberMobileText()

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
    val staff=bundle.staff
    val staffState=staff?.state?.collectAsStateWithLifecycle()?.value
    val staffAvailable=bundle.staffAvailable()
    var showingGuide by remember(bundle,state.user?.id,state.selectedId){mutableStateOf(false)}
    val guideScope=state.user?.id?.let {user->state.selectedId?.let {maker->GuideScope(bundle.origin,user.toString(),maker)}}
    val guidance=guideScope?.let {rememberScopedGuidance(LocalContext.current,it,bundle.demo,bundle.previewGuidance)}
    BackHandler(showingGuide){showingGuide=false}
    var showingStaff by remember(state.selectedId){mutableStateOf(false)}
    LaunchedEffect(openStaff,staffAvailable) {if(openStaff && staffAvailable){showingStaff=true;onStaffOpened()}}
    val payments=bundle.payments
    val paymentState=payments?.state?.collectAsStateWithLifecycle()?.value
    var showingPayments by remember(state.selectedId){mutableStateOf(false)}
    var paymentRoute by remember(state.selectedId){mutableStateOf<Pair<Long,String?>?>(null)}
    val nativeCheckout=LocalNativeCheckout.current
    val nativeState=nativeCheckout?.state?.collectAsStateWithLifecycle()?.value?:NativePaymentState.Idle
    val owningEpoch=bundle.sessionEpoch()
    val owningUser=state.user?.id
    var readyScope by remember(bundle){mutableStateOf<MemberScope?>(null)}
    var navigationError by remember(bundle){mutableStateOf<String?>(null)}
    var homeExtra by remember(state.selectedId){mutableStateOf<Pair<Long,String>?>(null)}
    var inboxNavigationSerial by remember(state.selectedId){mutableLongStateOf(0)}
    fun navigate(index:Int){destination=index;homeExtra=null;if(index==3)inboxNavigationSerial++}
    LaunchedEffect(links?.delivered?.sequence,readyScope,actionState?.scope,showingStaff,showingGuide) {
        if(showingStaff || showingGuide)return@LaunchedEffect
        val route=links?.delivered?:return@LaunchedEffect
        val selected=state.selected?:return@LaunchedEffect
        val expected=MemberScope(selected.id,selected.slug)
        if(readyScope!=expected || actionState?.scope!=expected || member==null || actions==null || route.destination.makerspaceId!=expected.id)return@LaunchedEffect
        fun current()=bundle.links?.state?.value?.delivered?.sequence==route.sequence && bundle.holder.state.value.selectedId==expected.id && bundle.holder.state.value.user!=null
        val applier=MemberRouteApplier(member,actions,{navigate(it)},{homeExtra=route.sequence to it;destination=0},onPayments={paymentRoute=route.sequence to it;showingPayments=true},text=copyText)
        val error=applier.open(route,expected,::current)
        if(current()) {
            navigationError=error
            if(error==null || (!actions.state.value.busy && actions.state.value.route==null))bundle.links?.consumed(route.sequence)
        }
    }
    LaunchedEffect(staff,state.selectedId,staffAvailable) {
        if(staffAvailable)state.selected?.let {staff?.activate(MemberScope(it.id,it.slug))}else {staff?.clear();showingStaff=false}
    }
    LaunchedEffect(payments,state.selectedId) {state.selected?.let {payments?.activate(MemberScope(it.id,it.slug))}}
    LaunchedEffect(paymentRoute,paymentState?.scope) {
        val request=paymentRoute?:return@LaunchedEffect
        val selected=state.selected?:return@LaunchedEffect
        val expected=MemberScope(selected.id,selected.slug)
        if(paymentState?.scope!=expected || payments==null)return@LaunchedEffect
        val opened=payments.openFromRoute(request.second)
        if(paymentRoute==request && bundle.holder.state.value.selectedId==expected.id) {
            if(!opened)navigationError=copyText(PaymentStrings.PAYMENT_CHARGE_UNAVAILABLE)
            paymentRoute=null
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
        scope.launch {staff?.clear();member?.clear();actions?.clear();payments?.clear();bundle.holder.signOut();if(change)onChangeServer?.invoke()}
    },changingServer)
    CompositionLocalProvider(LocalMemberActions provides actions,LocalMemberActionScope provides scope) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded=maxWidth>=600.dp
        Row {
            if(expanded && actionState?.route==null && !showingPayments && !showingStaff && !showingGuide) NavigationRail(Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
                destinations.forEachIndexed {index,title -> NavigationRailItem(selected=destination==index,onClick={navigate(index)},icon={Icon(destinationIcons[index],null)},label={Text(copyText(title))},modifier=Modifier.heightIn(min=if(LocalDensity.current.fontScale>=1.6f)120.dp else 80.dp),colors=NavigationRailItemDefaults.colors(selectedIconColor=MaterialTheme.colorScheme.onPrimaryContainer,selectedTextColor=MaterialTheme.colorScheme.onPrimaryContainer,indicatorColor=MaterialTheme.colorScheme.primaryContainer)) }
            }
            Column(Modifier.weight(1f)) {
                navigationError?.let {message->Column(Modifier.padding(16.dp)) {
                    BrandError(message)
                    if(links?.delivered!=null) {
                        BrandTextButton(copyText(UiStrings.MOBILE_OPEN_PENDING_UPDATE),{scope.launch {
                            if(showingStaff || showingGuide || staff?.protectsNavigation==true)return@launch
                            val pending=bundle.links?.state?.value?.delivered?:return@launch
                            val expected=readyScope?:return@launch
                            if(member!=null && actions!=null) {
                                val applier=MemberRouteApplier(member,actions,{navigate(it)},{homeExtra=pending.sequence to it;destination=0},onPayments={paymentRoute=pending.sequence to it;showingPayments=true},text=copyText)
                                val result=applier.open(pending,expected){!showingStaff && !showingGuide && staff?.protectsNavigation!=true && bundle.links?.state?.value?.delivered?.sequence==pending.sequence && bundle.holder.state.value.selectedId==expected.id}
                                if(showingStaff || showingGuide || staff?.protectsNavigation==true)return@launch
                                navigationError=result
                                if(navigationError==null || (!actions.state.value.busy && actions.state.value.route==null))bundle.links?.consumed(pending.sequence)
                            }
                        }},!showingStaff && !showingGuide && staff?.protectsNavigation!=true && actionState?.route==null && actionState?.busy!=true)
                        BrandTextButton(copyText(UiStrings.MOBILE_DISMISS_UPDATE),{links?.delivered?.let {bundle.links?.consumed(it.sequence)};navigationError=null})
                    }
                }}
                if(clock<(content?.retryAtMillis?:0))Text(copyText(UiStrings.MOBILE_TRY_AGAIN_IN_VALUES,retrySecondsRemaining(clock,requireNotNull(content).retryAtMillis)),Modifier.padding(16.dp),color=MaterialTheme.colorScheme.onPrimaryContainer)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        showingGuide -> UsageGuideScreen(bundle.demo,staffAvailable,{showingGuide=false},
                            {showingGuide=false;onShowIntro?.invoke()},guidance?.let {{it.resetTips()}},footer={
                                Text(copyText(UsageStrings.USAGE_APP_VERSION,org.spaceworks.app.BuildConfig.VERSION_NAME))
                                if(!bundle.demo)Text(copyText(UsageStrings.USAGE_SERVER,bundle.origin))
                                val context=LocalContext.current
                                org.spaceworks.app.theme.BrandLinks.supportURL?.takeIf {org.spaceworks.app.account.AccountLinkPolicy.safe(it)}?.let {url->
                                    BrandTextButton(androidx.compose.ui.res.stringResource(org.spaceworks.app.R.string.account_support),{runCatching {context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(url)))}})}
                                org.spaceworks.app.theme.BrandLinks.privacyURL?.takeIf {org.spaceworks.app.account.AccountLinkPolicy.safe(it)}?.let {url->
                                    BrandTextButton(androidx.compose.ui.res.stringResource(org.spaceworks.app.R.string.account_privacy),{runCatching {context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(url)))}})}
                            })
                        showingStaff && staffAvailable && staff!=null && (staffState?.scope==null || staffState.scope?.id==state.selectedId) -> org.spaceworks.app.staff.StaffScreen(staff,bundle.demo,{staff.close();showingStaff=false},previewScope=state.selected?.let {MemberScope(it.id,it.slug)})
                        showingStaff -> Text(copyText(UiStrings.MOBILE_LOADING_YOUR_MAKERSPACE),Modifier.padding(24.dp))
                        showingPayments && paymentState?.scope?.id!=state.selectedId -> Text(copyText(UiStrings.MOBILE_LOADING_YOUR_MAKERSPACE),Modifier.padding(24.dp))
                        showingPayments && payments!=null -> PaymentsScreen(payments,bundle.demo,{showingPayments=false},nativeState,
                            launchStripe={input->
                                if(!bundle.demo && !bundle.holder.state.value.busy && bundle.sessionEpoch()==owningEpoch && bundle.holder.state.value.user?.id==owningUser &&
                                    bundle.holder.state.value.selectedId==paymentState?.scope?.id)
                                    nativeCheckout?.launchStripe?.invoke(input)?:PaymentLaunchResult.HOST_NOT_READY
                                else PaymentLaunchResult.HOST_NOT_READY
                            },acknowledgeNative={nativeCheckout?.acknowledge?.invoke()},recoverInterrupted={nativeCheckout?.recoverInterrupted?.invoke()},refreshOnEnter=paymentRoute==null,hostAvailable=!state.busy)
                        actionState?.route!=null && actionState.scope?.id!=state.selectedId -> Text(copyText(UiStrings.MOBILE_LOADING_YOUR_MAKERSPACE),Modifier.padding(24.dp))
                        actionState?.route!=null && actions!=null -> MemberActionsScreen(actions,bundle.demo,{})
                        destination==4 -> Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.TopCenter) {
                            Column(Modifier.widthIn(max=640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                                Text(copyText(UiStrings.MOBILE_PROFILE),style=MaterialTheme.typography.headlineLarge)
                                Text(state.user?.displayName.orEmpty(),style=MaterialTheme.typography.titleLarge)
                                Text(state.user?.email.orEmpty())
                                Text(copyText(UiStrings.MOBILE_VALUE_VALUE_C1AACD,state.selected?.roleName.orEmpty(),makerspaceLabel(state.selected?.slug.orEmpty())))
                                if(content?.scope?.id==state.selectedId && member!=null)MemberProfileSummary(member)
                                if(staff!=null && staffAvailable)BrandTextButton(copyText(org.spaceworks.app.staff.StaffStrings.STAFF_STAFF_TOOLS),{showingStaff=true},!state.busy,modifier=Modifier.semantics {testTag="staff-entry"})
                                onAccount?.let {BrandTextButton(androidx.compose.ui.res.stringResource(org.spaceworks.app.R.string.account_title),it,!state.busy,modifier=Modifier.semantics {testTag="account-entry"})}
                                if(payments!=null)BrandTextButton(copyText(PaymentStrings.PAYMENT_PAYMENTS),{showingPayments=true},!state.busy,modifier=Modifier.semantics {testTag="payments-entry"})
                                state.memberships.filter {it.id!=state.selectedId}.forEach {membership ->
                                    BrandTextButton(copyText(UiStrings.MOBILE_SWITCH_TO_VALUE,makerspaceLabel(membership.slug)),{bundle.links?.clear();bundle.holder.select(membership.id)},!state.busy)
                                }
                                actions?.takeIf {actionState?.scope?.id==state.selectedId}?.let {action->
                                    if(actionState?.capabilities?.value?.selfService==true)BrandTextButton(copyText(UiStrings.MOBILE_EDIT_PROFILE),{scope.launch {action.openProfile()}})
                                    if(actionState?.capabilities?.value?.memberPreferences==true)BrandTextButton(copyText(UiStrings.MOBILE_COMMUNICATION_PREFERENCES),{scope.launch {action.openPreferences()}})
                                    if(actionState?.capabilities?.loading==true)LinearProgressIndicator(Modifier.fillMaxWidth())
                                    actionState?.capabilities?.error?.let {BrandError(it);BrandTextButton(copyText(UiStrings.MOBILE_RETRY_MAKERSPACE_SETTINGS),{scope.launch {action.refreshCapabilities()}})}
                                }
                                BrandTextButton(copyText(UiStrings.MOBILE_REFRESH_ACCOUNT),{scope.launch {bundle.holder.foreground()}},!state.busy)
                                if(state.user?.emailVerified==false && state.config?.emailEnabled==true)bundle.accounts?.let {BrandTextButton(copyText(UiStrings.MOBILE_CONFIRM_YOUR_EMAIL),{it.open(AccountRoute.VERIFY_EMAIL,state.user?.email.orEmpty())})}
                                BrandTextButton(copyText(UsageStrings.USAGE_APP_GUIDE),{showingGuide=true},!state.busy,modifier=Modifier.semantics {testTag="usage-guide-entry"})
                                onShowIntro?.let {BrandTextButton(copyText(UiStrings.MOBILE_SHOW_INTRODUCTION),it,!state.busy)}
                                BrandButton(copyText(UiStrings.MOBILE_SIGN_OUT),{signingOut=true},!state.busy)
                                onChangeServer?.let {BrandTextButton(copyText(UiStrings.MOBILE_CHANGE_SERVER),{changingServer=true},!state.busy)}
                            }
                        }
                        member==null -> Text(copyText(UiStrings.MOBILE_MEMBER_INFORMATION_IS_UNAVAILABLE_FOR_THIS_BUILD),Modifier.padding(24.dp))
                        content?.scope?.id!=state.selectedId -> Column(Modifier.padding(24.dp)) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(copyText(UiStrings.MOBILE_LOADING_YOUR_MAKERSPACE))}
                        destination==0 -> MemberHome(member,state.user?.displayName.orEmpty().ifBlank {state.user?.username.orEmpty()},{navigate(1)},bundle.demo,homeExtra,onPayments=payments?.let {{showingPayments=true}},guideProgress=guidance,bookingsEnabled=actionState?.capabilities?.value?.bookableSpaces==true,onProfile={navigate(4)})
                        destination==1 -> MemberInventory(member)
                        destination==2 -> MemberActivityScreen(member)
                        else -> MemberInboxScreen(member,notifications={bundle.push?.let {push->NotificationsPanel(push,bundle.demo,bundle.pushConfigured,state.memberships.associate {it.id to makerspaceLabel(it.slug)},bundle.pushPreview){hint->bundle.links?.offer(hint)}}},onOpenUpdate={bundle.links?.offer(it)},navigationSequence=inboxNavigationSerial)
                    }
                }
                state.error?.let {Box(Modifier.padding(horizontal=24.dp)){BrandError(it)}}
                if(!expanded && actionState?.route==null && !showingPayments && !showingStaff && !showingGuide) NavigationBar(Modifier.heightIn(min=if(LocalDensity.current.fontScale>=1.6f)112.dp else 80.dp)) {
                    destinations.forEachIndexed {index,title -> NavigationBarItem(selected=destination==index,onClick={navigate(index)},icon={Icon(destinationIcons[index],null)},label={Text(copyText(title))},colors=NavigationBarItemDefaults.colors(selectedIconColor=MaterialTheme.colorScheme.onPrimaryContainer,selectedTextColor=MaterialTheme.colorScheme.onPrimaryContainer,indicatorColor=MaterialTheme.colorScheme.primaryContainer)) }
                }
            }
        }
    }
    }
}

@Composable private fun SignOutConfirmation(onDismiss:()->Unit,onConfirm:()->Unit,changeServer:Boolean=false) {
    val copyText=rememberMobileText()

    AlertDialog(onDismissRequest=onDismiss,title={Text(if(changeServer)copyText(UiStrings.MOBILE_CHANGE_SERVER_9C8CC1) else copyText(UiStrings.MOBILE_SIGN_OUT_B11555))},
        text={Text(if(changeServer)copyText(UiStrings.MOBILE_CONTINUING_SIGNS_OUT_THIS_ACCOUNT_BEFORE_CHOOSING_ANOTHER_SERVER) else copyText(UiStrings.MOBILE_YOU_LL_NEED_TO_SIGN_IN_AGAIN_TO_USE_THIS_ACCOUNT))},
        confirmButton={TextButton(onClick=onConfirm,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text(if(changeServer)copyText(UiStrings.MOBILE_CONTINUE) else copyText(UiStrings.MOBILE_SIGN_OUT))}},
        dismissButton={BrandTextButton(copyText(UiStrings.MOBILE_CANCEL),onDismiss)})
}
