package org.spaceworks.app.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.platform.MobileText
import java.util.Collections

/** Origin is the controller's canonical accepted origin; epoch identifies a credential installation. */
data class AccountOwnerIdentity(val origin:String,val userId:String,val installationEpoch:Long) {
    init { require(origin.isNotBlank()&&userId.isNotBlank()&&installationEpoch>=0) }
    override fun toString()="AccountOwnerIdentity(redacted)"
}

/** Transient one-time completion, never saved/logged; codes are an immutable defensive copy. */
class AccountSecurityCompletion(codes:List<String>,val notice:String?) {
    val codes:List<String> = Collections.unmodifiableList(ArrayList(codes))
    override fun toString()="AccountSecurityCompletion(redacted)"
}

class AccountManagementContext internal constructor(
    val identity:AccountOwnerIdentity,
    val hasSession:()->Boolean,
    val selected:()->MemberScope?,
    val phoneCooldown:AccountPhoneCooldown,
    val onSignedOut:suspend()->Unit,
    val onMembershipChanged:suspend()->Unit,
    val onConfirmedMembershipLeft:suspend(MemberScope)->Unit,
    val onSecurityEnded:suspend(List<String>,String?)->Unit,
    val onPasswordChanged:suspend()->Unit,
)

/**
 * Call on the controller's serialized main dispatcher. Lazy and account-owned: no Context/store/SDK.
 * Native auth adapters must honor their explicit expected identity before starting work.
 */
class AccountManagementOwner(
    private val readIdentity:()->AccountOwnerIdentity?,
    private val readSelected:()->MemberScope?,
    private val endedInstallation:(AccountOwnerIdentity)->Boolean,
    private val endLocalSession:suspend(AccountOwnerIdentity,String?)->Boolean,
    private val foreground:suspend(AccountOwnerIdentity)->Unit,
    private val dropConfirmedMembership:(AccountOwnerIdentity,Int)->Boolean,
    private val gatewayFactory:(AccountManagementContext)->AccountGateway,
    private val now:()->Long=System::currentTimeMillis,
    private val text:(AccountCopy)->String={it.english},
    private val failureText:MobileText=MobileText.ENGLISH,
) {
    private data class Binding(val identity:AccountOwnerIdentity,val token:Any,val holder:AccountManagementStateHolder)
    private data class AccountKey(val origin:String,val userId:String)
    private val cooldowns=mutableMapOf<AccountKey,AccountPhoneCooldown>()
    private var binding:Binding?=null
    private var generation=0L
    private var completionRevision=0L
    private var resetRevision=0L
    private var endingToken:Any?=null
    private val mutableCompletion=MutableStateFlow<AccountSecurityCompletion?>(null)
    val completion=mutableCompletion.asStateFlow()

    private fun owns(identity:AccountOwnerIdentity,token:Any)=binding?.let{it.identity==identity&&it.token===token}==true&&readIdentity()==identity

    fun current():AccountManagementStateHolder? {
        val identity=readIdentity()?:run{binding?.holder?.clear();binding=null;return null}
        binding?.takeIf{it.identity==identity}?.let{return it.holder}
        // Private state disappears before constructing a replacement gateway or invoking its factory.
        resetAccount()
        val token=Any();val installedGeneration=++generation
        val cooldown=cooldowns.getOrPut(AccountKey(identity.origin,identity.userId)){AccountPhoneCooldown()}
        suspend fun refresh(){if(owns(identity,token))foreground(identity)}
        suspend fun finish(codes:List<String>,notice:String?,allowAlreadyEnded:Boolean=false) {
            val ownedEnd=allowAlreadyEnded&&generation==installedGeneration&&readIdentity()==null&&
                (binding==null||binding?.token===token)&&endedInstallation(identity)
            if((!owns(identity,token)&&!ownedEnd)||endingToken===token)return
            val privateCodes=codes.toList()
            val visibility=completionRevision
            val resets=resetRevision
            endingToken=token
            val accepted=try{endLocalSession(identity,notice)}finally{if(endingToken===token)endingToken=null}
            // Auth intentionally calls resetAccount synchronously before wiping credentials.
            // A later login/bind, a replacement CAS or an origin change must never show old codes.
            if(!accepted||generation!=installedGeneration||completionRevision!=visibility||
                resetRevision !in resets..(resets+1)||readIdentity()!=null||!endedInstallation(identity))return
            binding?.takeIf{it.token===token}?.holder?.clear()
            binding=null
            mutableCompletion.value=AccountSecurityCompletion(privateCodes,notice)
        }
        val context=AccountManagementContext(identity,
            hasSession={owns(identity,token)},
            selected={if(owns(identity,token))readSelected()else null},
            phoneCooldown=cooldown,
            onSignedOut={finish(emptyList(),null,allowAlreadyEnded=true)},
            onMembershipChanged={refresh()},
            onConfirmedMembershipLeft={scope->
                if(owns(identity,token)&&dropConfirmedMembership(identity,scope.id)&&owns(identity,token))foreground(identity)
            },
            onSecurityEnded={codes,notice->finish(codes,notice)},
            onPasswordChanged={refresh()})
        val gateway=gatewayFactory(context)
        val holder=AccountManagementStateHolder(gateway,
            onSignedOut=context.onSignedOut,onMembershipChanged=context.onMembershipChanged,
            onSecurityEnded=context.onSecurityEnded,now=now,phoneCooldown=cooldown,text=text,
            onPasswordChanged=context.onPasswordChanged,failureText=failureText,
            onConfirmedMembershipLeft=context.onConfirmedMembershipLeft)
        binding=Binding(identity,token,holder)
        return holder
    }

    /** Before selected-maker publication; retains account binding and phone deadline. */
    fun clearScope(){binding?.holder?.clear()}
    /** Before account/server/installation replacement and logout. */
    fun resetAccount(){resetRevision++;binding?.holder?.clear();binding=null;mutableCompletion.value=null}
    fun acknowledgeCompletion(){completionRevision++;mutableCompletion.value=null}
    fun background(){acknowledgeCompletion()}
}
