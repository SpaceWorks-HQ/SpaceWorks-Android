package org.spaceworks.app.auth

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
    private val now:()->Long=System::currentTimeMillis) {
    private val mutable=MutableStateFlow(AuthState())
    val state=mutable.asStateFlow()
    private val operation=Mutex()
    private var retryAt=0L
    private var challengeExpiresAt=0L
    val nativeSocialReady get()=api.nativeSocialReady
    suspend fun initialize()=runOperation {
        val config=api.config()
        mutable.value=state.value.copy(config=config,initialized=true)
        if (withContext(Dispatchers.IO){tokens.read()}!=null) refreshIdentity()
    }
    suspend fun signIn(username:String,password:String)=runOperation {
        mutable.value=state.value.copy(gate=null)
        ensure(username.trim().isNotBlank() && password.isNotEmpty(),"Enter your email or username and password.")
        ensure(state.value.config!=null && state.value.passwordEnabled,"Password sign-in is unavailable on this server.")
        when(val result=api.login(username.trim(),password)) {
            is DeviceLoginResult.DeviceTokenResponseValue -> {
                acceptPair(TokenPair(result.value.access,result.value.refresh))
            }
            is DeviceLoginResult.MFAChallengeResponseValue -> {
                ensure(result.value.mfaRequired,"Restart sign-in and try again.")
                challengeExpiresAt=now()+result.value.expiresIn.coerceAtLeast(0)*1000L
                mutable.value=state.value.copy(mfa=result.value,enrollment=null,error=null,mfaExpiresAtMillis=challengeExpiresAt,expiredMfa=false)
            }
        }
    }
    suspend fun googleSignIn(credential:suspend(String,String)->String)=runOperation {
        mutable.value=state.value.copy(gate=null)
        val client=state.value.googleClientId ?: throw AuthMessage("Google sign-in is not configured for this server.")
        ensure(api.nativeSocialReady,"Device sign-in is not configured for this build. Contact your makerspace.")
        val result=api.google(client,credential)
        ensure(!result.refresh.isNullOrBlank() && validGrant(result.deviceGrant),"This server did not return a device session. Contact your makerspace.")
        acceptPair(TokenPair(result.access,requireNotNull(result.refresh)))
    }
    suspend fun enroll()=runOperation {
        val challenge=requireChallenge()
        mutable.value=state.value.copy(enrollment=api.enroll(challenge.challenge))
    }
    suspend fun verify(code:String)=runOperation {
        val challenge=requireChallenge()
        ensure(code.trim().length in 6..64,"Enter your authenticator or recovery code.")
        val result=api.verify(challenge.challenge,code.trim(),challenge.enrollmentRequired)
        ensure(result.surface!="verification_only" && !result.refresh.isNullOrBlank() && validGrant(result.deviceGrant),
            "This server did not return a device session. Contact your makerspace.")
        persist(TokenPair(result.access,requireNotNull(result.refresh)))
        if (result.recoveryCodes.orEmpty().isNotEmpty()) mutable.value=state.value.copy(recoveryCodes=result.recoveryCodes.orEmpty())
        else finishMfaInternal()
    }
    suspend fun finishMfa()=runOperation { finishMfaInternal() }
    private suspend fun finishMfaInternal() {
        try { refreshIdentity();mutable.value=state.value.copy(mfa=null,enrollment=null,recoveryCodes=emptyList()) }
        catch(f:Exception) {withContext(NonCancellable+Dispatchers.IO){tokens.replace(null)};throw f}
    }
    private suspend fun acceptPair(pair:TokenPair) {
        persist(pair)
        try {refreshIdentity()} catch(f:Exception) {withContext(NonCancellable+Dispatchers.IO){tokens.replace(null)};throw f}
    }
    suspend fun cancelMfa()=runOperation(ignoreThrottle=true) {
        withContext(NonCancellable+Dispatchers.IO){tokens.replace(null)}
        mutable.value=state.value.copy(mfa=null,enrollment=null,recoveryCodes=emptyList(),error=null,expiredMfa=false,mfaExpiresAtMillis=0)
    }
    suspend fun expireMfa()=runOperation(ignoreThrottle=true) {
        if(state.value.mfa!=null && state.value.recoveryCodes.isEmpty() && now()>=challengeExpiresAt)clearExpiredChallenge()
    }
    suspend fun foreground() {
        if (!state.value.initialized || state.value.user==null || state.value.busy) return
        runOperation {refreshIdentity()}
    }
    fun select(id:Int) {
        require(state.value.memberships.any {it.id==id})
        saveSelection(id);mutable.value=state.value.copy(selectedId=id)
    }
    fun dismissGate() {if(!state.value.busy)mutable.value=state.value.copy(gate=null,error=null)}
    suspend fun signOut()=runOperation(ignoreThrottle=true) {
        var failed=false
        try { api.logout() } catch(cancelled:CancellationException) {throw cancelled} catch(_:Exception) {failed=true}
        finally {
            saveSelection(null)
            mutable.value=AuthState(initialized=true,config=state.value.config,
                notice=if(failed) "Signed out on this device. Server revocation could not be confirmed." else "Signed out.")
            withContext(NonCancellable+Dispatchers.IO){tokens.replace(null)}
        }
    }
    suspend fun changePassword(current:String,replacement:String,confirmation:String)=runOperation {
        ensure(current.isNotEmpty() && replacement.isNotEmpty(),"Enter your current and new passwords.")
        ensure(replacement.length>=8,"Use at least 8 characters.")
        ensure(replacement==confirmation,"Passwords don't match.")
        api.changePassword(current,replacement)
        try {api.logout()} catch(cancelled:CancellationException){throw cancelled} catch(_:Exception) {/* Server changed the credential; local session is always discarded. */}
        finally {
            saveSelection(null)
            mutable.value=AuthState(initialized=true,config=state.value.config,notice="Password updated. Sign in again.")
            withContext(NonCancellable+Dispatchers.IO){tokens.replace(null)}
        }
    }
    private suspend fun persist(pair:TokenPair) {
        ensure(pair.access.isNotBlank() && pair.refresh.isNotBlank(),"This server did not return a device session. Contact your makerspace.")
        withContext(NonCancellable+Dispatchers.IO) {tokens.replace(pair)}
    }
    private suspend fun refreshIdentity() {
        val user=try {api.me()} catch(f:ApiFailure) {
            if(f.status==401 || f.code=="device_superadmin_not_supported") {
                saveSelection(null);mutable.value=state.value.copy(user=null,selectedId=null,
                    gate=if(f.code=="device_superadmin_not_supported")AuthGate.WEB_CONSOLE else AuthGate.SESSION_ENDED)
                withContext(NonCancellable+Dispatchers.IO){tokens.replace(null)}
            }
            throw f
        }
        if (user.isSuperuser || user.role=="superadmin") {
            saveSelection(null)
            mutable.value=state.value.copy(user=null,selectedId=null,gate=AuthGate.WEB_CONSOLE)
            withContext(NonCancellable+Dispatchers.IO){tokens.replace(null)}
            throw ApiFailure(403,"Use the web console","device_superadmin_not_supported",null)
        }
        val allowed=user.makerspaces.filter {it.source==AuthMembershipSourceEnum.membership}
        val selected=state.value.selectedId?.takeIf {id->allowed.any {it.id==id}}
            ?: readSelection()?.takeIf {id->allowed.any {it.id==id}} ?: allowed.singleOrNull()?.id
        saveSelection(selected)
        mutable.value=state.value.copy(user=user,selectedId=selected,mfa=null,enrollment=null,error=null)
    }
    private fun requireChallenge():MFAChallengeResponse {
        if(now()>=challengeExpiresAt){clearExpiredChallenge();throw AuthMessage("Your verification session expired. Sign in to request a new one.")}
        return state.value.mfa ?: throw AuthMessage("Restart sign-in and try again.")
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
            mutable.value=state.value.copy(busy=true,error=null,notice=null)
            block()
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(_:AuthCancelled) {mutable.value=state.value.copy(error=null,notice="Sign-in cancelled.")}
        catch(f:Exception) {
            if(f is ApiFailure && f.status==429) retryAt=now()+(f.retryAfterSeconds?:60)*1000L
            mutable.value=state.value.copy(retryAtMillis=retryAt,
                gate=if(f is ApiFailure && f.code=="device_superadmin_not_supported")AuthGate.WEB_CONSOLE else state.value.gate,
                error=when(f){is ApiFailure->f.detail;is AuthMessage->f.message ?: "Couldn't connect. Try again.";else->"Couldn't connect. Try again."})
        } finally {mutable.value=state.value.copy(busy=false);operation.unlock()}
    }
}
