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
    suspend fun forgotPassword(email:String):String {throw AuthMessage("Password recovery is unavailable. Contact your makerspace.")}
    suspend fun resetPassword(email:String,code:String,replacement:String):String {throw AuthMessage("Password recovery is unavailable. Contact your makerspace.")}
    suspend fun signUp(name:String,email:String,password:String,phone:String):String {throw AuthMessage("Account registration is unavailable. Contact your makerspace.")}
    suspend fun confirmEmail(code:String):String {throw AuthMessage("Email verification is unavailable. Contact your makerspace.")}
    suspend fun resendEmail():String {throw AuthMessage("Email verification is unavailable. Contact your makerspace.")}
    val nativeSocialReady:Boolean get()=false
    suspend fun google(clientId:String,credential:suspend(String,String)->String):SocialLoginResponse {throw AuthMessage("Device sign-in is not configured for this build.")}
}
fun interface AttestationProvider { suspend fun attest(challenge:String):JsonElement }
class DevelopmentAttestation(private val debug:Boolean,private val emulator:Boolean):AttestationProvider {
    val available get()=debug && emulator
    override suspend fun attest(challenge:String):JsonElement {
        if(!debug || !emulator) throw AuthMessage("Device verification is unavailable for this build. Contact your makerspace.")
        return buildJsonObject {put("type","development")}
    }
}
class GeneratedAuthGateway(private val session:ApiSession,private val appId:String,
    private val environment:EnvironmentEnum,private val attestation:AttestationProvider,
    private val onNativeSignOut:suspend()->Unit={}):AuthGateway {
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
    override suspend fun logout() {try {session.deviceAuthenticated.apiV1AuthDeviceLogoutCreate().requireBody()}finally {onNativeSignOut()}}
    override suspend fun changePassword(current:String,replacement:String) {session.auth.apiV1AuthChangePasswordCreate(ChangePassword(current,replacement)).requireBody()}
    override suspend fun forgotPassword(email:String)=session.authPublic.apiV1AuthForgotPasswordCreate(ForgotPasswordRequest(email)).requireBody().detail
    override suspend fun resetPassword(email:String,code:String,replacement:String)=session.authPublic.apiV1AuthResetPasswordCreate(ResetPasswordConfirmRequest.OtpResetPasswordConfirmValue(OtpResetPasswordConfirm(email,code,replacement))).requireBody().detail
    override suspend fun signUp(name:String,email:String,password:String,phone:String)=session.authPublic.apiV1AuthMemberSignUpCreate(MemberSignUp(name,email,password,phone,"")).requireBody().detail
    override suspend fun confirmEmail(code:String)=session.auth.apiV1AuthEmailVerificationConfirmCreate(EmailVerificationConfirm(code)).requireBody().detail
    override suspend fun resendEmail()=session.auth.apiV1AuthEmailVerificationResendCreate().requireBody().detail
    override val nativeSocialReady get()=(attestation as? DevelopmentAttestation)?.available==true
    override suspend fun google(clientId:String,credential:suspend(String,String)->String):SocialLoginResponse {
        val challenge=session.devicePublic.apiV1AuthDeviceAttestationChallengeCreate(DeviceIdentity(PlatformEnum.android,appId,environment)).requireBody()
        val nonce=session.socialPublic.apiV1AuthSocialNonceCreate(SocialNonce("google",Surface356Enum.member,DeliveryEnum.device,ClientPlatformEnum.android,challenge.challenge)).requireBody()
        val idToken=credential(clientId,nonce.nonce)
        val proof=attestation.attest(challenge.challenge)
        return session.socialPublic.apiV1AuthSocialGoogleCreate(SocialLogin(idToken,nonce.nonce,Surface356Enum.member,DeliveryEnum.device,ClientPlatformEnum.android,challenge=challenge.challenge,attestation=proof)).requireBody()
    }
}
