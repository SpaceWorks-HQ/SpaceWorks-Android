package org.spaceworks.app.notifications

import org.spaceworks.app.platform.MobileText

import org.spaceworks.app.auth.AuthStateHolder
import org.spaceworks.app.network.ApiFailure
import okhttp3.HttpUrl.Companion.toHttpUrl

/** DEBUG fixtures own no Context, Firebase SDK, device token or disk state. */
data class DemoPushBundle(val holder:PushStateHolder,val control:PushPreviewControl)
object DemoPushSupport {
    fun create(text:MobileText=MobileText.ENGLISH):DemoPushBundle {
        val copyText=text
        val api=object:PushGateway,PushPreviewControl {
            var scenario=PushPreviewScenario.NORMAL
            override fun scenario(value:PushPreviewScenario){scenario=value}
            private val ids=mutableMapOf<Int,Int>()
            override suspend fun register(makerspaceId:Int,token:String):Int {
                when(scenario){PushPreviewScenario.UNAVAILABLE->throw ApiFailure(503,"Notifications are unavailable. Try again later.",null,null);PushPreviewScenario.THROTTLED->throw ApiFailure(429,"Too many requests. Try again later.",null,5);else->Unit}
                return ids.getOrPut(makerspaceId){100+makerspaceId}
            }
            override suspend fun remove(makerspaceId:Int,deviceId:Int){ids.remove(makerspaceId)}
        }
        val provider=object:PushProvider {
            override suspend fun token()="debug_fixture_token_not_a_device_1234567890"
            override suspend fun stop()=Unit
        }
        val persistence=object:PushPersistence {
            val consent=mutableMapOf<String,Boolean>();val records=mutableMapOf<String,List<PushRecord>>()
            override suspend fun consent(account:String)=consent[account]?:false
            override suspend fun consent(account:String,value:Boolean){consent[account]=value}
            override suspend fun records(account:String)=records[account].orEmpty()
            override suspend fun records(account:String,value:List<PushRecord>){records[account]=value}
            override suspend fun clear(account:String){consent.remove(account);records.remove(account)}
        }
        return DemoPushBundle(PushStateHolder(api,provider,persistence,text=copyText),api)
    }
    fun links(holder:AuthStateHolder,text:MobileText=MobileText.ENGLISH)=LinkStateHolder("https://demo.invalid/".toHttpUrl(),holder::authorizeMakerspace,holder::select,holder::authorizeSlug,text=text)
}
