package org.spaceworks.app

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import org.spaceworks.app.auth.*
import org.spaceworks.app.network.*
import org.spaceworks.generated.model.EnvironmentEnum

class AppController(application:Application):AndroidViewModel(application) {
    private val prefs=application.getSharedPreferences("deployment",0)
    private val fixed=BuildConfig.SERVER_URL
    val connection=ConnectionStateHolder(fixed.ifBlank {prefs.getString("server","")?:""},fixed.isNotBlank(),BuildConfig.DEBUG,
        health={ApiSession(it).checkHealth()},save={prefs.edit().putString("server",it).apply()})
    private var demo:AuthBundle?=null
    fun demoSession():AuthBundle?=demo ?: DemoSupport.create()?.also {demo=it}
    fun clearDemo() {demo=null}
    private var savedOrigin:String?=null
    private var savedBundle:AuthBundle?=null
    fun realSession(origin:String):AuthBundle {
        if(origin==savedOrigin) savedBundle?.let {return it}
        val store=EncryptedTokenStore.android(getApplication(),origin)
        lateinit var holder:AuthStateHolder
        val session=ApiSession(ServerAddress.parse(origin,BuildConfig.DEBUG),store){holder.state.value.selectedId?.toString()}
        val emulator=Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("Emulator") || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.startsWith("sdk_")
        val gateway=GeneratedAuthGateway(session,BuildConfig.APPLICATION_ID,if(BuildConfig.DEBUG)EnvironmentEnum.development else EnvironmentEnum.production,
            DevelopmentAttestation(BuildConfig.DEBUG,emulator))
        holder=AuthStateHolder(gateway,store,
            readSelection={prefs.getInt("space.$origin",-1).takeIf {it>0}},
            saveSelection={id->prefs.edit().apply {if(id==null)remove("space.$origin") else putInt("space.$origin",id)}.apply()})
        return AuthBundle(holder,store).also {savedOrigin=origin;savedBundle=it}
    }
}
