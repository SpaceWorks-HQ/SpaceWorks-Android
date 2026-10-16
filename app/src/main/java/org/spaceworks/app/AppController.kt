package org.spaceworks.app

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
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
    companion object {private val sessions=java.util.concurrent.ConcurrentHashMap<String,AuthBundle>()}
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
        health={ApiSession(it).checkHealth()},save={prefs.edit().putString("server",it).apply()})
    private var demo:AuthBundle?=null
    fun demoSession():AuthBundle?=demo ?: run {
        sessions.values.forEach {it.push?.pause()}
        FcmRegistration.pause(getApplication())
        DemoSupport.create()?.also {demo=it}
    }
    fun clearDemo(){demo?.push?.pause();demo=null}
    fun realSession(origin:String):AuthBundle {
        val key=getApplication<Application>().noBackupFilesDir.absolutePath+"\n"+origin
        return sessions.computeIfAbsent(key){createReal(origin)}
    }
    private fun createReal(origin:String):AuthBundle {
        val application=getApplication<Application>()
        val store=EncryptedTokenStore.sharedAndroid(application,origin)
        lateinit var holder:AuthStateHolder
        var push:PushStateHolder?=null
        var links:LinkStateHolder?=null
        var member:org.spaceworks.app.member.MemberStateHolder?=null
        var actions:org.spaceworks.app.actions.MemberActionsStateHolder?=null
        val session=ApiSession(ServerAddress.parse(origin,BuildConfig.DEBUG),store){holder.state.value.selectedId?.toString()}
        val environment=if(BuildConfig.DEBUG)EnvironmentEnum.development else EnvironmentEnum.production
        val emulator=Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("Emulator") || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.startsWith("sdk_")
        val gateway=GeneratedAuthGateway(session,BuildConfig.APPLICATION_ID,environment,DevelopmentAttestation(BuildConfig.DEBUG,emulator),
            onNativeSignOut={try {GoogleIdentity.clear(application)}catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}catch(_:Exception){}},
            onBeforeNativeSignOut={push?.disable(forgetConsent=false)})
        holder=AuthStateHolder(gateway,store,
            readSelection={prefs.getInt("space.$origin",-1).takeIf {it>0}},
            saveSelection={id->prefs.edit().apply {if(id==null)remove("space.$origin")else putInt("space.$origin",id)}.apply()},
            onSessionEnded={member?.clear();actions?.clear();links?.clear();push?.pause()})
        links=LinkStateHolder(session.server,holder::authorizeMakerspace,holder::select,holder::authorizeSlug)
        push=PushStateHolder(GeneratedPushGateway(session,environment),object:PushProvider {
            override suspend fun token()=FcmRegistration.token(application)
            override suspend fun stop()=FcmRegistration.stop(application)
        },AndroidPushPersistence(application.getSharedPreferences("push-registration",0)),deliveryGate={enabled,ids->NativePushEvents.configure(application,enabled,ids)})
        val accounts=AccountStateHolder(gateway,{holder.state.value.selfRegistrationEnabled},{holder.foreground()})
        member=org.spaceworks.app.member.MemberStateHolder(org.spaceworks.app.member.GeneratedMemberGateway(session),onUnauthorized={holder.foreground()})
        actions=org.spaceworks.app.actions.MemberActionsStateHolder(org.spaceworks.app.actions.GeneratedMemberActionsGateway(session),requireNotNull(member),onUnauthorized={holder.foreground()},onProfileSaved={holder.foreground()},capabilities=org.spaceworks.app.actions.GeneratedMakerCapabilitiesGateway(session))
        return AuthBundle(holder,store,accounts=accounts,member=member,actions=actions,origin=session.server.toString(),push=push,links=links,pushConfigured=FcmRegistration.configured(application),sessionEpoch={store.snapshot().epoch})
    }
}
