package org.spaceworks.app

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.spaceworks.app.account.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.spaceworks.app.auth.*
import org.spaceworks.app.network.*
import org.spaceworks.app.notifications.*
import org.spaceworks.app.platform.*
import org.spaceworks.generated.model.EnvironmentEnum

sealed interface PendingNativeIntent {
    val sequence:Long
    data class Link(val raw:String,override val sequence:Long):PendingNativeIntent
    data class Hint(val hint:PushHint,override val sequence:Long):PendingNativeIntent
}
class AppController(application:Application):AndroidViewModel(application) {
    private val sessions=java.util.concurrent.ConcurrentHashMap<String,AuthBundle>()
    private val copyText=MobileText.resources(application)
    private val management=AccountManagementCoordinator(application.resources::accountText,copyText)
    private val prefs=application.getSharedPreferences("deployment",0)
    private val fixed=BuildConfig.SERVER_URL
    private val incomingMutable=MutableStateFlow<PendingNativeIntent?>(null)
    val incoming=incomingMutable.asStateFlow()
    private var intentSequence=0L
    /** A URL can express intent; it never configures or changes the selected server. */
    fun receive(intent:Intent) {
        val hint=runCatching {SpaceWorksMessagingService.consume(intent)}.getOrNull()
        val raw=intent.dataString;intent.data=null
        val next=++intentSequence
        incomingMutable.value=if(hint!=null)PendingNativeIntent.Hint(hint,next)
            else raw?.takeIf {it.toByteArray().size<=2048}?.let {PendingNativeIntent.Link(it,next)}
    }
    fun consumed(sequence:Long){if(incomingMutable.value?.sequence==sequence)incomingMutable.value=null}
    val onboarding=OnboardingStateHolder(prefs.getBoolean("intro.complete",false)){prefs.edit().putBoolean("intro.complete",it).apply()}
    val appearance=AppearanceStateHolder(prefs.getBoolean("theme.dark",false)){prefs.edit().putBoolean("theme.dark",it).apply()}
    val connection=ConnectionStateHolder(fixed.ifBlank {prefs.getString("server","")?:""},fixed.isNotBlank(),BuildConfig.DEBUG,
        health={ApiSession(it).checkHealth()},save={prefs.edit().putString("server",it).apply()},text=copyText)
    private var demo:AuthBundle?=null
    fun demoSession():AuthBundle?=(demo ?: run {
        sessions.values.forEach {it.push?.pause()}
        DemoSupport.create(text=copyText,context=getApplication<Application>(),paymentFiles=org.spaceworks.app.payments.CachedPaymentFiles(java.io.File(getApplication<Application>().cacheDir,"native-documents")),management=management)?.also {demo=it}
    })?.also {management.show(it)}
    fun clearDemo(){demo?.clearDemoState();demo=null}
    fun realSession(origin:String):AuthBundle {
        val key=getApplication<Application>().noBackupFilesDir.absolutePath+"\n"+origin
        return sessions.computeIfAbsent(key){createReal(origin)}.also {management.show(it)}
    }
    private fun createReal(origin:String):AuthBundle {
        val application=getApplication<Application>()
        val store=EncryptedTokenStore.sharedAndroid(application,origin)
        lateinit var holder:AuthStateHolder
        var runtime:NativeAccountRuntime?=null
        var accounts:AccountStateHolder?=null
        var push:PushStateHolder?=null
        var links:LinkStateHolder?=null
        var member:org.spaceworks.app.member.MemberStateHolder?=null
        var actions:org.spaceworks.app.actions.MemberActionsStateHolder?=null
        var payments:org.spaceworks.app.payments.PaymentsStateHolder?=null
        var staff:org.spaceworks.app.staff.StaffStateHolder?=null
        val session=ApiSession(ServerAddress.parse(origin,BuildConfig.DEBUG),store){holder.state.value.selectedId?.toString()}
        val environment=if(BuildConfig.DEBUG)EnvironmentEnum.development else EnvironmentEnum.production
        val emulator=Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("Emulator") || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.startsWith("sdk_")
        val gateway=GeneratedAuthGateway(session,BuildConfig.APPLICATION_ID,environment,DevelopmentAttestation(BuildConfig.DEBUG,emulator,text=copyText),
            onNativeSignOut={try {GoogleIdentity.clear(application)}catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}catch(_:Exception){}},
            onBeforeOwnedSignOut={epoch->push?.disable(forgetConsent=false,expectedEpoch=epoch)},text=copyText)
        holder=AuthStateHolder(gateway,store,
            readSelection={prefs.getInt("space.$origin",-1).takeIf {it>0}},
            saveSelection={id->prefs.edit().apply {if(id==null)remove("space.$origin")else putInt("space.$origin",id)}.apply()},
            onSessionEnded={accounts?.clear();runtime?.let(management::sessionEnded);staff?.resetAccount();member?.clear();actions?.clear();payments?.clear();links?.clear();push?.pause()},
            onScopeChanged={runtime?.let(management::scopeChanged);staff?.clear();member?.clear();actions?.clear();payments?.clear()},text=copyText)
        session.bindIdentityEpoch {holder.acceptedInstallationEpoch}
        links=LinkStateHolder(session.server,holder::authorizeMakerspace,{id->if(staff?.protectsNavigation==true || management.protectsNavigation())throw kotlinx.coroutines.CancellationException();holder.select(id)},holder::authorizeSlug,text=copyText)
        push=PushStateHolder(GeneratedPushGateway(session,environment),object:PushProvider {
            override suspend fun token():String=throw kotlinx.coroutines.CancellationException("Push owner required")
            override suspend fun stop()=Unit
            override suspend fun token(account:PushAccount):String {
                val epoch=account.installationEpoch?:throw kotlinx.coroutines.CancellationException("Push owner required")
                val lease=account.lease?:throw kotlinx.coroutines.CancellationException("Push owner required")
                if(holder.acceptedInstallationEpoch!=epoch)throw kotlinx.coroutines.CancellationException("Push owner changed")
                return FcmRegistration.token(application,lease) {
                    // The coordinator may hold its monitor: never take the store monitor here.
                    store.installationEpoch.value==epoch && lease.owner==PushOwnerIdentity(session.server.toString(),epoch)
                }
            }
            override suspend fun stop(account:PushAccount,expectedEpoch:Long?) {
                val epoch=expectedEpoch?:account.installationEpoch?:return
                if(account.installationEpoch!=epoch || store.snapshot().epoch!=epoch)return
                val owner=PushOwnerIdentity(session.server.toString(),epoch)
                val lease=account.lease?.takeIf {it.owner==owner}?:NativePushEvents.cleanupLease(owner)?:return
                FcmRegistration.stop(application,lease) {store.installationEpoch.value==epoch}
            }
        },AndroidPushPersistence(application.getSharedPreferences("push-registration",0)),text=copyText,
            ownedDeliveryGate={account,enabled,ids->
                val lease=account.lease
                val epoch=account.installationEpoch
                if(lease!=null && epoch!=null && lease.owner==PushOwnerIdentity(session.server.toString(),epoch) &&
                    (!enabled || (store.snapshot().epoch==epoch && holder.acceptedInstallationEpoch==epoch)))NativePushEvents.configure(application,lease,enabled,ids)
            })
        accounts=AccountStateHolder(gateway,{holder.state.value.selfRegistrationEnabled},{holder.foreground()},text=copyText)
        member=org.spaceworks.app.member.MemberStateHolder(org.spaceworks.app.member.GeneratedMemberGateway(session,text=copyText),onUnauthorized={holder.foreground()},text=copyText)
        actions=org.spaceworks.app.actions.MemberActionsStateHolder(org.spaceworks.app.actions.GeneratedMemberActionsGateway(session,text=copyText),requireNotNull(member),onUnauthorized={holder.foreground()},onProfileSaved={holder.foreground()},capabilities=org.spaceworks.app.actions.GeneratedMakerCapabilitiesGateway(session,text=copyText),text=copyText)
        payments=org.spaceworks.app.payments.PaymentsStateHolder(
            org.spaceworks.app.payments.GeneratedPaymentsGateway(session,text=copyText),
            org.spaceworks.app.payments.CachedPaymentFiles(java.io.File(application.cacheDir,"native-documents")),
            onUnauthorized={holder.foreground()},onForbidden={holder.foreground()},
            resetSDK={runCatching {com.razorpay.Checkout.clearUserData(application)};Unit},text=copyText)
        val staffAuthority=org.spaceworks.app.staff.StaffAuthorizer {scope,action->
            val current=holder.state.value
            val membership=current.selected
            current.staffAllowed && membership?.id==scope.id && membership.slug==scope.slug &&
                (action==null || action in membership.actions)
        }
        val transfer=StorageTransfer(java.io.File(application.cacheDir,"staff-transfer"),session.server.toString().takeIf {BuildConfig.DEBUG&&session.server.scheme=="http"})
        staff=org.spaceworks.app.staff.StaffStateHolder(
            org.spaceworks.app.staff.GeneratedStaffGateway(session,staffAuthority,text=copyText),staffAuthority,
            org.spaceworks.app.staff.StaffPhotoUpload {grant,jpeg->
                if(grant.method=="PUT")transfer.uploadPut(grant.uploadUrl,grant.headers,jpeg,"image/jpeg")
                else transfer.uploadPost(grant.uploadUrl,grant.fields,jpeg,"image/jpeg","evidence.jpg")
            },onUnauthorized={holder.foreground()},onForbidden={holder.foreground()},text=copyText)
        runtime=NativeAccountRuntime(session.server.toString(),store,{holder.acceptedInstallationEpoch},
            {holder.state.value.user?.id?.toString()},
            {holder.state.value.selected?.let {org.spaceworks.app.member.MemberScope(it.id,it.slug)}},
            holder::endLocalSession,holder::foreground,holder::dropConfirmedMembership)
        val bundle=AuthBundle(holder,store,accounts=accounts,member=member,actions=actions,origin=session.server.toString(),push=push,links=links,pushConfigured=FcmRegistration.configured(application),sessionEpoch={store.snapshot().epoch},payments=payments,staff=staff,
            staffAvailable={holder.state.value.staffAllowed && holder.state.value.selected!=null},management=management)
        management.register(bundle,requireNotNull(runtime)) {owner->GeneratedAccountGateway(session,owner.hasSession,owner.selected,application.resources::accountText)}
        viewModelScope.launch {store.installationEpoch.collect {holder.invalidateInstallation(it)}}
        return bundle
    }
}
