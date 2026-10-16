package org.spaceworks.app.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.spaceworks.app.network.*
import org.spaceworks.generated.model.*
import retrofit2.Response

fun <T> Response<T>.requireBody():T {
    if (!isSuccessful) throw Errors.map(code(),errorBody()?.string(),headers()["Retry-After"])
    return body() ?: throw IllegalStateException("Empty API response")
}
interface AuthGateway {
    suspend fun config():PublicConfig
    suspend fun login(username:String,password:String):DeviceLoginResult
    suspend fun enroll(challenge:String):MFAEnrollment
    suspend fun verify(challenge:String,code:String,enrollment:Boolean):MFACompleted
    suspend fun me():AuthUserPayload
    suspend fun logout()
    suspend fun changePassword(current:String,replacement:String) {throw AuthMessage("Password updates are unavailable. Contact your makerspace.")}
}
fun interface AttestationProvider { suspend fun attest(challenge:String):JsonElement }
class DevelopmentAttestation(private val debug:Boolean,private val emulator:Boolean):AttestationProvider {
    override suspend fun attest(challenge:String):JsonElement {
        if(!debug || !emulator) throw AuthMessage("Device verification is unavailable for this build. Contact your makerspace.")
        return buildJsonObject {put("type","development")}
    }
}
class GeneratedAuthGateway(private val session:ApiSession,private val appId:String,
    private val environment:EnvironmentEnum,private val attestation:AttestationProvider):AuthGateway {
    override suspend fun config()=session.platform.apiV1ConfigRetrieve().requireBody()
    override suspend fun login(username:String,password:String):DeviceLoginResult {
        val challenge=session.devicePublic.apiV1AuthDeviceAttestationChallengeCreate(DeviceIdentity(PlatformEnum.android,appId,environment)).requireBody()
        val proof=attestation.attest(challenge.challenge)
        return session.devicePublic.apiV1AuthDeviceLoginCreate(DeviceLogin(PlatformEnum.android,appId,environment,username,password,challenge.challenge,proof)).requireBody()
    }
    override suspend fun enroll(challenge:String)=session.mfa.apiV1AuthMfaEnrollCreate(MFAChallenge(challenge)).requireBody()
    override suspend fun verify(challenge:String,code:String,enrollment:Boolean):MFACompleted {
        val input=MFAVerify(challenge,code)
        return (if(enrollment) session.mfa.apiV1AuthMfaConfirmCreate(input) else session.mfa.apiV1AuthMfaVerifyCreate(input)).requireBody()
    }
    override suspend fun me()=session.auth.apiV1AuthMeRetrieve().requireBody()
    override suspend fun logout() {session.deviceAuthenticated.apiV1AuthDeviceLogoutCreate().requireBody()}
    override suspend fun changePassword(current:String,replacement:String) {session.auth.apiV1AuthChangePasswordCreate(ChangePassword(current,replacement)).requireBody()}
}
