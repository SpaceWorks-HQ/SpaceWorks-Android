package org.spaceworks.app.auth

import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.MobileCopyException
import org.spaceworks.app.platform.failure
import org.spaceworks.app.copy.CoreStrings
import org.spaceworks.app.copy.apiFailure

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.spaceworks.app.network.*
import org.spaceworks.generated.model.*

class AuthMessage(message:String):Exception(message)
class AuthCancelled:Exception()
enum class AuthGate { WEB_CONSOLE, SESSION_ENDED }
private fun ensure(value:Boolean,message:String) {if(!value) throw AuthMessage(message)}

data class AuthState(val busy:Boolean=false,val initialized:Boolean=false,val config:PublicConfig?=null,
    val user:AuthUserPayload?=null,val selectedId:Int?=null,val mfa:MFAChallengeResponse?=null,
    val enrollment:MFAEnrollment?=null,val recoveryCodes:List<String> = emptyList(),
    val error:String?=null,val notice:String?=null,val retryAtMillis:Long=0,
    val mfaExpiresAtMillis:Long=0,val expiredMfa:Boolean=false,val gate:AuthGate?=null) {
    val memberships get()=user?.makerspaces.orEmpty().filter {it.source==AuthMembershipSourceEnum.membership}
    val selected get()=memberships.firstOrNull {it.id==selectedId}
    val passwordEnabled get()=(config?.passwordLogin?.get("enabled") as? JsonPrimitive)?.booleanOrNull != false &&
        (config?.memberAccounts?.get("enabled") as? JsonPrimitive)?.booleanOrNull != false
    // The owner contract has no native session_authority yet. Role/action text alone cannot grant native staff tools.
    val staffAllowed get()=false
    val selfRegistrationEnabled get()=config!=null && (config?.memberAccounts?.get("enabled") as? JsonPrimitive)?.booleanOrNull!=false &&
        (config?.selfRegistration?.get("enabled") as? JsonPrimitive)?.booleanOrNull!=false
    val googleClientId get():String? {
        if((config?.memberAccounts?.get("enabled") as? JsonPrimitive)?.booleanOrNull==false)return null
        val google=config?.socialAuth?.get("google") as? JsonObject ?: return null
        if((google["enabled"] as? JsonPrimitive)?.booleanOrNull!=true)return null
        return (google["web_client_id"] as? JsonPrimitive)?.contentOrNull?.takeIf {it.isNotBlank()}
    }
}
class AuthStateHolder(private val api:AuthGateway,private val tokens:TokenStore,
    private val readSelection:()->Int?={null},private val saveSelection:(Int?)->Unit={},
    private val now:()->Long=System::currentTimeMillis,private val onSessionEnded:()->Unit={},
    private val onScopeChanged:()->Unit={},private val text:MobileText=MobileText.ENGLISH) {
    constructor(api:AuthGateway,tokens:TokenStore,readSelection:()->Int?={null},saveSelection:(Int?)->Unit={},now:()->Long=System::currentTimeMillis,onSessionEnded:()->Unit)
        :this(api,tokens,readSelection,saveSelection,now,onSessionEnded,{},MobileText.ENGLISH)

    private val mutable=MutableStateFlow(AuthState())
    val state=mutable.asStateFlow()
    private val operation=Mutex()
    private var installedEpoch:Long?=null
    private var retryAt=0L
    private var challengeExpiresAt=0L
    val nativeSocialReady get()=api.nativeSocialReady
    suspend fun initialize()=runOperation {
        val config=api.config()
        mutable.value=state.value.copy(config=config,initialized=true)
        val restored=withContext(Dispatchers.IO){tokens.snapshot()}
        if(restored.pair!=null){installedEpoch=restored.epoch;refreshIdentity()}
    }
    suspend fun signIn(username:String,password:String)=runOperation {
        mutable.value=state.value.copy(gate=null)
        ensure(username.trim().isNotBlank() && password.isNotEmpty(),text(CoreStrings.CORE_ENTER_YOUR_EMAIL_OR_USERNAME_AND_PASSWORD))
        ensure(state.value.config!=null && state.value.passwordEnabled,text(CoreStrings.CORE_PASSWORD_SIGN_IN_IS_UNAVAILABLE_ON_THIS_SERVER))
        when(val result=api.login(username.trim(),password)) {
            is DeviceLoginResult.DeviceTokenResponseValue -> {
                acceptPair(TokenPair(result.value.access,result.value.refresh))
            }
            is DeviceLoginResult.MFAChallengeResponseValue -> {
                ensure(result.value.mfaRequired,text(CoreStrings.CORE_RESTART_SIGN_IN_AND_TRY_AGAIN))
                challengeExpiresAt=now()+result.value.expiresIn.coerceAtLeast(0)*1000L
                mutable.value=state.value.copy(mfa=result.value,enrollment=null,error=null,mfaExpiresAtMillis=challengeExpiresAt,expiredMfa=false)
            }
        }
    }
    suspend fun googleSignIn(credential:suspend(String,String)->String)=runOperation {
        mutable.value=state.value.copy(gate=null)
        val client=state.value.googleClientId ?: throw AuthMessage(text(CoreStrings.CORE_GOOGLE_SIGN_IN_IS_NOT_CONFIGURED_FOR_THIS_SERVER))
        ensure(api.nativeSocialReady,text(CoreStrings.CORE_DEVICE_SIGN_IN_IS_NOT_CONFIGURED_FOR_THIS_BUILD_CONTACT_YOUR))
        val result=api.google(client,credential)
        ensure(!result.refresh.isNullOrBlank() && validGrant(result.deviceGrant),text(CoreStrings.CORE_THIS_SERVER_DID_NOT_RETURN_A_DEVICE_SESSION_CONTACT_YOUR_MAK))
        acceptPair(TokenPair(result.access,requireNotNull(result.refresh)))
    }
    suspend fun enroll()=runOperation {
        val challenge=requireChallenge()
        mutable.value=state.value.copy(enrollment=api.enroll(challenge.challenge))
    }
    suspend fun verify(code:String)=runOperation {
        val challenge=requireChallenge()
        ensure(code.trim().length in 6..64,text(CoreStrings.CORE_ENTER_YOUR_AUTHENTICATOR_OR_RECOVERY_CODE))
        val result=api.verify(challenge.challenge,code.trim(),challenge.enrollmentRequired)
        ensure(result.surface!="verification_only" && !result.refresh.isNullOrBlank() && validGrant(result.deviceGrant),
            text(CoreStrings.CORE_THIS_SERVER_DID_NOT_RETURN_A_DEVICE_SESSION_CONTACT_YOUR_MAK))
        persist(TokenPair(result.access,requireNotNull(result.refresh)))
        if (result.recoveryCodes.orEmpty().isNotEmpty()) mutable.value=state.value.copy(recoveryCodes=result.recoveryCodes.orEmpty())
        else finishMfaInternal()
    }
    suspend fun finishMfa()=runOperation { finishMfaInternal() }
    private suspend fun finishMfaInternal() {
        val expected=installedEpoch
        try { refreshIdentity();mutable.value=state.value.copy(mfa=null,enrollment=null,recoveryCodes=emptyList()) }
        catch(f:Exception) {clearInstalled(expected);throw f}
    }
    private suspend fun acceptPair(pair:TokenPair) {
        persist(pair)
        val expected=installedEpoch
        try {refreshIdentity()} catch(f:Exception) {clearInstalled(expected);throw f}
    }
    suspend fun cancelMfa()=runOperation(ignoreThrottle=true) {
        clearInstalled(installedEpoch)
        mutable.value=state.value.copy(mfa=null,enrollment=null,recoveryCodes=emptyList(),error=null,expiredMfa=false,mfaExpiresAtMillis=0)
    }
    suspend fun expireMfa()=runOperation(ignoreThrottle=true) {
        if(state.value.mfa!=null && state.value.recoveryCodes.isEmpty() && now()>=challengeExpiresAt)clearExpiredChallenge()
    }
    suspend fun foreground() {
        if (!state.value.initialized || state.value.user==null || state.value.busy) return
        runOperation {refreshIdentity()}
    }
    /** Navigation and push hints must confirm a fresh server identity, never only cached role text. */
    suspend fun authorizeMakerspace(id:Int):Boolean {
        var authorized=false
        runOperation {
            if(state.value.user==null)return@runOperation
            refreshIdentity()
            authorized=state.value.memberships.any {it.id==id}
        }
        return authorized
    }
    suspend fun authorizeSlug(slug:String,staff:Boolean):Int? {
        var authorized:Int?=null
        runOperation {
            if(state.value.user==null)return@runOperation
            refreshIdentity()
            if(!staff || state.value.staffAllowed)authorized=state.value.memberships.singleOrNull {it.slug==slug}?.id
        }
        return authorized
    }
    fun select(id:Int) {
        if(state.value.memberships.none {it.id==id}) {
            mutable.value=state.value.copy(error=text(CoreStrings.CORE_YOUR_MAKERSPACE_ACCESS_CHANGED_CHOOSE_AGAIN));return
        }
        if(state.value.selectedId!=id)onScopeChanged()
        saveSelection(id);mutable.value=state.value.copy(selectedId=id)
    }
    fun dismissGate() {if(!state.value.busy)mutable.value=state.value.copy(gate=null,error=null)}
    suspend fun signOut()=runOperation(ignoreThrottle=true) {
        val expected=installedEpoch?:tokens.snapshot().epoch
        // Private flows stop before push cleanup/server revocation can suspend.
        onSessionEnded()
        mutable.value=state.value.copy(user=null,selectedId=null,mfa=null,enrollment=null,recoveryCodes=emptyList())
        var failed=false
        try { api.logout() } catch(cancelled:CancellationException) {throw cancelled} catch(_:Exception) {failed=true}
        finally {
            if(tokens.snapshot().epoch==expected)saveSelection(null)
            mutable.value=AuthState(initialized=true,config=state.value.config,
                notice=if(failed) text(CoreStrings.CORE_SIGNED_OUT_ON_THIS_DEVICE_SERVER_REVOCATION_COULD_NOT_BE_CON) else text(CoreStrings.CORE_SIGNED_OUT))
            clearInstalled(expected)
        }
    }
    suspend fun changePassword(current:String,replacement:String,confirmation:String)=runOperation {
        val expected=installedEpoch
        ensure(current.isNotEmpty() && replacement.isNotEmpty(),text(CoreStrings.CORE_ENTER_YOUR_CURRENT_AND_NEW_PASSWORDS))
        ensure(replacement.length>=8,text(CoreStrings.CORE_USE_AT_LEAST_8_CHARACTERS))
        ensure(replacement==confirmation,text(CoreStrings.CORE_PASSWORDS_DON_T_MATCH))
        api.changePassword(current,replacement)
        try {api.logout()} catch(cancelled:CancellationException){throw cancelled} catch(_:Exception) {/* Server changed the credential; local session is always discarded. */}
        finally {
            if(expected==null || tokens.snapshot().epoch==expected)saveSelection(null)
            mutable.value=AuthState(initialized=true,config=state.value.config,notice=text(CoreStrings.CORE_PASSWORD_UPDATED_SIGN_IN_AGAIN))
            clearInstalled(expected)
        }
    }
    private suspend fun persist(pair:TokenPair) {
        ensure(pair.access.isNotBlank() && pair.refresh.isNotBlank(),text(CoreStrings.CORE_THIS_SERVER_DID_NOT_RETURN_A_DEVICE_SESSION_CONTACT_YOUR_MAK))
        withContext(NonCancellable+Dispatchers.IO) {tokens.replace(pair);installedEpoch=tokens.snapshot().epoch}
    }
    private fun rejectChangedSession():Nothing {
        onSessionEnded()
        mutable.value=state.value.copy(user=null,selectedId=null,mfa=null,enrollment=null,recoveryCodes=emptyList(),gate=AuthGate.SESSION_ENDED)
        throw AuthMessage(text(CoreStrings.CORE_YOUR_SESSION_CHANGED_SIGN_IN_AGAIN))
    }
    private fun requireInstalledEpoch():Long {
        val expected=installedEpoch?:tokens.snapshot().epoch.also {installedEpoch=it}
        if(tokens.snapshot().epoch!=expected)rejectChangedSession()
        return expected
    }
    private suspend fun clearInstalled(expected:Long?) {
        if(expected==null)return
        try {withContext(NonCancellable+Dispatchers.IO) {
            val current=tokens.snapshot()
            if(current.epoch==expected)tokens.rotateIfCurrent(current,null)
        }} finally {onSessionEnded()}
    }
    private suspend fun refreshIdentity() {
        val expected=requireInstalledEpoch()
        val user=try {api.me()} catch(f:ApiFailure) {
            if(tokens.snapshot().epoch!=expected)rejectChangedSession()
            if(f.status==401 || f.code=="device_superadmin_not_supported") {
                saveSelection(null);mutable.value=state.value.copy(user=null,selectedId=null,
                    gate=if(f.code=="device_superadmin_not_supported")AuthGate.WEB_CONSOLE else AuthGate.SESSION_ENDED)
                clearInstalled(expected)
            }
            throw f
        }
        if(tokens.snapshot().epoch!=expected)rejectChangedSession()
        if (user.isSuperuser || user.role=="superadmin") {
            saveSelection(null)
            mutable.value=state.value.copy(user=null,selectedId=null,gate=AuthGate.WEB_CONSOLE)
            clearInstalled(expected)
            throw ApiFailure(403,text(CoreStrings.CORE_USE_THE_WEB_CONSOLE),"device_superadmin_not_supported",null)
        }
        if(state.value.user?.id!=null && state.value.user?.id!=user.id)onSessionEnded()
        val allowed=user.makerspaces.filter {it.source==AuthMembershipSourceEnum.membership}
        val selected=state.value.selectedId?.takeIf {id->allowed.any {it.id==id}}
            ?: readSelection()?.takeIf {id->allowed.any {it.id==id}} ?: allowed.singleOrNull()?.id
        if(state.value.selectedId!=selected)onScopeChanged()
        saveSelection(selected)
        mutable.value=state.value.copy(user=user,selectedId=selected,mfa=null,enrollment=null,error=null)
    }
    private fun requireChallenge():MFAChallengeResponse {
        if(now()>=challengeExpiresAt){clearExpiredChallenge();throw AuthMessage(text(CoreStrings.CORE_YOUR_VERIFICATION_SESSION_EXPIRED_SIGN_IN_TO_REQUEST_A_NEW_O))}
        return state.value.mfa ?: throw AuthMessage(text(CoreStrings.CORE_RESTART_SIGN_IN_AND_TRY_AGAIN))
    }
    private fun clearExpiredChallenge() {mutable.value=state.value.copy(mfa=null,enrollment=null,recoveryCodes=emptyList(),expiredMfa=true,mfaExpiresAtMillis=0,error=null)}
    private fun validGrant(grant:Map<String,JsonElement>?):Boolean {
        val id=(grant?.get("id") as? JsonPrimitive)?.contentOrNull
        return try {id!=null && java.util.UUID.fromString(id).toString()==id.lowercase()}catch(_:IllegalArgumentException){false}
    }
    private suspend fun runOperation(ignoreThrottle:Boolean=false,block:suspend()->Unit) {
        if(!operation.tryLock()) return
        try {
            if(!ignoreThrottle && now()<retryAt) return
            if(state.value.user!=null)requireInstalledEpoch()
            mutable.value=state.value.copy(busy=true,error=null,notice=null)
            block()
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(_:AuthCancelled) {mutable.value=state.value.copy(error=null,notice=text(CoreStrings.CORE_SIGN_IN_CANCELLED))}
        catch(f:Exception) {
            if(f is ApiFailure && f.status==429) retryAt=now()+(f.retryAfterSeconds?:60)*1000L
            mutable.value=state.value.copy(retryAtMillis=retryAt,
                gate=if(f is ApiFailure && f.code=="device_superadmin_not_supported")AuthGate.WEB_CONSOLE else state.value.gate,
                error=when(f){is MobileCopyException->text.failure(f);is ApiFailure->text.apiFailure(f);is AuthMessage->f.message ?: text(CoreStrings.CORE_COULDN_T_CONNECT_TRY_AGAIN);else->text(CoreStrings.CORE_COULDN_T_CONNECT_TRY_AGAIN)})
        } finally {mutable.value=state.value.copy(busy=false);operation.unlock()}
    }
}
