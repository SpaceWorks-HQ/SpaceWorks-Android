package org.spaceworks.app.notifications

import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.MobileCopyException
import org.spaceworks.app.platform.failure
import org.spaceworks.app.copy.CoreStrings
import org.spaceworks.app.copy.apiFailure

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import okhttp3.HttpUrl
import org.spaceworks.app.platform.PushHint
import java.util.concurrent.atomic.AtomicLong

data class AuthorizedNavigation(val destination:MobileDestination,val sequence:Long,val notification:Boolean=false)
data class LinkState(val pending:MobileDestination?=null,val pendingLegacy:LegacyMemberRoute?=null,val requiredMaker:Int?=null,val delivered:AuthorizedNavigation?=null,val notification:Boolean=false,val busy:Boolean=false,val error:String?=null)
/** Links carry navigation intent only; every delivery first refetches the current identity. */
class LinkStateHolder(private val server:HttpUrl,private val authorize:suspend(Int)->Boolean,private val select:(Int)->Unit,private val authorizeSlug:suspend(String,Boolean)->Int?={_,_->null},private val text:MobileText=MobileText.ENGLISH) {
    constructor(server:HttpUrl,authorize:suspend(Int)->Boolean,select:(Int)->Unit,authorizeSlug:suspend(String,Boolean)->Int?)
        :this(server,authorize,select,authorizeSlug,MobileText.ENGLISH)

    private val mutable=MutableStateFlow(LinkState());val state=mutable.asStateFlow()
    private val sequence=AtomicLong();private val lock=Mutex()
    fun offer(raw:String){
        val route=MobileRoutes.relative(raw)?:MobileRoutes.external(raw,server);val legacy=if(route==null)(MobileRoutes.legacyPath(raw)?:MobileRoutes.legacy(raw,server))else null
        sequence.incrementAndGet();mutable.value=LinkState(pending=route,pendingLegacy=legacy,error=if(route==null&&legacy==null)text(CoreStrings.CORE_THIS_LINK_ISN_T_SUPPORTED_BY_THIS_APP)else null)
    }
    fun offer(hint:PushHint){
        val legacy=if(hint.version==2)hint.link?.let(MobileRoutes::legacyPath)else null
        if(legacy==null)offer(MobileRoutes.hint(hint),true)
        else {sequence.incrementAndGet();mutable.value=LinkState(pendingLegacy=legacy,requiredMaker=hint.makerspaceId,notification=true)}
    }
    private fun offer(target:MobileDestination?,notification:Boolean=false){sequence.incrementAndGet();mutable.value=LinkState(pending=target,notification=notification,error=if(target==null)text(CoreStrings.CORE_THIS_LINK_ISN_T_SUPPORTED_BY_THIS_APP)else null)}
    fun clear(){sequence.incrementAndGet();mutable.value=LinkState()}
    fun consumed(value:Long){mutable.update {if(it.delivered?.sequence==value)it.copy(delivered=null)else it}}
    suspend fun resolve() {
        if(!lock.tryLock())return
        try {
            while(true) {
                val target=state.value.pending
                val legacy=state.value.pendingLegacy
                val notification=state.value.notification
                val requiredMaker=state.value.requiredMaker
                if(target==null && legacy==null)return
                val request=sequence.get()
                mutable.update {it.copy(busy=true,error=null)}
                try {
                    val destination=if(target!=null){if(authorize(target.makerspaceId))target else null}
                        else requireNotNull(legacy).let {alias->authorizeSlug(alias.slug,alias.staff)?.takeIf {id->id>0&&(requiredMaker==null||requiredMaker==id)}?.let {id->MobileDestination(id,if(alias.staff)MobileSection.INBOX else MobileSection.HOME)}}
                    if(request!=sequence.get())continue
                    if(destination==null){mutable.update {it.copy(busy=false,pending=null,pendingLegacy=null,requiredMaker=null,error=text(CoreStrings.CORE_YOUR_MAKERSPACE_ACCESS_COULDN_T_BE_CONFIRMED_OPEN_INBOX_OR_T))};return}
                    select(destination.makerspaceId)
                    if(request!=sequence.get())continue
                    mutable.value=LinkState(delivered=AuthorizedNavigation(destination,request,notification))
                    return
                }catch(cancelled:CancellationException){if(request==sequence.get())mutable.update {it.copy(busy=false)};throw cancelled}
                catch(_:Exception){if(request==sequence.get()){mutable.update {it.copy(busy=false,pending=null,pendingLegacy=null,requiredMaker=null,error=text(CoreStrings.CORE_COULDN_T_OPEN_THIS_UPDATE_TRY_AGAIN_FROM_INBOX))};return}}
            }
        }finally {lock.unlock()}
    }
}
