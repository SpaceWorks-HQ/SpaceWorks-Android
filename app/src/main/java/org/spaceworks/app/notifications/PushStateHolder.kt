package org.spaceworks.app.notifications

import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.MobileCopyException
import org.spaceworks.app.platform.failure
import org.spaceworks.app.copy.CoreStrings
import org.spaceworks.app.copy.apiFailure

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.spaceworks.app.network.ApiFailure
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** Account-local persistence contains registration IDs and hashes, never FCM/access tokens. */
data class PushRecord(val makerspaceId:Int,val deviceId:Int,val tokenHash:String)
interface PushPersistence {
    suspend fun consent(account:String):Boolean
    suspend fun consent(account:String,value:Boolean)
    suspend fun records(account:String):List<PushRecord>
    suspend fun records(account:String,value:List<PushRecord>)
    suspend fun clear(account:String)
}
interface PushGateway {
    suspend fun register(makerspaceId:Int,token:String):Int
    suspend fun remove(makerspaceId:Int,deviceId:Int)
    /** Immutable old installation, supplied only for cleanup after private identity clears. */
    suspend fun remove(makerspaceId:Int,deviceId:Int,expectedEpoch:Long) {remove(makerspaceId,deviceId)}
}
interface PushProvider {
    suspend fun token():String
    suspend fun stop()
    suspend fun token(account:PushAccount):String=token()
    suspend fun stop(account:PushAccount,expectedEpoch:Long?=null) {stop()}
}
enum class PushPreviewScenario { NORMAL, UNAVAILABLE, THROTTLED }
interface PushPreviewControl {fun scenario(value:PushPreviewScenario)}
data class PushAccount(val key:String,val makerspaceIds:Set<Int>,val installationEpoch:Long?=null,
    val lease:org.spaceworks.app.platform.PushOwnerLease?=null)
data class PushState(val consent:Boolean=false,val permission:Boolean=false,val busy:Boolean=false,
    val registered:Set<Int> = emptySet(),val error:String?=null,val retryAtMillis:Long=0)

/** Serialized registration/cleanup. A caller cancellation cannot orphan a completed registration. */
class PushStateHolder(private val api:PushGateway,private val provider:PushProvider,private val persistence:PushPersistence,
    private val now:()->Long=System::currentTimeMillis,
    private val deliveryGate:(Boolean,Set<Int>)->Unit={_,_->},private val text:MobileText=MobileText.ENGLISH,
    private val ownedDeliveryGate:((PushAccount,Boolean,Set<Int>)->Unit)?=null) {
    constructor(api:PushGateway,provider:PushProvider,persistence:PushPersistence,now:()->Long=System::currentTimeMillis,deliveryGate:(Boolean,Set<Int>)->Unit)
        :this(api,provider,persistence,now,deliveryGate,MobileText.ENGLISH)

    private val mutable=MutableStateFlow(PushState());val state=mutable.asStateFlow()
    private val lock=Mutex();private val generation=AtomicLong();@Volatile private var account:PushAccount?=null
    @Volatile private var attached=false
    @Volatile private var active=false
    private var permitted=false
    private val deadlines=mutableMapOf<String,Long>()

    private fun publish(owner:PushAccount?,enabled:Boolean,makers:Set<Int>) {
        val owned=ownedDeliveryGate
        if(owned!=null) {if(owner!=null)owned(owner,enabled,makers)} else deliveryGate(enabled,makers)
    }
    suspend fun activate(next:PushAccount?,permission:Boolean) {
        val request=generation.incrementAndGet();active=false;attached=false;publish(account,false,emptySet())
        lock.withLock {withContext(NonCancellable) {
            if(request!=generation.get())return@withContext
            val previous=account
            if(previous?.key!=next?.key && previous!=null) {
                // The next account's credentials cannot delete the previous account's records.
                // Authenticated logout uses disable() before grant revocation; retain any failures.
                try {if(persistence.consent(previous.key) || persistence.records(previous.key).isNotEmpty())withTimeout(20_000){provider.stop(previous)}}
                catch(_:Exception){/* The local delivery gate is already closed; IDs remain for the owner account. */}
            }
            account=next;permitted=permission;attached=next!=null
            if(next==null){mutable.value=PushState();return@withContext}
            require(next.key.isNotBlank() && next.makerspaceIds.all {it>0})
            val consent=persistence.consent(next.key)
            mutable.value=PushState(consent,permission,retryAtMillis=deadlines[next.key]?:0)
            active=consent&&permission&&next.makerspaceIds.isNotEmpty()&&request==generation.get()
            if(next.makerspaceIds.isEmpty()){cleanup(next,preserveConsent=true);return@withContext}
            if(consent && permission && request==generation.get())synchronize(next,request,null)
            else if(!consent || !permission) {
                val removed=cleanup(next,preserveConsent=true)
                if(!removed)mutable.update {it.copy(error=text(CoreStrings.CORE_NOTIFICATIONS_ARE_OFF_ON_THIS_DEVICE_SERVER_REMOVAL_COULD_NO))}
            }
        }}
    }
    /** Detach local delivery immediately before entering the isolated debug demo; no SDK/API calls. */
    fun pause() {
        generation.incrementAndGet();active=false;attached=false;publish(account,false,emptySet())
        mutable.update {it.copy(busy=false,registered=emptySet())}
    }
    suspend fun enable(permission:Boolean) {
        val next=account?:return
        val request=generation.get()
        lock.withLock {withContext(NonCancellable) {
            if(!attached || account?.key!=next.key || request!=generation.get())return@withContext
            persistence.consent(next.key,true);permitted=permission;active=permission&&next.makerspaceIds.isNotEmpty()
            mutable.update {it.copy(consent=true,permission=permission)}
            if(next.makerspaceIds.isEmpty()){publish(next,false,emptySet());cleanup(next,preserveConsent=true);mutable.update {it.copy(registered=emptySet())};return@withContext}
            if(permission)synchronize(next,request,null)
            else {publish(next,false,emptySet());cleanup(next,preserveConsent=true);mutable.update {it.copy(registered=emptySet())}}
        }}
    }
    /** Call before generated device logout, while the authenticated session can still delete IDs. */
    suspend fun disable(forgetConsent:Boolean=true,expectedEpoch:Long?=null):Boolean {
        val current=account
        generation.incrementAndGet();active=false;if(!forgetConsent)attached=false;publish(current,false,emptySet())
        return lock.withLock {withContext(NonCancellable) {
            val success=if(current!=null)cleanup(current,preserveConsent=!forgetConsent,expectedEpoch=expectedEpoch)else true
            mutable.value=PushState(permission=permitted,error=if(success)null else text(CoreStrings.CORE_NOTIFICATIONS_ARE_OFF_ON_THIS_DEVICE_SERVER_REMOVAL_COULD_NO),retryAtMillis=current?.let {deadlines[it.key]}?:0)
            success
        }}
    }
    suspend fun rotated(token:String) {
        val request=generation.get()
        lock.withLock {withContext(NonCancellable) {
            val current=account?:return@withContext
            if(!attached || !active || !permitted || !persistence.consent(current.key) || request!=generation.get())return@withContext
            synchronize(current,request,token)
        }}
    }
    private suspend fun cleanup(current:PushAccount,preserveConsent:Boolean=true,expectedEpoch:Long?=null):Boolean {
        var success=true
        val consent=try {persistence.consent(current.key)}catch(_:Exception){success=false;false}
        val saved=try {persistence.records(current.key)}catch(_:Exception){success=false;emptyList()}
        val pending=saved.toMutableList()
        try {withTimeout(15_000) {
            for(record in saved) {
                if(now()<(deadlines[current.key]?:0)) {success=false;break}
                try {if(expectedEpoch==null)api.remove(record.makerspaceId,record.deviceId)else api.remove(record.makerspaceId,record.deviceId,expectedEpoch);pending.remove(record)}
                catch(cancelled:CancellationException){throw cancelled}catch(f:Exception){success=false;message(f,current.key);if(f is ApiFailure&&f.status==429)break}
            }
        }}catch(_:Exception){success=false}finally {
            try {if(consent || saved.isNotEmpty())withTimeout(20_000){provider.stop(current,expectedEpoch)}}catch(_:Exception){success=false}
            try {
                if(pending.isNotEmpty()) {
                    persistence.records(current.key,pending)
                    persistence.consent(current.key,preserveConsent&&consent)
                }else {
                    persistence.clear(current.key)
                    if(preserveConsent&&consent)persistence.consent(current.key,true)
                }
            }catch(_:Exception){success=false}
        }
        return success
    }
    private suspend fun synchronize(current:PushAccount,request:Long,rotated:String?) {
        if(!active || request!=generation.get() || now()<(deadlines[current.key]?:0))return
        mutable.update {it.copy(busy=true,error=null)}
        var records=mutableListOf<PushRecord>();val confirmed=mutableSetOf<Int>()
        var failure:String?=null
        suspend fun save(){persistence.records(current.key,records)}
        suspend fun remove(record:PushRecord){api.remove(record.makerspaceId,record.deviceId);records.remove(record);save()}
        try {withTimeout(60_000) {
            records=persistence.records(current.key).toMutableList()
            // Gate callbacks only after explicit consent and an authenticated membership snapshot.
            publish(current,true,current.makerspaceIds)
            val token=rotated?:withTimeout(20_000){provider.token(current)}
            require(token.matches(Regex("^[A-Za-z0-9_:.-]{20,4096}$")))
            val hash=MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString(""){"%02x".format(it)}
            for(record in records.toList().filter {it.makerspaceId !in current.makerspaceIds}) {
                if(now()<(deadlines[current.key]?:0))break
                try {remove(record)}catch(cancelled:CancellationException){throw cancelled}catch(f:Exception){failure=message(f,current.key);if(f is ApiFailure&&f.status==429)break}
            }
            for(maker in current.makerspaceIds.sorted()) {
                if(!active || request!=generation.get() || now()<(deadlines[current.key]?:0))break
                try {
                    val existing=records.firstOrNull {it.makerspaceId==maker && it.tokenHash==hash}
                    val id=existing?.deviceId?:api.register(maker,token).also {newId->
                        require(newId>0)
                        records.removeAll {it.makerspaceId==maker && it.deviceId==newId}
                        records.add(PushRecord(maker,newId,hash));save()
                    }
                    confirmed+=maker
                    for(old in records.toList().filter {it.makerspaceId==maker && it.deviceId!=id})remove(old)
                }catch(cancelled:CancellationException){throw cancelled}catch(f:Exception){failure=message(f,current.key);if(f is ApiFailure&&f.status==429)break}
            }
        }}catch(f:Exception){failure=message(f,current.key)}
        finally {
            if(request==generation.get() && active) {
                publish(current,true,current.makerspaceIds)
                mutable.update {it.copy(busy=false,registered=confirmed,error=failure)}
            }else publish(current,false,emptySet())
        }
    }
    private fun message(f:Exception,accountKey:String):String {
        if(f is ApiFailure && f.status==429) {
            val deadline=now()+(f.retryAfterSeconds?:60)*1000L;deadlines[accountKey]=deadline
            if(account?.key==accountKey)mutable.update {it.copy(retryAtMillis=deadline)}
        }
        return if(f is MobileCopyException)text.failure(f) else if(f is ApiFailure)text.apiFailure(f) else text(CoreStrings.CORE_NOTIFICATIONS_ARE_UNAVAILABLE_TRY_AGAIN_LATER)
    }
}
