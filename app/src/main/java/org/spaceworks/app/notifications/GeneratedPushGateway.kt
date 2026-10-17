package org.spaceworks.app.notifications

import org.spaceworks.app.auth.requireBody
import org.spaceworks.app.network.ApiSession
import org.spaceworks.app.network.Errors
import org.spaceworks.generated.api.NativePushApi
import org.spaceworks.generated.model.EnvironmentEnum
import org.spaceworks.generated.model.PushDeviceRegistration
import org.spaceworks.generated.model.PushDeviceRegistrationProviderEnum

/** Each generated service captures the maker header; mutable global selection cannot override it. */
class GeneratedPushGateway(private val session:ApiSession,private val environment:EnvironmentEnum):PushGateway {
    override suspend fun register(makerspaceId:Int,token:String):Int {
        require(makerspaceId>0 && token.matches(Regex("^[A-Za-z0-9_:.-]{20,4096}$")))
        val result=session.scopedApi(NativePushApi::class.java,makerspaceId)
            .apiV1IntegrationsPushDevicesCreate(PushDeviceRegistration(token,PushDeviceRegistrationProviderEnum.fcm,environment)).requireBody()
        check(result.id>0 && result.makerspaceId==makerspaceId && result.active && result.provider=="fcm" && result.environment==environment.value)
        return result.id
    }
    override suspend fun remove(makerspaceId:Int,deviceId:Int) {
        require(makerspaceId>0 && deviceId>0)
        remove(session.scopedApi(NativePushApi::class.java,makerspaceId),deviceId)
    }
    override suspend fun remove(makerspaceId:Int,deviceId:Int,expectedEpoch:Long) {
        require(makerspaceId>0 && deviceId>0)
        remove(session.pinnedScopedApi(NativePushApi::class.java,makerspaceId,expectedEpoch),deviceId)
    }
    private suspend fun remove(api:NativePushApi,deviceId:Int) {
        val response=api.apiV1IntegrationsPushDevicesDestroy(deviceId)
        if(response.code()==204 || response.code()==404)return
        throw Errors.map(response.code(),response.errorBody()?.string(),response.headers()["Retry-After"])
    }
}
