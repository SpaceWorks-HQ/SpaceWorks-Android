package org.spaceworks.app

import org.spaceworks.app.ui.UiStrings
import org.spaceworks.app.platform.rememberMobileText

import android.os.Bundle
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.first
import org.spaceworks.app.notifications.*
import org.spaceworks.app.platform.*
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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
import org.spaceworks.app.account.*
import org.spaceworks.app.theme.*
import org.spaceworks.app.ui.*

class MainActivity:ComponentActivity() {
    private val controller:AppController by viewModels()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        // Activity Result contracts must be registered before STARTED, including restored callbacks.
        val stripe=StripeSheetAdapter(this)
        val razorpay=RazorpaySheetAdapter(this,stripe.callbacks)
        val checkout=NativeCheckoutBindings(stripe.callbacks.state,{input->when(input){
                is StripeSheetInput->stripe.launch(input,false)
                is RazorpaySheetInput->razorpay.launch(input,false)
            }},
            {stripe.callbacks.acknowledgeServerRefresh()},
            {stripe.callbacks.recoverInterrupted(MobileText.resources(applicationContext)(org.spaceworks.app.payments.PaymentStrings.PAYMENT_CHECK_THE_PAYMENT_STATUS_BEFORE_TRYING_AGAIN));Unit})
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            NativeDocumentFiles(java.io.File(cacheDir,"native-documents")).cleanStale()
        }
        controller.receive(intent)
        setContent {
            val incoming by controller.incoming.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalNativeCheckout provides checkout) {
            SpaceWorksApp(controller.connection,controller::realSession,controller::demoSession,controller::clearDemo,controller.onboarding,controller.appearance,incoming,controller::consumed)
            }
        }
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);controller.receive(intent)}
}
@Composable fun SpaceWorksApp(holder:ConnectionStateHolder,sessionFactory:((String)->AuthBundle)?=null,
    demoFactory:()->AuthBundle?={DemoSupport.create()},onExitDemo:()->Unit={},onboarding:OnboardingStateHolder?=null,appearance:AppearanceStateHolder?=null,incoming:PendingNativeIntent?=null,onIncomingConsumed:(Long)->Unit={}) {
    val copyText=rememberMobileText()

    var localDark by rememberSaveable {mutableStateOf(false)}
    val dark=appearance?.dark?.collectAsStateWithLifecycle()?.value ?: localDark
    val view=LocalView.current
    SideEffect {
        var context=view.context
        while(context is ContextWrapper && context !is Activity)context=context.baseContext
        (context as? Activity)?.window?.let {window->
            WindowCompat.getInsetsController(window,view).apply {
                isAppearanceLightStatusBars=!dark
                isAppearanceLightNavigationBars=!dark
            }
        }
    }
    var demo by rememberSaveable {mutableStateOf(false)}
    val connection by holder.state.collectAsStateWithLifecycle()
    val introComplete=onboarding?.completed?.collectAsStateWithLifecycle()?.value ?: true
    val bundle=remember(connection.connected,connection.url,demo) {
        if(demo)demoFactory() else if(connection.connected)sessionFactory?.invoke(connection.url) else null
    }
    val identity=bundle?.holder?.state?.collectAsStateWithLifecycle()?.value?.user
    SpaceWorksTheme(dark) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                    if(identity!=null)Text(stringResource(R.string.app_name),Modifier.weight(1f).padding(vertical=16.dp),style=MaterialTheme.typography.titleLarge)
                    else Spacer(Modifier.weight(1f))
                    BrandTextButton(if(dark)copyText(UiStrings.MOBILE_LIGHT_MODE) else copyText(UiStrings.MOBILE_DARK_MODE),{localDark=!dark;appearance?.setDark(localDark)})
                }
                if(demo) Surface(color=MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(copyText(UiStrings.MOBILE_DEMO_MODE),Modifier.weight(1f).padding(vertical=8.dp),style=MaterialTheme.typography.labelLarge)
                        BrandTextButton(copyText(UiStrings.MOBILE_EXIT_DEMO),{bundle?.clearDemoState();demo=false;onExitDemo()})
                    }
                }
                LaunchedEffect(holder,introComplete) {if(introComplete && holder.state.value.url.isNotBlank() && !demo)holder.connect()}
                when {
                    !introComplete -> OnboardingScreen({onboarding?.finish()},if(BuildConfig.DEBUG){{onboarding?.finish();demo=true}}else null)
                    !demo && !connection.connected -> ServerScreen(holder,connection,onDemo=if(BuildConfig.DEBUG){{demo=true}}else null)
                    bundle!=null -> SessionContent(bundle,if(!holder.locked && !demo){{holder.changeServer()}} else null,onboarding?.let {{it.replay()}},incoming,onIncomingConsumed,onDemoDeleted={bundle.clearDemoState();demo=false;onExitDemo()})
                    else -> Text(copyText(UiStrings.MOBILE_SIGN_IN_IS_NOT_CONFIGURED_FOR_THIS_BUILD),Modifier.padding(24.dp))
                }
            }
        }
    }
}
@Composable private fun SessionContent(bundle:AuthBundle,onChangeServer:(()->Unit)?,onShowIntro:(()->Unit)?,incoming:PendingNativeIntent?,onIncomingConsumed:(Long)->Unit,onDemoDeleted:()->Unit) {
    val copyText=rememberMobileText()

    val state by bundle.holder.state.collectAsStateWithLifecycle()
    val management=bundle.management
    val completion=management?.owner?.completion?.collectAsStateWithLifecycle()?.value
    var accountPanel by remember(bundle){mutableStateOf<AccountManagementStateHolder?>(null)}
    var deletion by remember(bundle){mutableStateOf<AccountDeletionFlow?>(null)}
    var pendingStaffMaker by remember(bundle){mutableStateOf<Int?>(null)}
    var accountError by remember(bundle){mutableStateOf<String?>(null)}
    val panelState=accountPanel?.state?.collectAsStateWithLifecycle()?.value
    val account=bundle.accounts?.state?.collectAsStateWithLifecycle()?.value
    val preview=bundle.previewAuth?.state?.collectAsStateWithLifecycle()?.value
    val scope=rememberCoroutineScope()
    val lifecycle=LocalLifecycleOwner.current
    val context=LocalContext.current
    val supportMessage=stringResource(R.string.account_support_missing)
    fun closeAccount() {
        deletion?.clear();deletion=null
        accountPanel?.let {management?.hidden(it)}
        accountPanel=null
    }
    fun openAccount() {
        val current=bundle.holder.state.value
        val user=current.user?:return
        val epoch=bundle.holder.acceptedInstallationEpoch?:return
        if(current.busy)return
        val panel=management?.owner?.current()?:return
        current.selected?.let {panel.activate(org.spaceworks.app.member.MemberScope(it.id,it.slug))}
            ?: panel.activateGlobal("${bundle.origin}|${user.id}|$epoch")
        management.showing(panel)
        accountError=null;accountPanel=panel
        scope.launch {panel.loadCapabilities()}
    }
    DisposableEffect(lifecycle,bundle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP)management?.background()}
        lifecycle.lifecycle.addObserver(observer)
        onDispose {lifecycle.lifecycle.removeObserver(observer);management?.background()}
    }
    DisposableEffect(accountPanel) {
        val oldPanel=accountPanel
        onDispose {oldPanel?.let {management?.hidden(it)}}
    }
    DisposableEffect(deletion) {
        val oldDeletion=deletion
        onDispose {oldDeletion?.clear()}
    }
    LaunchedEffect(bundle,state.user?.id,state.selectedId) {closeAccount()}
    LaunchedEffect(bundle,state.user?.id) {pendingStaffMaker=null}
    LaunchedEffect(panelState?.scope,panelState?.globalAccountKey) {
        if(accountPanel!=null && panelState?.scope==null && panelState?.globalAccountKey==null)closeAccount()
    }

    val links=bundle.links?.state?.collectAsStateWithLifecycle()?.value
    val staffState=bundle.staff?.state?.collectAsStateWithLifecycle()?.value
    val staffForm=staffState?.let {it.busy || it.section in setOf(org.spaceworks.app.staff.StaffSection.REQUEST,org.spaceworks.app.staff.StaffSection.HANDOVER,org.spaceworks.app.staff.StaffSection.CHECK_IN,org.spaceworks.app.staff.StaffSection.BOOKINGS)}==true
    var pushError by remember(bundle){mutableStateOf<String?>(null)}
    suspend fun activatePush() {
        val user=bundle.holder.state.value.user?:return
        if(user.mustChangePassword)return
        try {
            val epoch:Long?=if(bundle.demo)null else (bundle.holder.acceptedInstallationEpoch?:return)
            val lease=if(bundle.demo)null else synchronized(bundle.tokens) {
                if(bundle.holder.acceptedInstallationEpoch!=epoch || bundle.tokens.snapshot().epoch!=epoch)return
                NativePushEvents.claim(PushOwnerIdentity(bundle.origin,requireNotNull(epoch)),enabled=false).also {
                    NativePushEvents.configure(context,it,false,emptySet())
                }
            }
            bundle.push?.activate(PushAccount(AndroidPushPersistence.accountKey(bundle.origin,user.id),
                bundle.holder.state.value.memberships.map {it.id}.toSet(),epoch,lease),bundle.demo || NotificationAccess.allowed(context))
        }catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
        catch(_:Exception){pushError=copyText(UiStrings.MOBILE_NOTIFICATIONS_ARE_UNAVAILABLE_TRY_AGAIN_LATER);bundle.push?.pause()}
    }
    LaunchedEffect(bundle,incoming?.sequence) {
        if(!bundle.demo && incoming!=null) {
            when(incoming){is PendingNativeIntent.Link->bundle.links?.offer(incoming.raw);is PendingNativeIntent.Hint->bundle.links?.offer(incoming.hint)}
            onIncomingConsumed(incoming.sequence)
        }
    }
    LaunchedEffect(bundle,links?.pending,links?.pendingLegacy,state.user?.id,staffForm,accountPanel) {
        if(state.user!=null && !staffForm && accountPanel==null && (links?.pending!=null || links?.pendingLegacy!=null)) {
            bundle.holder.state.first {!it.busy}
            bundle.links?.resolve()
        }
    }
    LaunchedEffect(bundle,state.user?.id,state.memberships.map {it.id}) {activatePush()}
    LaunchedEffect(bundle) {
        if(!bundle.demo)NativePushEvents.hints.collect {hint->
            // Receipt refreshes the current Inbox; it never changes the visible makerspace.
            if(bundle.holder.state.value.user!=null && bundle.holder.state.value.selectedId==hint.makerspaceId && bundle.holder.authorizeMakerspace(hint.makerspaceId) && bundle.holder.state.value.selectedId==hint.makerspaceId)bundle.member?.refreshInbox()
        }
    }
    LaunchedEffect(bundle) {
        if(!bundle.demo)NativePushEvents.token.collect {token->
            if(token!=null && bundle.holder.state.value.user!=null)try {bundle.push?.rotated(token)}catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}catch(_:Exception){pushError=copyText(UiStrings.MOBILE_NOTIFICATIONS_ARE_UNAVAILABLE_TRY_AGAIN_LATER)}
        }
    }

    LaunchedEffect(bundle) {if(!state.initialized)bundle.holder.initialize()}
    LaunchedEffect(bundle,state.user?.id,state.selectedId) {if(state.user==null || state.selectedId==null){bundle.staff?.clear();bundle.member?.clear();bundle.actions?.clear();bundle.payments?.clear()};if(state.user==null)bundle.push?.pause()}
    LaunchedEffect(lifecycle,bundle) {lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {bundle.holder.foreground();activatePush();kotlinx.coroutines.awaitCancellation()}}
    links?.error?.let {Box(Modifier.padding(horizontal=24.dp)){BrandError(it)}}
    pushError?.let {Box(Modifier.padding(horizontal=24.dp)){BrandError(it)}}
    accountError?.let {Box(Modifier.padding(horizontal=24.dp)){BrandError(it)}}
    when {
        completion?.codes?.isNotEmpty()==true -> AccountRecoveryCompletion(completion.codes,completion.notice,bundle.demo,{management.owner.acknowledgeCompletion()})
        deletion!=null && state.user!=null -> deletion?.Screen(onBack={deletion?.clear();deletion=null},onFix={id,destination->
            closeAccount()
            if(id==null || destination==AccountFixDestination.SUPPORT)accountError=supportMessage
            else if(destination==AccountFixDestination.STAFF)scope.launch {
                val epoch=bundle.sessionEpoch();val user=bundle.holder.state.value.user?.id
                if(bundle.staffAvailable() && bundle.holder.state.value.selectedId==id && bundle.holder.authorizeMakerspace(id) &&
                    bundle.sessionEpoch()==epoch && bundle.holder.state.value.user?.id==user && bundle.holder.state.value.selectedId==id && bundle.staffAvailable() &&
                    bundle.actions?.state?.value?.route==null && bundle.actions?.state?.value?.busy!=true && bundle.staff?.protectsNavigation!=true)pendingStaffMaker=id
                else accountError=supportMessage
            }
            else if(bundle.holder.state.value.memberships.none {it.id==id})accountError=supportMessage
            else {
                val target=when(destination){AccountFixDestination.PAYMENTS->"payments";AccountFixDestination.BOOKINGS->"bookings";AccountFixDestination.EVENTS->"events";else->"activity"}
                bundle.links?.offer("spaceworks:///makerspaces/$id/$target")
            }
        },onDeleted={closeAccount();management?.owner?.resetAccount();onDemoDeleted()})
        bundle.demo && preview?.page!=null -> PreviewAuthScreen(requireNotNull(bundle.previewAuth))
        account?.route!=null -> AccountFlowScreen(requireNotNull(bundle.accounts),bundle.demo)
        state.user==null -> AuthScreen(bundle,onChangeServer?.let {{scope.launch {bundle.holder.signOut();it()}}})
        state.user?.mustChangePassword==true -> ChangePasswordScreen(bundle)
        accountPanel!=null && management?.owner?.current()===accountPanel -> AccountScreens(requireNotNull(accountPanel),bundle.demo,::closeAccount,
            privacyURL=BrandLinks.privacyURL,supportURL=BrandLinks.supportURL,
            onDelete={deletion=bundle.deletionPreview(bundle.holder.state.value.memberships.map {it.id})})
        state.selected==null -> MakerspacePicker(bundle,if(management!=null){::openAccount}else null)
        else -> MemberShell(bundle,onChangeServer,onShowIntro,if(management!=null){::openAccount}else null,
            openStaff=pendingStaffMaker==state.selectedId,onStaffOpened={pendingStaffMaker=null})
    }
}
