package org.spaceworks.app.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import org.spaceworks.app.auth.requireBody
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.network.ApiSession
import org.spaceworks.app.network.Errors
import org.spaceworks.generated.api.AccountsApi
import org.spaceworks.generated.api.AuthApi
import org.spaceworks.generated.api.ReauthenticationApi
import org.spaceworks.generated.model.AccountDeletionRefused
import org.spaceworks.generated.model.AccountDeletionRequest
import org.spaceworks.generated.model.ConfirmEnum
import org.spaceworks.generated.model.Reauthentication
import org.spaceworks.generated.model.ReauthenticationFactor
import org.spaceworks.generated.model.ReauthenticationResult
import org.spaceworks.generated.model.ReauthenticationSocialComplete
import org.spaceworks.generated.model.ReauthenticationSocialStart
import org.spaceworks.generated.model.AccountDeletionBlocker as BlockerWire
import org.spaceworks.generated.model.AccountDeletionPreview as PreviewWire

/** Immutable screen projection of one server blocker; unknown codes keep their server detail. */
data class DeletionBlocker(val code:String,val makerspaceId:Int?,val makerspaceSlug:String?,val organizationUrl:String?,val count:Int,val detail:String) {
    val fix:AccountFixDestination get()=when(code) {
        "unpaid_charges","refund_in_progress","deposit_held" -> AccountFixDestination.PAYMENTS
        "active_loans","open_requests","problem_report_open" -> AccountFixDestination.ACTIVITY
        "upcoming_bookings" -> AccountFixDestination.BOOKINGS
        "upcoming_registrations" -> AccountFixDestination.EVENTS
        "last_manager" -> AccountFixDestination.STAFF
        else -> AccountFixDestination.SUPPORT
    }
}
data class DeletionPreview(val deletable:Boolean,val restorable:Boolean,val restoreReason:String,val blockers:List<DeletionBlocker>,
    val reauthRequired:Boolean,val methods:List<String>)
sealed interface DeletionProof {
    data class Verified(val expiresInSeconds:Int):DeletionProof
    /** A provider proof that still needs a second factor of [kind] (totp, phone or email_code). */
    data class Factor(val token:String,val kind:String):DeletionProof { override fun toString()="Factor(redacted)" }
}
class PhoneProofToken(val token:String) { override fun toString()="PhoneProofToken(redacted)" }
class SocialProofIntent(val provider:String,val state:String,val nonce:String) { override fun toString()="SocialProofIntent(redacted)" }
sealed interface DeletionOutcome {
    data class Deleted(val deletedAt:String,val purgeAfter:String):DeletionOutcome
    data class Blocked(val blockers:List<DeletionBlocker>):DeletionOutcome
}

interface AccountDeletionGateway {
    suspend fun preview():DeletionPreview
    suspend fun password(password:String,code:String?):DeletionProof
    suspend fun startPhone():PhoneProofToken
    suspend fun confirmPhone(token:PhoneProofToken,code:String):DeletionProof
    suspend fun startSocial(provider:String):SocialProofIntent
    suspend fun completeSocial(intent:SocialProofIntent,idToken:String):DeletionProof
    suspend fun factor(token:String,code:String):DeletionProof
    suspend fun delete():DeletionOutcome
}

/** Account-global generated operations only: no makerspace header and no fabricated scope. */
class GeneratedAccountDeletionGateway(private val session:ApiSession,private val hasSession:()->Boolean):AccountDeletionGateway {
    private val owningEpoch=session.installationEpoch
    private val json=Json {ignoreUnknownKeys=true;explicitNulls=false}
    private fun <T> api(type:Class<T>):T {
        if(!hasSession())throw ApiFailure(401,"Please sign in again.","session_expired",null)
        if(session.installationEpoch!=owningEpoch)throw CancellationException("Account installation changed")
        return session.pinnedGlobalApi(type,owningEpoch) {call->if(session.installationEpoch!=owningEpoch)call.cancel()}
    }
    private fun blocker(row:BlockerWire)=DeletionBlocker(row.code,row.makerspaceId,row.makerspaceSlug,row.organizationUrl,row.count,row.detail)
    private fun proof(result:ReauthenticationResult):DeletionProof {
        val token=result.factorToken
        val kind=result.factorRequired?.value
        if(!result.verified && token!=null && kind!=null)return DeletionProof.Factor(token,kind)
        if(!result.verified)throw ApiFailure(0,"Verification failed. Check your password or code.","reauth_failed",null)
        return DeletionProof.Verified(result.expiresIn)
    }
    /** A wrong password is also 401: after the core's single rotation, a valid identity means it was the credential. */
    private suspend fun <T> verifying(block:suspend()->T):T = try {block()} catch(failure:ApiFailure) {
        if(failure.status!=401 || !hasSession())throw failure
        try {api(AuthApi::class.java).apiV1AuthMeRetrieve().requireBody()}
        catch(cancelled:CancellationException){throw cancelled} catch(_:Exception){throw failure}
        throw failure.copy(status=0,code="reauth_failed")
    }
    override suspend fun preview():DeletionPreview {
        val value:PreviewWire=api(AccountsApi::class.java).apiV1AuthAccountDeletionRetrieve().requireBody()
        return DeletionPreview(value.deletable,value.restorable,value.restoreReason,value.blockers.map(::blocker),value.reauth.required,value.reauth.methods)
    }
    override suspend fun password(password:String,code:String?)=verifying {
        proof(api(ReauthenticationApi::class.java).apiV1AuthReauthCreate(Reauthentication(password.takeIf {it.isNotEmpty()},code?.takeIf {it.isNotBlank()})).requireBody())
    }
    override suspend fun startPhone()=PhoneProofToken(api(ReauthenticationApi::class.java).apiV1AuthReauthPhoneStartCreate().requireBody().token)
    override suspend fun confirmPhone(token:PhoneProofToken,code:String)=verifying {
        proof(api(ReauthenticationApi::class.java).apiV1AuthReauthPhoneConfirmCreate(ReauthenticationFactor(token.token,code)).requireBody())
    }
    override suspend fun startSocial(provider:String):SocialProofIntent {
        val started=api(ReauthenticationApi::class.java).apiV1AuthReauthSocialStartCreate(ReauthenticationSocialStart(provider)).requireBody()
        return SocialProofIntent(provider,started.state,started.nonce)
    }
    override suspend fun completeSocial(intent:SocialProofIntent,idToken:String)=verifying {
        proof(api(ReauthenticationApi::class.java).apiV1AuthReauthSocialCompleteCreate(ReauthenticationSocialComplete(intent.state,intent.nonce,idToken=idToken)).requireBody())
    }
    override suspend fun factor(token:String,code:String)=verifying {
        proof(api(ReauthenticationApi::class.java).apiV1AuthReauthSocialFactorCreate(ReauthenticationFactor(token,code)).requireBody())
    }
    override suspend fun delete():DeletionOutcome {
        val response=api(AccountsApi::class.java).apiV1AuthAccountDeletionCreate(AccountDeletionRequest(ConfirmEnum.entries.single {it.value=="DELETE"}))
        if(response.isSuccessful) {
            val body=response.body()?:throw IllegalStateException("Empty API response")
            return DeletionOutcome.Deleted(body.deletedAt.toString(),body.purgeAfter.toString())
        }
        val raw=response.errorBody()?.string()
        // 409 and a blocker-shaped 403 carry a fresh server list; anything else is an ordinary failure.
        if(response.code() in setOf(403,409))runCatching {json.decodeFromString(AccountDeletionRefused.serializer(),requireNotNull(raw))}.getOrNull()
            ?.let {return DeletionOutcome.Blocked(it.blockers.map(::blocker))}
        throw Errors.map(response.code(),raw,response.headers()["Retry-After"])
    }
}

enum class DeletionStage { LOADING, BLOCKERS, REAUTHENTICATE, FACTOR, CONFIRM, FINISHED }
enum class DeletionMessage { PROOF_FAILED, PROOF_EXPIRED, CONFIRM_INVALID, CONFLICT, CODE_REQUIRED, PASSWORD_REQUIRED, PROVIDER_UNAVAILABLE }
data class DeletionState(val stage:DeletionStage=DeletionStage.LOADING,val preview:DeletionPreview?=null,val busy:Boolean=false,
    val phoneCodeSent:Boolean=false,val factorKind:String?=null,val proofExpiresAt:Long?=null,
    val message:DeletionMessage?=null,val error:String?=null,val retryAtMillis:Long=0,val deleted:DeletionOutcome.Deleted?=null) {
    val methods get()=preview?.methods.orEmpty()
    val passwordAvailable get()="password" in methods
    val totpRequired get()="totp" in methods
    val phoneAvailable get()="phone" in methods
    /** Native reauthentication supports linked Google identities; other providers verify on the web. */
    val socialProviders get()=methods.filter {it=="google"}
}

/** Server-authoritative whole-account deletion. Credentials and codes are arguments only, never state. */
class AccountDeletionStateHolder(private val api:AccountDeletionGateway,private val now:()->Long=System::currentTimeMillis,
    private val onDeleted:suspend(DeletionOutcome.Deleted)->Unit={},private val failureText:(ApiFailure)->String={it.detail}) {
    private val mutable=MutableStateFlow(DeletionState());val state=mutable.asStateFlow()
    private var phoneToken:PhoneProofToken?=null
    private var factorToken:String?=null
    private var generation=0L
    fun clear(){generation++;phoneToken=null;factorToken=null;mutable.value=DeletionState()}
    private suspend fun operation(block:suspend(Long)->Unit) {
        if(state.value.busy || now()<state.value.retryAtMillis)return
        val owner=generation
        mutable.update {it.copy(busy=true,error=null,message=null)}
        try {block(owner)}
        catch(cancelled:CancellationException){throw cancelled}
        catch(failure:ApiFailure) {
            if(owner!=generation)return
            val retry=if(failure.status==429)now()+(failure.retryAfterSeconds?:60)*1000 else state.value.retryAtMillis
            when {
                failure.code=="reauth_failed" -> mutable.update {it.copy(message=DeletionMessage.PROOF_FAILED,retryAtMillis=retry)}
                failure.code=="recent_reauth_required" -> mutable.update {it.copy(stage=DeletionStage.REAUTHENTICATE,proofExpiresAt=null,message=DeletionMessage.PROOF_EXPIRED)}
                failure.code=="deletion_retry" -> {mutable.update {it.copy(error=failureText(failure))};reload(owner)}
                else -> mutable.update {it.copy(error=failureText(failure),retryAtMillis=retry)}
            }
        }
        catch(_:Exception){if(owner==generation)mutable.update {it.copy(message=DeletionMessage.PROVIDER_UNAVAILABLE)}}
        finally {if(owner==generation)mutable.update {it.copy(busy=false)}}
    }
    private suspend fun reload(owner:Long) {
        val preview=api.preview()
        if(owner!=generation)return
        val stage=when {
            !preview.deletable -> DeletionStage.BLOCKERS
            preview.reauthRequired -> DeletionStage.REAUTHENTICATE
            else -> DeletionStage.CONFIRM
        }
        mutable.update {it.copy(stage=stage,preview=preview,phoneCodeSent=false,factorKind=null,proofExpiresAt=if(stage==DeletionStage.CONFIRM)it.proofExpiresAt else null)}
    }
    suspend fun load()=operation {reload(it)}
    private suspend fun accept(owner:Long,proof:DeletionProof) {
        if(owner!=generation)return
        when(proof) {
            is DeletionProof.Factor -> {factorToken=proof.token;mutable.update {it.copy(stage=DeletionStage.FACTOR,factorKind=proof.kind)}}
            is DeletionProof.Verified -> {
                phoneToken=null;factorToken=null
                mutable.update {it.copy(proofExpiresAt=now()+proof.expiresInSeconds.coerceAtLeast(0)*1000L)}
                // Blockers can appear while verifying; the confirmation step follows a fresh preview.
                reload(owner)
                if(state.value.stage==DeletionStage.REAUTHENTICATE)mutable.update {it.copy(stage=DeletionStage.CONFIRM)}
            }
        }
    }
    suspend fun verifyPassword(password:String,code:String)=operation {owner->
        if(state.value.stage!=DeletionStage.REAUTHENTICATE)return@operation
        val s=state.value
        if(s.passwordAvailable && password.isEmpty()){mutable.update {it.copy(message=DeletionMessage.PASSWORD_REQUIRED)};return@operation}
        if(s.totpRequired && !Regex("\\d{6}").matches(code.trim())){mutable.update {it.copy(message=DeletionMessage.CODE_REQUIRED)};return@operation}
        accept(owner,api.password(password,code.trim().takeIf {s.totpRequired}))
    }
    suspend fun sendPhoneCode()=operation {owner->
        if(state.value.stage!=DeletionStage.REAUTHENTICATE)return@operation
        val token=api.startPhone()
        if(owner==generation){phoneToken=token;mutable.update {it.copy(phoneCodeSent=true)}}
    }
    suspend fun verifyPhone(code:String)=operation {owner->
        if(state.value.stage!=DeletionStage.REAUTHENTICATE)return@operation
        val token=phoneToken
        if(token==null){mutable.update {it.copy(phoneCodeSent=false)};return@operation}
        if(!Regex("\\d{6}").matches(code.trim())){mutable.update {it.copy(message=DeletionMessage.CODE_REQUIRED)};return@operation}
        accept(owner,api.confirmPhone(token,code.trim()))
    }
    /** [credential] obtains a fresh provider ID token bound to the server nonce. */
    suspend fun verifySocial(provider:String,credential:suspend(String)->String)=operation {owner->
        if(state.value.stage!=DeletionStage.REAUTHENTICATE)return@operation
        val intent=api.startSocial(provider)
        if(owner!=generation)return@operation
        accept(owner,api.completeSocial(intent,credential(intent.nonce)))
    }
    suspend fun verifyFactor(code:String)=operation {owner->
        if(state.value.stage!=DeletionStage.FACTOR)return@operation
        val token=factorToken?:run {mutable.update {it.copy(stage=DeletionStage.REAUTHENTICATE,factorKind=null)};return@operation}
        if(code.trim().length !in 6..64){mutable.update {it.copy(message=DeletionMessage.CODE_REQUIRED)};return@operation}
        accept(owner,api.factor(token,code.trim()))
    }
    fun expireProof() {
        val expires=state.value.proofExpiresAt?:return
        if(state.value.stage==DeletionStage.CONFIRM && now()>=expires)
            mutable.update {it.copy(stage=DeletionStage.REAUTHENTICATE,proofExpiresAt=null,message=DeletionMessage.PROOF_EXPIRED)}
    }
    suspend fun confirm(typed:String)=operation {owner->
        expireProof()
        if(state.value.stage!=DeletionStage.CONFIRM)return@operation
        if(typed!="DELETE"){mutable.update {it.copy(message=DeletionMessage.CONFIRM_INVALID)};return@operation}
        when(val outcome=api.delete()) {
            is DeletionOutcome.Blocked -> if(owner==generation)mutable.update {
                it.copy(stage=DeletionStage.BLOCKERS,preview=it.preview?.copy(deletable=false,blockers=outcome.blockers),proofExpiresAt=null,message=DeletionMessage.CONFLICT)
            }
            is DeletionOutcome.Deleted -> {
                if(owner!=generation)return@operation
                mutable.update {it.copy(stage=DeletionStage.FINISHED,deleted=outcome,proofExpiresAt=null)}
                onDeleted(outcome)
            }
        }
    }
    fun backToMethods(){if(!state.value.busy){phoneToken=null;factorToken=null;mutable.update {it.copy(stage=DeletionStage.REAUTHENTICATE,phoneCodeSent=false,factorKind=null,message=null)}}}
}
