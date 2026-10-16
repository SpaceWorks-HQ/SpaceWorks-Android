package org.spaceworks.app.auth

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.spaceworks.app.network.*
import org.spaceworks.generated.model.*

class AuthMessage(message:String):Exception(message)
private fun ensure(value:Boolean,message:String) {if(!value) throw AuthMessage(message)}

data class AuthState(val busy:Boolean=false,val initialized:Boolean=false,val config:PublicConfig?=null,
    val user:AuthUserPayload?=null,val selectedId:Int?=null,val mfa:MFAChallengeResponse?=null,
    val enrollment:MFAEnrollment?=null,val recoveryCodes:List<String> = emptyList(),
    val error:String?=null,val notice:String?=null,val retryAtMillis:Long=0) {
    val memberships get()=user?.makerspaces.orEmpty().filter {it.source==AuthMembershipSourceEnum.membership}
    val selected get()=memberships.firstOrNull {it.id==selectedId}
    val passwordEnabled get()=(config?.passwordLogin?.get("enabled") as? JsonPrimitive)?.booleanOrNull != false &&
        (config?.memberAccounts?.get("enabled") as? JsonPrimitive)?.booleanOrNull != false
    // The owner contract has no native session_authority yet. Role/action text alone cannot grant native staff tools.
    val staffAllowed get()=false
}
class AuthStateHolder(private val api:AuthGateway,private val tokens:TokenStore,
    private val readSelection:()->Int?={null},private val saveSelection:(Int?)->Unit={},
    private val now:()->Long=System::currentTimeMillis) {
    private val mutable=MutableStateFlow(AuthState())
    val state=mutable.asStateFlow()
    private val operation=Mutex()
    private var retryAt=0L
    private var challengeExpiresAt=0L
    suspend fun initialize()=runOperation {
        val config=api.config()
        mutable.value=state.value.copy(config=config,initialized=true)
        if (withContext(Dispatchers.IO){tokens.read()}!=null) refreshIdentity()
    }
    suspend fun signIn(username:String,password:String)=runOperation {
        ensure(username.trim().isNotBlank() && password.isNotEmpty(),"Enter your email or username and password.")
        ensure(state.value.config!=null && state.value.passwordEnabled,"Password sign-in is unavailable on this server.")
        when(val result=api.login(username.trim(),password)) {
            is DeviceLoginResult.DeviceTokenResponseValue -> {
                acceptPair(TokenPair(result.value.access,result.value.refresh))
            }
            is DeviceLoginResult.MFAChallengeResponseValue -> {
                ensure(result.value.mfaRequired,"Restart sign-in and try again.")
                challengeExpiresAt=now()+result.value.expiresIn.coerceAtLeast(0)*1000L
                mutable.value=state.value.copy(mfa=result.value,enrollment=null,error=null)
            }
        }
    }
    suspend fun enroll()=runOperation {
        val challenge=requireChallenge()
        mutable.value=state.value.copy(enrollment=api.enroll(challenge.challenge))
    }
    suspend fun verify(code:String)=runOperation {
        val challenge=requireChallenge()
        ensure(code.trim().length in 6..64,"Enter your authenticator or recovery code.")
        val result=api.verify(challenge.challenge,code.trim(),challenge.enrollmentRequired)
        val grantId=(result.deviceGrant?.get("id") as? JsonPrimitive)?.contentOrNull
        val validGrant=try {grantId!=null && java.util.UUID.fromString(grantId).toString()==grantId.lowercase()}catch(_:IllegalArgumentException){false}
        ensure(result.surface!="verification_only" && !result.refresh.isNullOrBlank() && validGrant,
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
        mutable.value=state.value.copy(mfa=null,enrollment=null,recoveryCodes=emptyList(),error=null)
    }
    suspend fun foreground() {
        if (!state.value.initialized || state.value.user==null || state.value.busy) return
        runOperation {refreshIdentity()}
    }
    fun select(id:Int) {
        require(state.value.memberships.any {it.id==id})
        saveSelection(id);mutable.value=state.value.copy(selectedId=id)
    }
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
        ensure(replacement==confirmation,"The new passwords do not match.")
        api.changePassword(current,replacement)
        try {api.logout()} catch(cancelled:CancellationException){throw cancelled} catch(_:Exception) {/* Server changed the credential; local session is always discarded. */}
        finally {
            saveSelection(null)
            mutable.value=AuthState(initialized=true,config=state.value.config,notice="Password updated. Sign in again.")
            withContext(NonCancellable+Dispatchers.IO){tokens.replace(null)}
        }
    }
    private suspend fun persist(pair:TokenPair)=withContext(NonCancellable+Dispatchers.IO) {tokens.replace(pair)}
    private suspend fun refreshIdentity() {
        val user=try {api.me()} catch(f:ApiFailure) {
            if(f.status==401 || f.code=="device_superadmin_not_supported") {
                saveSelection(null);mutable.value=state.value.copy(user=null,selectedId=null)
                withContext(NonCancellable+Dispatchers.IO){tokens.replace(null)}
            }
            throw f
        }
        if (user.isSuperuser || user.role=="superadmin") {
            saveSelection(null)
            mutable.value=state.value.copy(user=null,selectedId=null)
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
        ensure(now()<challengeExpiresAt,"Your sign-in expired. Start again.")
        return state.value.mfa ?: throw AuthMessage("Restart sign-in and try again.")
    }
    private suspend fun runOperation(ignoreThrottle:Boolean=false,block:suspend()->Unit) {
        if(!operation.tryLock()) return
        try {
            if(!ignoreThrottle && now()<retryAt) return
            mutable.value=state.value.copy(busy=true,error=null,notice=null)
            block()
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(f:Exception) {
            if(f is ApiFailure && f.status==429) retryAt=now()+(f.retryAfterSeconds?:60)*1000L
            mutable.value=state.value.copy(retryAtMillis=retryAt,error=when(f){is ApiFailure->f.detail;is AuthMessage->f.message ?: "Couldn't connect. Try again.";else->"Couldn't connect. Try again."})
        } finally {mutable.value=state.value.copy(busy=false);operation.unlock()}
    }
}
