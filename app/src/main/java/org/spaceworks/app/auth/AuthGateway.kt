package org.spaceworks.app.auth

import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.MobileCopyException
import org.spaceworks.app.platform.failure
import org.spaceworks.app.copy.CoreStrings
import org.spaceworks.app.copy.apiFailure

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
    suspend fun me(expectedEpoch:Long):AuthUserPayload=me()
    suspend fun logout()
    /** The caller's installation is captured before private cleanup, which may invoke callbacks. */
    suspend fun logout(expectedEpoch:Long) {logout()}
    suspend fun changePassword(current:String,replacement:String) {throw MobileCopyException(CoreStrings.CORE_PASSWORD_UPDATES_ARE_UNAVAILABLE_CONTACT_YOUR_MAKERSPACE)}
    suspend fun changePassword(current:String,replacement:String,expectedEpoch:Long) {changePassword(current,replacement)}
    suspend fun forgotPassword(email:String):String {throw MobileCopyException(CoreStrings.CORE_PASSWORD_RECOVERY_IS_UNAVAILABLE_CONTACT_YOUR_MAKERSPACE)}
    suspend fun resetPassword(email:String,code:String,replacement:String):String {throw MobileCopyException(CoreStrings.CORE_PASSWORD_RECOVERY_IS_UNAVAILABLE_CONTACT_YOUR_MAKERSPACE)}
    suspend fun signUp(name:String,email:String,password:String,phone:String):String {throw MobileCopyException(CoreStrings.CORE_ACCOUNT_REGISTRATION_IS_UNAVAILABLE_CONTACT_YOUR_MAKERSPACE)}
    suspend fun confirmEmail(code:String):String {throw MobileCopyException(CoreStrings.CORE_EMAIL_VERIFICATION_IS_UNAVAILABLE_CONTACT_YOUR_MAKERSPACE)}
    suspend fun resendEmail():String {throw MobileCopyException(CoreStrings.CORE_EMAIL_VERIFICATION_IS_UNAVAILABLE_CONTACT_YOUR_MAKERSPACE)}
    val nativeSocialReady:Boolean get()=false
    suspend fun google(clientId:String,credential:suspend(String,String)->String):SocialLoginResponse {throw MobileCopyException(CoreStrings.CORE_DEVICE_SIGN_IN_IS_NOT_CONFIGURED_FOR_THIS_BUILD)}
    /** Configured OIDC provider: [credential] receives the provider and server nonce and returns its ID token. */
    suspend fun oidc(provider:NativeOidc,credential:suspend(NativeOidc,String)->String):SocialLoginResponse {throw MobileCopyException(CoreStrings.CORE_DEVICE_SIGN_IN_IS_NOT_CONFIGURED_FOR_THIS_BUILD)}
    /** Member-only phone sign-in. The returned challenge is reused, unchanged, by [phoneConfirm]. */
    suspend fun phoneStart(phone:String):PhoneChallenge {throw MobileCopyException(CoreStrings.CORE_PHONE_SIGN_IN_IS_UNAVAILABLE_ON_THIS_SERVER)}
    suspend fun phoneConfirm(phone:String,code:String,challenge:PhoneChallenge):PhoneLoginResult {throw MobileCopyException(CoreStrings.CORE_PHONE_SIGN_IN_IS_UNAVAILABLE_ON_THIS_SERVER)}
    /** Identity-only restore of an archived phone account; never returns a session. Reuses a [phoneStart] challenge. */
    suspend fun restorePhone(phone:String,code:String,newPassword:String,challenge:PhoneChallenge):AccountRestoreResult {throw MobileCopyException(CoreStrings.CORE_PHONE_SIGN_IN_IS_UNAVAILABLE_ON_THIS_SERVER)}
    /** Revokes every browser session, device grant and push destination for this account on this server. */
    suspend fun logoutAll(expectedEpoch:Long):LogoutAllResponse {throw MobileCopyException(CoreStrings.CORE_COULDN_T_CONNECT_TRY_AGAIN)}
}
/** One attestation challenge bound to a phone sign-in attempt. Local state, never persisted or logged. */
class PhoneChallenge(val challenge:String,val expiresInSeconds:Int) { override fun toString()="PhoneChallenge(redacted)" }
/** Produces the generated attestation union over one fresh server challenge. */
fun interface AttestationProvider { suspend fun attest(challenge:String):DeviceAttestationPayload }
class DevelopmentAttestation(private val debug:Boolean,private val emulator:Boolean,private val text:MobileText=MobileText.ENGLISH):AttestationProvider {
    val available get()=debug && emulator
    override suspend fun attest(challenge:String):DeviceAttestationPayload {
        if(!debug || !emulator) throw AuthMessage(text(CoreStrings.CORE_DEVICE_VERIFICATION_IS_UNAVAILABLE_FOR_THIS_BUILD_CONTACT_YO))
        // The schema's shared type enum only names play_integrity; development uses its open-object arm.
        return DeviceAttestationPayload.JsonElementValue(buildJsonObject {put("type","development")})
    }
}
/**
 * Debug emulators send the development proof that only a development server accepts. Every other
 * build sends a Play Integrity Standard token over base64url(SHA-256(challenge)) when the owner
 * supplied a Cloud project number; otherwise device sign-in stays visibly unavailable.
 */
class NativeAttestation(private val development:DevelopmentAttestation,private val integrity:(suspend(String)->String)?,
    private val text:MobileText=MobileText.ENGLISH):AttestationProvider {
    val available get()=development.available || integrity!=null
    override suspend fun attest(challenge:String):DeviceAttestationPayload {
        if(development.available)return development.attest(challenge)
        val provider=integrity?:throw AuthMessage(text(CoreStrings.CORE_DEVICE_VERIFICATION_IS_UNAVAILABLE_FOR_THIS_BUILD_CONTACT_YO))
        val token=try {provider(challenge)}catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
            catch(_:Exception){throw AuthMessage(text(CoreStrings.CORE_THIS_DEVICE_COULD_NOT_VERIFY_THE_APP_TRY_AGAIN))}
        return DeviceAttestationPayload.DeviceAttestationPayloadAnyOfValue(
            DeviceAttestationPayloadAnyOf(DeviceAttestationPayloadTypeEnum.play_integrity,token))
    }
}
class GeneratedAuthGateway(private val session:ApiSession,private val appId:String,
    private val environment:EnvironmentEnum,private val attestation:AttestationProvider,
    private val onNativeSignOut:suspend()->Unit={},private val onBeforeNativeSignOut:suspend()->Unit={},private val text:MobileText=MobileText.ENGLISH,
    private val onBeforeOwnedSignOut:suspend(Long)->Unit={onBeforeNativeSignOut()}):AuthGateway {
    constructor(session:ApiSession,appId:String,environment:EnvironmentEnum,attestation:AttestationProvider,onNativeSignOut:suspend()->Unit={},onBeforeNativeSignOut:suspend()->Unit)
        :this(session,appId,environment,attestation,onNativeSignOut,onBeforeNativeSignOut,MobileText.ENGLISH)

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
    override suspend fun me()=me(session.installationEpoch)
    override suspend fun me(expectedEpoch:Long)=session.pinnedGlobalApi(org.spaceworks.generated.api.AuthApi::class.java,expectedEpoch)
        .apiV1AuthMeRetrieve().requireBody()
    override suspend fun logout()=logout(session.installationEpoch)
    override suspend fun logout(expectedEpoch:Long):Unit=withContext(kotlinx.coroutines.NonCancellable) {
        val expected=expectedEpoch
        fun requireOwner() {
            if(session.installationEpoch!=expected)throw kotlinx.coroutines.CancellationException("Session changed")
        }
        // Push cleanup may suspend. Neither its later logout nor SDK cleanup may target a new login.
        requireOwner()
        var cleanupFailure:Exception?=null
        try {onBeforeOwnedSignOut(expected)}catch(f:Exception){cleanupFailure=f}
        requireOwner()
        try {
            val api=session.pinnedGlobalApi(org.spaceworks.generated.api.DeviceAuthApi::class.java,expected) {call->
                if(session.installationEpoch!=expected)call.cancel()
            }
            requireOwner()
            api.apiV1AuthDeviceLogoutCreate().requireBody()
        } finally {
            if(session.installationEpoch==expected)onNativeSignOut()
        }
        cleanupFailure?.let {throw it}
        Unit
    }
    override suspend fun changePassword(current:String,replacement:String)=changePassword(current,replacement,session.installationEpoch)
    override suspend fun changePassword(current:String,replacement:String,expectedEpoch:Long) {
        if(session.installationEpoch!=expectedEpoch)throw kotlinx.coroutines.CancellationException("Session changed")
        session.pinnedGlobalApi(org.spaceworks.generated.api.AuthApi::class.java,expectedEpoch) {call->
            if(session.installationEpoch!=expectedEpoch)call.cancel()
        }.apiV1AuthChangePasswordCreate(ChangePassword(current,replacement)).requireBody()
    }
    override suspend fun forgotPassword(email:String)=session.authPublic.apiV1AuthForgotPasswordCreate(ForgotPasswordRequest(email)).requireBody().detail
    override suspend fun resetPassword(email:String,code:String,replacement:String)=session.authPublic.apiV1AuthResetPasswordCreate(ResetPasswordConfirmRequest.OtpResetPasswordConfirmValue(OtpResetPasswordConfirm(email,code,replacement))).requireBody().detail
    override suspend fun signUp(name:String,email:String,password:String,phone:String)=session.authPublic.apiV1AuthMemberSignUpCreate(MemberSignUp(name,email,password,phone,"")).requireBody().detail
    override suspend fun confirmEmail(code:String)=session.globalApi(org.spaceworks.generated.api.AuthApi::class.java).apiV1AuthEmailVerificationConfirmCreate(EmailVerificationConfirm(code)).requireBody().detail
    override suspend fun resendEmail()=session.globalApi(org.spaceworks.generated.api.AuthApi::class.java).apiV1AuthEmailVerificationResendCreate().requireBody().detail
    override val nativeSocialReady get()=when(attestation){is NativeAttestation->attestation.available;is DevelopmentAttestation->attestation.available;else->true}
    private suspend fun challenge()=session.devicePublic.apiV1AuthDeviceAttestationChallengeCreate(DeviceIdentity(PlatformEnum.android,appId,environment)).requireBody()
    override suspend fun oidc(provider:NativeOidc,credential:suspend(NativeOidc,String)->String):SocialLoginResponse {
        val slug=provider.key.removePrefix("oidc:").takeIf {provider.key.startsWith("oidc:") && it.matches(Regex("[A-Za-z0-9_-]{1,64}"))}
            ?:throw MobileCopyException(CoreStrings.CORE_DEVICE_SIGN_IN_IS_NOT_CONFIGURED_FOR_THIS_BUILD)
        val challenge=challenge()
        val nonce=session.socialPublic.apiV1AuthSocialNonceCreate(SocialNonce(provider.key,Surface356Enum.member,DeliveryEnum.device,ClientPlatformEnum.android,challenge.challenge)).requireBody()
        val idToken=credential(provider,nonce.nonce)
        val proof=attestation.attest(challenge.challenge)
        return session.socialPublic.apiV1AuthSocialOidcCreate(slug,SocialLogin(idToken,nonce.nonce,Surface356Enum.member,DeliveryEnum.device,ClientPlatformEnum.android,challenge=challenge.challenge,attestation=proof)).requireBody()
    }
    override suspend fun phoneStart(phone:String):PhoneChallenge {
        val challenge=challenge()
        session.authPublic.apiV1AuthPhoneLoginStartCreate(PhoneLoginStart(phone,DeliveryEnum.device,ClientPlatformEnum.android,challenge.challenge)).requireBody()
        return PhoneChallenge(challenge.challenge,challenge.expiresIn)
    }
    override suspend fun restorePhone(phone:String,code:String,newPassword:String,challenge:PhoneChallenge):AccountRestoreResult {
        val proof=attestation.attest(challenge.challenge)
        return session.publicApi(org.spaceworks.generated.api.AccountsApi::class.java).apiV1AuthAccountRestorePhoneCreate(
            AccountRestorePhone(phone,code,newPassword,DeliveryEnum.device,ClientPlatformEnum.android,challenge.challenge,proof)).requireBody()
    }
    override suspend fun phoneConfirm(phone:String,code:String,challenge:PhoneChallenge):PhoneLoginResult {
        val proof=attestation.attest(challenge.challenge)
        return session.authPublic.apiV1AuthPhoneLoginConfirmCreate(PhoneLoginConfirm(phone,code,DeliveryEnum.device,ClientPlatformEnum.android,challenge.challenge,proof)).requireBody()
    }
    override suspend fun logoutAll(expectedEpoch:Long):LogoutAllResponse=withContext(kotlinx.coroutines.NonCancellable) {
        if(session.installationEpoch!=expectedEpoch)throw kotlinx.coroutines.CancellationException("Session changed")
        // Remove this device's push registrations while the grant still authenticates them.
        try {onBeforeOwnedSignOut(expectedEpoch)}catch(_:Exception){}
        if(session.installationEpoch!=expectedEpoch)throw kotlinx.coroutines.CancellationException("Session changed")
        val result=session.pinnedGlobalApi(org.spaceworks.generated.api.AuthApi::class.java,expectedEpoch) {call->
            if(session.installationEpoch!=expectedEpoch)call.cancel()
        }.apiV1AuthLogoutAllCreate().requireBody()
        if(session.installationEpoch==expectedEpoch)onNativeSignOut()
        result
    }
    override suspend fun google(clientId:String,credential:suspend(String,String)->String):SocialLoginResponse {
        val challenge=challenge()
        val nonce=session.socialPublic.apiV1AuthSocialNonceCreate(SocialNonce("google",Surface356Enum.member,DeliveryEnum.device,ClientPlatformEnum.android,challenge.challenge)).requireBody()
        val idToken=credential(clientId,nonce.nonce)
        val proof=attestation.attest(challenge.challenge)
        return session.socialPublic.apiV1AuthSocialGoogleCreate(SocialLogin(idToken,nonce.nonce,Surface356Enum.member,DeliveryEnum.device,ClientPlatformEnum.android,challenge=challenge.challenge,attestation=proof)).requireBody()
    }
}
