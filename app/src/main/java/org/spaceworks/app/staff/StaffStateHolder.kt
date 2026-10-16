package org.spaceworks.app.staff

import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.MobileCopyException
import org.spaceworks.app.platform.failure
import org.spaceworks.app.platform.rememberMobileText

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.copy.apiFailure
import org.spaceworks.generated.model.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class StaffSection { QUEUE, DIRECT, EVENTS, SPACES, BOOKINGS, REQUEST, HANDOVER, CHECK_IN }
enum class StaffHandoverMode { ISSUE, RETURN_REQUEST, DIRECT_ISSUE, DIRECT_RETURN;
    val returning get() = this == RETURN_REQUEST || this == DIRECT_RETURN
}
enum class StaffAssetOutcome { PENDING, RETURNED, DAMAGED, MISSING }
data class StaffReturnAsset(val id:Int,val label:String,val outcome:StaffAssetOutcome=StaffAssetOutcome.PENDING)
data class StaffReturnCount(val itemId:Int,val label:String,val remaining:Int,val assets:List<StaffReturnAsset>,
    val metadataValid:Boolean=true,val returned:Int=0,val damaged:Int=0,val missing:Int=0) {
    val valid get() = metadataValid && remaining>=0 && listOf(returned,damaged,missing).all{it>=0} &&
        returned.toLong()+damaged.toLong()+missing.toLong()<=remaining.toLong()
    val touched get() = returned>0 || damaged>0 || missing>0
    fun body() = ReturnItemResolution(itemId=itemId,returned=returned,damaged=damaged,missing=missing,
        assets=assets.mapNotNull{asset-> when(asset.outcome){
            StaffAssetOutcome.PENDING->null
            else->ReturnAssetResolution(assetId=asset.id,outcome=ReturnAssetResolutionOutcomeEnum.entries.first{it.value==asset.outcome.name.lowercase()})
        }}.takeIf{it.isNotEmpty()})
}
data class StaffHandoverDraft(
    val mode:StaffHandoverMode,val request:AdminRequest?=null,val loan:DirectLoan?=null,val step:Int=0,
    val borrowerId:Int?=null,val borrowerConfirmed:Boolean=false,val remark:String="",
    val boxCode:String="",val boxLabel:String="",val scans:List<QrResolveResult> = emptyList(),
    val quantities:Map<Int,Int> = emptyMap(),val returns:List<StaffReturnCount> = emptyList(),
    val kits:List<KitExpectation> = emptyList(),val kitCounts:Map<UUID,Int> = emptyMap(),val kitsReady:Boolean=false,
    val photoReady:Boolean=false,val photoRevision:Long=0,val evidenceId:Int?=null,val assignmentRecorded:Boolean=false,
    val needsReload:Boolean=false,val receipt:String?=null,
) {
    val returning get()=mode.returning
    val requiresBox get()=mode==StaffHandoverMode.ISSUE || mode==StaffHandoverMode.RETURN_REQUEST || loan?.returnScanRequired==true
    val steps get()=when(mode){
        StaffHandoverMode.ISSUE->listOf("Container","Borrower","Equipment","Photo and review","Receipt")
        StaffHandoverMode.DIRECT_ISSUE->listOf("Borrower","Equipment","Photo and review","Receipt")
        else->listOf("Container","Returned items","Photo and review","Receipt")
    }
    val reviewStep get()=steps.size-2
}
data class StaffViewState(
    val scope:MemberScope?=null,val section:StaffSection=StaffSection.QUEUE,val queue:StaffQueue=StaffQueue.PENDING,
    val requests:List<AdminRequest> = emptyList(),val directLoans:List<DirectLoan> = emptyList(),
    val events:List<EventAdmin> = emptyList(),val spaces:List<BookableSpaceAdmin> = emptyList(),
    val bookings:List<BookingAdmin> = emptyList(),val members:List<DirectLoanMember> = emptyList(),
    val products:List<InventoryProductAdmin> = emptyList(),val containers:List<Box> = emptyList(),
    val nextPages:Map<String,Int?> = emptyMap(),val loading:Set<String> = emptySet(),val busy:Boolean=false,
    val selectedRequest:AdminRequest?=null,val selectedSpace:BookableSpaceAdmin?=null,
    val handover:StaffHandoverDraft?=null,val checkInEvent:EventAdmin?=null,
    val person:EventCheckInResolveResponse?=null,val attendanceNotice:String?=null,
    val error:String?=null,val notice:String?=null,val retryAtMillis:Long=0,val available:Boolean=false,
)
/** The upload consumes only an actual generated presign; the default cannot send a photo. */
fun interface StaffPhotoUpload {
    suspend fun upload(grant:StaffEvidenceUpload,jpeg:ByteArray)
    companion object {val UNAVAILABLE=StaffPhotoUpload{_,_->throw MobileCopyException(StaffStrings.STAFF_UPLOAD_NOT_CONFIGURED)}}
}
/** Only Debug implements this preview seam; release contains no sample photo or staff fixtures. */
data class StaffPreviewOption(val id:String,val label:org.spaceworks.app.platform.MobileCopyKey)
interface StaffPreviewControl {
    fun samplePhoto():ByteArray
    fun resetAccount()
    val previewOptions:List<StaffPreviewOption> get()=emptyList()
    val previewScenarioId:String? get()=null
    fun selectPreviewScenario(id:String,scope:MemberScope):Boolean=false
}

/** Opaque, transient OS-picker/decode ticket. Never serialize, save or log its target snapshot. */
class StaffPhotoTarget internal constructor(
    internal val owner: Any, internal val epoch: Long, internal val scope: MemberScope,
    internal val handoverVersion: Long, internal val photoVersion: Long,
    internal val draft: StaffHandoverDraft,
)

enum class StaffScanKind { CONTAINER, EQUIPMENT, CHECK_IN }

/** Opaque lookup-only ticket; equality supplies a stable UI context without exposing/logging IDs. */
class StaffScanTarget internal constructor(
    internal val owner:Any,internal val epoch:Long,internal val scope:MemberScope,internal val kind:StaffScanKind,
    internal val handoverVersion:Long,internal val checkInVersion:Long,
    internal val draft:StaffHandoverDraft?,internal val event:EventAdmin?,
    internal val person:EventCheckInResolveResponse?,internal val attendanceNotice:String?,
) {
    override fun equals(other:Any?):Boolean = other is StaffScanTarget&&owner===other.owner&&epoch==other.epoch&&scope==other.scope&&
        kind==other.kind&&handoverVersion==other.handoverVersion&&checkInVersion==other.checkInVersion&&draft==other.draft&&
        event==other.event&&person==other.person&&attendanceNotice==other.attendanceNotice
    override fun hashCode():Int=listOf(owner,epoch,scope,kind,handoverVersion,checkInVersion,draft,event,person,attendanceNotice).hashCode()
}

/** Account-owned, transient staff state. No drafts, QR payloads or photos enter saved state. */
class StaffStateHolder(
    private val api:StaffGateway,
    private val authority:StaffAuthorizer=StaffAuthorizer.DENY,
    private val evidenceUpload:StaffPhotoUpload=StaffPhotoUpload.UNAVAILABLE,
    private val onUnauthorized:suspend()->Unit={},private val onForbidden:suspend()->Unit={},
    private val now:()->Long=System::currentTimeMillis,
    private val text:MobileText=MobileText.ENGLISH,
) {
    private val mutable=MutableStateFlow(StaffViewState());val state=mutable.asStateFlow()
    private val epoch=AtomicLong();private val versions=mutableMapOf<String,Long>();private val mutation=Mutex()
    private var features:Set<String> = emptySet();private var photo:ByteArray?=null;private var photoVersion=0L
    private var handoverVersion=0L;private var checkInVersion=0L
    private val photoOwner=Any()
    private val scanOwner=Any()
    val isDemo get()=api is StaffPreviewControl
    val protectsNavigation get()=state.value.let{it.scope!=null&&(it.busy||it.section in setOf(StaffSection.REQUEST,StaffSection.HANDOVER,StaffSection.CHECK_IN,StaffSection.BOOKINGS))}
    val previewOptions get()=(api as? StaffPreviewControl)?.previewOptions.orEmpty()
    val previewScenarioId get()=(api as? StaffPreviewControl)?.previewScenarioId
    /** Debug-only reset. The UI supplies its actual owner scope even after a fixture403 clears rows. */
    suspend fun previewScenario(id:String,scope:MemberScope):Boolean {
        val preview=api as? StaffPreviewControl?:return false
        if(state.value.busy||preview.previewOptions.none{it.id==id}||state.value.scope?.let{it!=scope}==true)return false
        val retainedFeatures=features.toSet()
        clear()
        if(!preview.selectPreviewScenario(id,scope))return false
        activate(scope,retainedFeatures)
        loadQueue()
        return true
    }
    fun activate(scope:MemberScope,features:Set<String> = emptySet()) {
        if(state.value.scope!=scope){clear();this.features=features.toSet();mutable.value=StaffViewState(scope=scope,available=authority.allows(scope,null))}
        else {this.features=features.toSet();if(!authority.allows(scope,null))clear()}
    }
    fun clear(){epoch.incrementAndGet();versions.clear();photo=null;photoVersion++;handoverVersion++;checkInVersion++;features=emptySet();mutable.value=StaffViewState()}
    fun resetAccount(){clear();(api as? StaffPreviewControl)?.resetAccount()}
    fun can(action:String?=null):Boolean=state.value.scope?.let{authority.allows(it,null)&&authority.allows(it,action)}==true
    fun canRequest()=can()&&!state.value.busy&&now()>=state.value.retryAtMillis
    private fun fresh(e:Long)=e==epoch.get()&&state.value.scope!=null
    private fun nextVersion(key:String):Long=(versions[key]?:0).plus(1).also{versions[key]=it}
    fun close():Boolean {
        if(state.value.busy)return false
        photo=null;photoVersion++;handoverVersion++;checkInVersion++
        mutable.update{it.copy(section=StaffSection.QUEUE,selectedRequest=null,handover=null,person=null,checkInEvent=null,error=null,notice=null)}
        return true
    }
    fun select(section:StaffSection):Boolean {
        if(!canRequest())return false
        if(section !in setOf(StaffSection.QUEUE,StaffSection.DIRECT,StaffSection.EVENTS,StaffSection.SPACES))return false
        close();mutable.update{it.copy(section=section)};return true
    }
    suspend fun changeQueue(queue:StaffQueue){if(!canRequest())return;nextVersion("queue");mutable.update{it.copy(queue=queue,requests=emptyList(),nextPages=it.nextPages-"queue",loading=it.loading-"queue")};loadQueue()}
    private suspend fun <T> read(key:String,more:Boolean=false,work:suspend(MemberScope,Int)->Pair<List<T>,String?>,id:(T)->Int,save:(StaffViewState,List<T>)->StaffViewState) {
        if(!canRequest() || key in state.value.loading || (more&&state.value.nextPages[key]==null))return
        val scope=state.value.scope?:return;val expected=epoch.get();val version=nextVersion(key)
        val page=if(more)state.value.nextPages[key]?:return else 1
        mutable.update{it.copy(loading=it.loading+key,error=null)}
        try {
            val (rows,next)=work(scope,page)
            if(!fresh(expected)||versions[key]!=version)return
            if(!can())throw ApiFailure(403,text(StaffStrings.STAFF_YOUR_STAFF_ACCESS_CHANGED),null,null)
            val nextPage=StaffPagination.page(next,page)
            mutable.update{old->val value=if(more)merge(existing<T>(key,old),rows,id)else merge(emptyList(),rows,id)
                save(old,value).copy(nextPages=old.nextPages+(key to nextPage))}
        }catch(c:CancellationException){throw c}
        catch(f:Exception){if(fresh(expected)&&versions[key]==version)fail(f,expected)}
        finally{if(fresh(expected)&&versions[key]==version)mutable.update{it.copy(loading=it.loading-key)}}
    }
    @Suppress("UNCHECKED_CAST") private fun <T> existing(key:String,s:StaffViewState):List<T> = when(key){
        "queue"->s.requests;"direct"->s.directLoans;"events"->s.events;"spaces"->s.spaces;"bookings"->s.bookings
        "members"->s.members;"products"->s.products;"containers"->s.containers;else->emptyList<Any>()
    } as List<T>
    private fun <T> merge(old:List<T>,new:List<T>,id:(T)->Int):List<T> {val result=old.associateByTo(linkedMapOf(),id);new.forEach{result[id(it)]=it};return result.values.toList()}
    suspend fun loadQueue(more:Boolean=false){val queue=state.value.queue;read<AdminRequest>("queue",more,{s,p->api.requests(s,queue,p).let{it.results to it.next?.toString()}},{it.id}){s,r->s.copy(requests=r)}}
    suspend fun loadDirect(more:Boolean=false)=read<DirectLoan>("direct",more,{s,p->api.directLoans(s,p).let{it.results to it.next?.toString()}},{it.id}){s,r->s.copy(directLoans=r)}
    suspend fun loadEvents(more:Boolean=false)=read<EventAdmin>("events",more,{s,p->api.events(s,p).let{it.results to it.next}},{it.id}){s,r->s.copy(events=r)}
    suspend fun loadSpaces(more:Boolean=false)=read<BookableSpaceAdmin>("spaces",more,{s,p->api.spaces(s,p).let{it.results to it.next}},{it.id}){s,r->s.copy(spaces=r)}
    suspend fun loadMembers(more:Boolean=false)=read<DirectLoanMember>("members",more,{s,p->api.directMembers(s,p).let{it.results to it.next?.toString()}},{it.userId}){s,r->s.copy(members=r)}
    suspend fun loadProducts(more:Boolean=false)=read<InventoryProductAdmin>("products",more,{s,p->api.inventory(s,p,"").let{it.results to it.next?.toString()}},{it.id}){s,r->s.copy(products=r)}
    suspend fun loadContainers(more:Boolean=false)=read<Box>("containers",more,{s,p->api.containers(s,p).let{it.results to it.next?.toString()}},{it.id}){s,r->s.copy(containers=r)}
    suspend fun openBookings(space:BookableSpaceAdmin){if(!canRequest()||state.value.spaces.none{it.id==space.id}||space.makerspaceId!=state.value.scope?.id)return
        nextVersion("bookings");mutable.update{it.copy(section=StaffSection.BOOKINGS,selectedSpace=space,bookings=emptyList(),nextPages=it.nextPages-"bookings",loading=it.loading-"bookings")};loadBookings()}
    suspend fun loadBookings(more:Boolean=false){val space=state.value.selectedSpace?:return
        read<BookingAdmin>("bookings",more,{s,p->api.bookings(s,space.id,p).let{it.results to it.next}},{it.id}){s,r->s.copy(bookings=r)}}
    fun openRequest(id:Int){if(!canRequest())return;val row=state.value.requests.firstOrNull{it.id==id}?:return;mutable.update{it.copy(section=StaffSection.REQUEST,selectedRequest=row,error=null,notice=null)}}
    private suspend fun <T> mutate(action:String,work:suspend(MemberScope)->T,save:(T)->Unit) {
        if(!canRequest()||!can(action)||!mutation.tryLock())return
        val scope=state.value.scope?:run{mutation.unlock();return};val expected=epoch.get()
        mutable.update{it.copy(busy=true,error=null,notice=null)}
        try{val result=work(scope);if(fresh(expected)){if(!can(action))throw ApiFailure(403,text(StaffStrings.STAFF_YOUR_STAFF_ACCESS_CHANGED),null,null);save(result)}}
        catch(c:CancellationException){if(fresh(expected))mutable.update{it.copy(error=text(StaffStrings.STAFF_THE_OUTCOME_COULD_NOT_BE_CONFIRMED_REFRESH_BEFORE_TRYING_AGA))};throw c}
        catch(f:Exception){if(fresh(expected))fail(f,expected)}
        finally{if(fresh(expected))mutable.update{it.copy(busy=false)};mutation.unlock()}
    }
    suspend fun accept(quantities:Map<Int,Int>){val row=state.value.selectedRequest?:return
        if(row.status !in setOf("pending","pending_approval")||!row.items.all{(quantities[it.id]?:0) in 0..it.requestedQuantity}||quantities.keys.any{id->row.items.none{it.id==id}}||quantities.values.none{it>0}){invalid(text(StaffStrings.STAFF_CHOOSE_A_VALID_QUANTITY_FOR_AT_LEAST_ONE_ITEM));return}
        mutate("accept_request",{api.accept(it,row.id,AcceptRequest(acceptedQuantities=row.items.map{item->AcceptQuantity(itemId=item.id,quantity=quantities[item.id]?:0)}))}){updated->replaceRequest(updated,text(StaffStrings.STAFF_REQUEST_ACCEPTED))}}
    suspend fun reject(reason:String){val row=state.value.selectedRequest?:return;val value=reason.trim()
        if(row.status !in setOf("pending","pending_approval")||value.isEmpty()||value.length>1000){invalid(text(StaffStrings.STAFF_ADD_A_REASON_OF_UP_TO_1_000_CHARACTERS));return}
        mutate("reject_request",{api.reject(it,row.id,RejectRequest(value))}){replaceRequest(it,text(StaffStrings.STAFF_REQUEST_REJECTED))}}
    private fun replaceRequest(row:AdminRequest,notice:String){mutable.update{it.copy(selectedRequest=row,requests=it.requests.map{old->if(old.id==row.id)row else old},notice=notice)}}
    suspend fun decideBooking(id:Int,approve:Boolean){val row=state.value.bookings.firstOrNull{it.id==id}?:return;val space=state.value.selectedSpace?:return
        if(row.status.value!="pending"||row.spaceId!=space.id)return
        val version=versions["bookings"];val expected=epoch.get()
        mutate("manage_bookings",{if(approve)api.approveBooking(it,id)else api.rejectBooking(it,id)}){result->if(versions["bookings"]==version&&state.value.selectedSpace?.id==space.id)mutable.update{it.copy(bookings=it.bookings.map{old->if(old.id==id)result else old},notice=if(approve)text(StaffStrings.STAFF_BOOKING_APPROVED) else text(StaffStrings.STAFF_BOOKING_REJECTED))}}
        val refusal=state.value.error
        if(refusal!=null&&fresh(expected)&&state.value.selectedSpace?.id==space.id){loadBookings();if(fresh(expected)&&state.value.selectedSpace?.id==space.id)mutable.update{it.copy(error=refusal)}}
    }
    fun beginHandover(mode:StaffHandoverMode,id:Int?=null) {
        if(!canRequest()||!can("upload_evidence"))return
        val action=if(mode.returning)"return_request" else if(mode==StaffHandoverMode.DIRECT_ISSUE)"issue_direct_loan" else "issue_request"
        if(!can(action)||(mode==StaffHandoverMode.ISSUE&&!can("assign_box")))return
        val request=if(mode==StaffHandoverMode.ISSUE||mode==StaffHandoverMode.RETURN_REQUEST)state.value.requests.firstOrNull{it.id==id}?:return else null
        val loan=if(mode==StaffHandoverMode.DIRECT_RETURN)state.value.directLoans.firstOrNull{it.id==id}?:return else null
        if(request!=null&&(request.makerspaceId!=state.value.scope?.id||request.status !in (if(mode.returning)setOf("issued","partially_returned")else setOf("accepted"))))return
        if(loan!=null&&loan.status!="active")return
        val returns=if(mode==StaffHandoverMode.RETURN_REQUEST)request!!.items.map{item->
            val assets=item.issuedAssets.mapNotNull{value->val obj=value as? JsonObject?:return@mapNotNull null
                val asset=(obj["asset_id"] as? JsonPrimitive)?.takeUnless{it.isString}?.intOrNull?.takeIf{it>0}?:return@mapNotNull null
                StaffReturnAsset(asset,(obj["asset_tag"] as? JsonPrimitive)?.contentOrNull?:text(StaffStrings.STAFF_ASSET_1_S, asset))}
            val remaining=(item.issuedQuantity.toLong()-item.returnedQuantity-item.damagedQuantity-item.missingQuantity)
            StaffReturnCount(item.id,item.productName,remaining.coerceIn(0,Int.MAX_VALUE.toLong()).toInt(),assets,
                metadataValid=remaining>=0&&(!item.requiresAssetQr||remaining==0L||assets.isNotEmpty()&&assets.size==item.issuedAssets.size&&assets.distinctBy{it.id}.size==assets.size))
        }else loan?.returnItems?.map{item->StaffReturnCount(item.itemId,item.productName,item.remainingQuantity,item.assets.map{StaffReturnAsset(it.assetId,it.assetTag)},
            metadataValid=item.trackingMode!="individual"||item.remainingQuantity==0||item.assets.isNotEmpty())}.orEmpty()
        photo=null;photoVersion++;handoverVersion++
        mutable.update{it.copy(section=StaffSection.HANDOVER,handover=StaffHandoverDraft(mode,request,loan,returns=returns),person=null,error=null,notice=null)}
    }
    fun moveStep(step:Int){val h=state.value.handover?:return;if(!state.value.busy&&step in 0..h.reviewStep)mutable.update{it.copy(handover=h.copy(step=step))}}
    fun setBorrower(id:Int?){edit{it.copy(borrowerId=id?.takeIf{value->state.value.members.any{row->row.userId==value}})}}
    fun confirmBorrower(confirmed:Boolean){edit{it.copy(borrowerConfirmed=confirmed)}}
    fun setRemark(value:String){edit{it.copy(remark=value.take(2000))}}
    fun setQuantity(id:Int,value:Int){edit(invalidateKits=true){it.copy(quantities=it.quantities+(id to value))}}
    fun setReturn(itemId:Int,returned:Int,damaged:Int,missing:Int){edit(invalidateKits=true){h->h.copy(returns=h.returns.map{if(it.itemId==itemId&&it.assets.isEmpty())it.copy(returned=returned,damaged=damaged,missing=missing)else it})}}
    fun setAsset(itemId:Int,assetId:Int,outcome:StaffAssetOutcome){edit(invalidateKits=true){h->h.copy(returns=h.returns.map{r->if(r.itemId!=itemId)r else {
        val assets=r.assets.map{if(it.id==assetId)it.copy(outcome=outcome)else it}
        r.copy(assets=assets,returned=assets.count{it.outcome==StaffAssetOutcome.RETURNED},damaged=assets.count{it.outcome==StaffAssetOutcome.DAMAGED},missing=assets.count{it.outcome==StaffAssetOutcome.MISSING})}})}}
    fun setKit(key:UUID,value:Int){edit{h->if(h.kits.any{it.key==key})h.copy(kitCounts=h.kitCounts+(key to value))else h}}
    fun removeScan(id:Int){edit(invalidateKits=true){it.copy(scans=it.scans.filterNot{row->row.qr.id==id})}}
    private fun edit(invalidateKits:Boolean=false,change:(StaffHandoverDraft)->StaffHandoverDraft){if(!canRequest())return;val h=state.value.handover?:return;if(h.needsReload||h.receipt!=null)return
        val value=change(h).let{if(invalidateKits)it.copy(kitsReady=false,kits=emptyList(),kitCounts=emptyMap())else it}
        mutable.update{it.copy(handover=value,error=null)}}
    private fun validPayload(raw:String,maximum:Int=2048)=raw.isNotBlank()&&raw.toByteArray().size<=maximum&&raw.none{it.isISOControl()||Character.getType(it)==Character.FORMAT.toInt()}
    /** Capture on camera open/manual submission, before a deferred frame or coroutine can run. */
    fun scanTarget(kind:StaffScanKind):StaffScanTarget? {
        val scope=state.value.scope?:return null
        if(!canRequest())return null
        if(kind==StaffScanKind.CHECK_IN) {
            if(state.value.section!=StaffSection.CHECK_IN||state.value.checkInEvent==null||!can("manage_events"))return null
            return StaffScanTarget(scanOwner,epoch.get(),scope,kind,0,checkInVersion,null,state.value.checkInEvent,state.value.person,state.value.attendanceNotice)
        }
        val h=state.value.handover?:return null
        if(kind==StaffScanKind.CONTAINER&&!h.requiresBox||kind==StaffScanKind.EQUIPMENT&&h.returning)return null
        val action=if(h.returning)"return_request" else if(h.mode==StaffHandoverMode.DIRECT_ISSUE)"issue_direct_loan" else "issue_request"
        if(state.value.section!=StaffSection.HANDOVER||h.needsReload||h.assignmentRecorded||h.receipt!=null||!can(action))return null
        return StaffScanTarget(scanOwner,epoch.get(),scope,kind,handoverVersion,0,h,null,null,null)
    }
    fun acceptsScanTarget(target:StaffScanTarget)=target.owner===scanOwner&&scanTarget(target.kind)==target
    /** True means an owned lookup was accepted, never that a handover/attendance was confirmed. */
    suspend fun resolveScan(target:StaffScanTarget,raw:String):Boolean {
        if(!acceptsScanTarget(target))return false
        when(target.kind){
            StaffScanKind.CHECK_IN->resolveCheckIn(raw)
            StaffScanKind.CONTAINER->scan(raw,true)
            StaffScanKind.EQUIPMENT->scan(raw,false)
        }
        return true
    }
    suspend fun scan(raw:String,container:Boolean){val h=state.value.handover?:return;if(!canRequest()||h.needsReload||h.assignmentRecorded)return
        if(container)mutable.update{it.copy(handover=it.handover?.copy(boxCode="",boxLabel="",kitsReady=false,kitCounts=emptyMap()))}
        if(!validPayload(raw)){invalid(text(StaffStrings.STAFF_ENTER_A_VALID_MAKERSPACE_QR_PAYLOAD));return}
        val version=handoverVersion
        mutate(if(h.returning)"return_request" else if(h.mode==StaffHandoverMode.DIRECT_ISSUE)"issue_direct_loan" else "issue_request",{scope->api.resolveQR(scope,QrResolve(raw.trim()))}){result->
            if(version!=handoverVersion)return@mutate
            if(result.qr.makerspace!=state.value.scope?.id||result.qr.status.value!="active"||!validPayload(result.qr.payload,64))throw validation(text(StaffStrings.STAFF_SCAN_AN_ACTIVE_CODE_FROM_THIS_MAKERSPACE))
            val type=result.qr.targetType.value;val current=state.value.handover?:return@mutate
            if(container){
                if(current.loan!=null){val loan=current.loan
                    if(type!=loan.targetType || type=="box"&&result.qr.targetId!=loan.containerId || type=="asset"&&loan.returnItems.flatMap{it.assets}.none{it.assetId==result.qr.targetId})throw validation(text(StaffStrings.STAFF_SCAN_THE_CODE_FOR_THIS_LOAN))
                }else if(type!="box")throw validation(text(StaffStrings.STAFF_SCAN_THE_CONTAINER_FOR_THIS_HANDOVER))
                mutable.update{it.copy(handover=current.copy(boxCode=result.qr.payload,boxLabel=(result.target["label"] as? JsonPrimitive)?.contentOrNull?:text(StaffStrings.STAFF_CONTAINER_1_S, result.qr.targetId),kitsReady=false,kitCounts=emptyMap()))}
            }else{
                if(type=="box")throw validation(text(StaffStrings.STAFF_SCAN_AN_EQUIPMENT_OR_ASSET_CODE_HERE))
                val product=(result.target["product_id"] as? JsonPrimitive)?.takeUnless{it.isString}?.intOrNull
                if(current.request!=null){if(type!="asset"||(result.target["status"] as? JsonPrimitive)?.content!="available"||current.request.items.none{it.productId==product&&it.requiresAssetQr})throw validation(text(StaffStrings.STAFF_SCAN_AN_AVAILABLE_ASSET_FROM_THIS_REQUEST))}
                else if("direct_handout" !in result.allowedActions)throw validation(text(StaffStrings.STAFF_THIS_EQUIPMENT_IS_UNAVAILABLE_FOR_DIRECT_HANDOUT))
                if(current.scans.any{it.qr.id==result.qr.id}||current.scans.size>=50)throw validation(text(StaffStrings.STAFF_THIS_CODE_IS_ALREADY_ADDED_OR_THE_50_CODE_LIMIT_WAS_REACHED))
                mutable.update{it.copy(handover=current.copy(scans=current.scans+result,kitsReady=false,kitCounts=emptyMap()))}
            }
        }
    }
    private fun needsKits(h:StaffHandoverDraft)="loans.kit_checklist" in features||h.request?.hasKitChecklist==true||h.loan?.hasKitChecklist==true
    private fun selectedItems(h:StaffHandoverDraft)=state.value.products.filter{(h.quantities[it.id]?:0)>0}.map{DirectLoanItem(it.id,h.quantities[it.id]?:0)}
    fun itemsReady():Boolean=state.value.handover?.let{itemsReady(it)}==true
    private fun itemsReady(h:StaffHandoverDraft):Boolean {
        if(h.returning)return h.returns.all{it.valid}&&h.returns.any{it.touched}
        if(h.request!=null)return h.request.items.any{it.acceptedQuantity>0}&&h.request.items.filter{it.requiresAssetQr}.all{item->h.scans.count{(it.target["product_id"] as? JsonPrimitive)?.intOrNull==item.productId}==item.acceptedQuantity}
        val valid=h.quantities.all{(id,q)->val product=state.value.products.firstOrNull{it.id==id};q==0||(product!=null&&q>0&&q<=(product.availableQuantity?:0)&&product.trackingMode?.value!="individual"&&product.isArchived!=true)}
        val duplicate=h.scans.any{result->val p=(result.target["product_id"] as? JsonPrimitive)?.intOrNull?:result.qr.targetId.takeIf{result.qr.targetType.value=="product"};(p?.let{h.quantities[it]}?:0)>0}
        return valid&&!duplicate&&(selectedItems(h).isNotEmpty()||h.scans.isNotEmpty())
    }
    suspend fun prepareKits(){val h=state.value.handover?:return;if(!itemsReady(h))return
        if(!needsKits(h)){mutable.update{it.copy(handover=h.copy(kits=emptyList(),kitCounts=emptyMap(),kitsReady=true))};return}
        mutate(if(h.returning)"return_request" else if(h.mode==StaffHandoverMode.DIRECT_ISSUE)"issue_direct_loan" else "issue_request",{scope->api.kitPreview(scope,KitPreviewInput(
            phase=PhaseEnum.entries.first{it.value==if(h.returning)"return" else "issue"},requestId=h.request?.id,loanId=h.loan?.id,
            boxCode=h.boxCode.takeIf{it.isNotEmpty()},qrPayloads=h.scans.map{it.qr.payload},items=if(h.mode==StaffHandoverMode.DIRECT_ISSUE)selectedItems(h)else null,resolutions=if(h.returning)h.returns.map{it.body()}else null))}){result->
            require(result.components.all{it.quantity>=0}&&result.components.distinctBy{it.key}.size==result.components.size)
            mutable.update{it.copy(handover=it.handover?.copy(kits=result.components,kitCounts=emptyMap(),kitsReady=true))}
        }
    }
    /** Capture before permission/picker/camera launch, not only after an OS result arrives. */
    fun photoTarget():StaffPhotoTarget? {
        val scope=state.value.scope?:return null;val h=state.value.handover?:return null
        if(!canRequest()||!can("upload_evidence")||state.value.section!=StaffSection.HANDOVER||h.needsReload||h.receipt!=null)return null
        return StaffPhotoTarget(photoOwner,epoch.get(),scope,handoverVersion,photoVersion,h)
    }
    fun acceptsPhotoTarget(target:StaffPhotoTarget):Boolean {
        val h=state.value.handover?:return false
        return target.owner===photoOwner&&target.epoch==epoch.get()&&target.scope==state.value.scope&&
            target.handoverVersion==handoverVersion&&target.photoVersion==photoVersion&&target.draft==h&&
            state.value.section==StaffSection.HANDOVER&&!h.needsReload&&h.receipt==null&&canRequest()&&can("upload_evidence")
    }
    /** Immediate in-app source only. Deferred OS/decode callbacks must pass their original ticket. */
    fun setPhoto(jpeg:ByteArray){photoTarget()?.let{setPhoto(it,jpeg)}}
    fun setPhoto(target:StaffPhotoTarget,jpeg:ByteArray):Boolean {
        if(!acceptsPhotoTarget(target))return false
        photoVersion++;photo=null
        val valid=jpeg.size in 3..5*1024*1024&&jpeg[0]==(-1).toByte()&&jpeg[1]==(-40).toByte()&&jpeg[2]==(-1).toByte()
        if(valid)photo=jpeg.copyOf()
        mutable.update{it.copy(handover=it.handover?.copy(photoReady=valid,photoRevision=photoVersion,evidenceId=null),error=if(valid)null else text(StaffStrings.STAFF_CHOOSE_A_JPEG_PHOTO_UP_TO_5_MB))}
        return valid
    }
    /** The injected decoder keeps Android I/O outside state; cleanup also runs for rejected/cancelled results. */
    suspend fun receivePhoto(target:StaffPhotoTarget,decode:suspend()->ByteArray,cleanup:()->Unit={}):Boolean = try {
        currentCoroutineContext().ensureActive()
        if(!acceptsPhotoTarget(target))false else {
            val bytes=decode()
            currentCoroutineContext().ensureActive()
            setPhoto(target,bytes)
        }
    }finally{runCatching(cleanup)}
    fun useSamplePhoto(){val preview=api as? StaffPreviewControl?:return;setPhoto(preview.samplePhoto())}
    /** Transient preview only; callers must never persist it in saved state or logs. */
    fun photoPreview():ByteArray?=photo?.copyOf()
    fun showError(message:String){invalid(message)}
    suspend fun uploadPhoto(){val h=state.value.handover?:return;val bytes=photo?:return;val pv=photoVersion;val hv=handoverVersion
        mutate("upload_evidence",{scope->val grant=api.evidence(scope,EvidenceUrlRequest(
            evidenceType=EvidenceUrlRequestEvidenceTypeEnum.entries.first{it.value==if(h.returning)"return" else "issue"},contentType="image/jpeg",sizeBytes=bytes.size))
            if(pv!=photoVersion||hv!=handoverVersion)throw CancellationException()
            evidenceUpload.upload(StaffEvidence.upload(grant),bytes)
            grant.evidenceId
        }){id->if(pv==photoVersion&&hv==handoverVersion)mutable.update{it.copy(handover=it.handover?.copy(evidenceId=id),notice=text(StaffStrings.STAFF_EVIDENCE_PHOTO_IS_READY))}}
    }
    fun ready():Boolean {val h=state.value.handover?:return false
        if(!canRequest()||h.needsReload||h.receipt!=null||h.evidenceId==null||!itemsReady(h)||h.requiresBox&&h.boxCode.isEmpty())return false
        if(needsKits(h)&&(!h.kitsReady||h.kits.any{val count=h.kitCounts[it.key];count==null||count !in 0..it.quantity}))return false
        return if(h.returning)h.remark.trim().isNotEmpty()else if(h.mode==StaffHandoverMode.DIRECT_ISSUE)state.value.members.any{it.userId==h.borrowerId}else h.borrowerConfirmed
    }
    suspend fun confirmHandover(){if(!ready())return;val h=state.value.handover?:return;val evidence=h.evidenceId?:return
        val action=if(h.returning)"return_request"else if(h.mode==StaffHandoverMode.DIRECT_ISSUE)"issue_direct_loan"else "issue_request"
        if(!can("upload_evidence")||h.mode==StaffHandoverMode.ISSUE&&!can("assign_box"))return
        val expected=epoch.get();val version=handoverVersion
        try {
            mutate(action,{scope->
                val kits=if(needsKits(h))h.kits.map{KitCount(it.key,h.kitCounts.getValue(it.key))}else null
                when(h.mode){
                    StaffHandoverMode.ISSUE->{api.assignBox(scope,h.request!!.id,AssignBox(h.boxCode));if(!fresh(expected))throw CancellationException()
                        mutable.update{it.copy(handover=it.handover?.copy(assignmentRecorded=true))}
                        api.issue(scope,h.request.id,IssueRequest(evidenceId=evidence,kitChecklist=kits,remark=h.remark,assetQrPayloads=h.scans.map{it.qr.payload})).also{if(it.status!="issued")throw validation(text(StaffStrings.STAFF_ISSUE_WAS_NOT_CONFIRMED_REFRESH_THIS_REQUEST))}.id}
                    StaffHandoverMode.RETURN_REQUEST->api.returnRequest(scope,h.request!!.id,ReturnRequest(evidenceId=evidence,boxCode=h.boxCode,remark=h.remark.trim(),resolutions=h.returns.map{it.body()},kitChecklist=kits)).id
                    StaffHandoverMode.DIRECT_ISSUE->api.createDirect(scope,DirectLoanIssue(borrowerId=h.borrowerId!!,evidenceId=evidence,kitChecklist=kits,remark=h.remark,qrPayloads=h.scans.map{it.qr.payload},items=selectedItems(h))).id
                    StaffHandoverMode.DIRECT_RETURN->api.returnDirect(scope,h.loan!!.id,DirectLoanReturn(evidenceId=evidence,notes=h.remark.trim(),kitChecklist=kits,qrPayload=h.boxCode.takeIf{it.isNotEmpty()},resolutions=h.returns.map{it.body()})).id
                }
            }){id->if(version==handoverVersion){photo=null;photoVersion++;mutable.update{it.copy(handover=it.handover?.copy(receipt=text(StaffStrings.STAFF_1_S_2_S_RECORDED, if(h.returning)text(StaffStrings.STAFF_RETURN)else text(StaffStrings.STAFF_ISSUE), id),step=h.steps.lastIndex,photoReady=false),notice=text(StaffStrings.STAFF_EVIDENCE_PHOTO_1_S_IS_ATTACHED, evidence))}}}
        }finally{if(fresh(expected)&&version==handoverVersion&&state.value.handover?.receipt==null){mutable.update{it.copy(handover=it.handover?.copy(needsReload=true),notice=if(it.handover?.assignmentRecorded==true)text(StaffStrings.STAFF_BOX_ASSIGNMENT_WAS_RECORDED_CLOSE_AND_REFRESH_THE_LATEST_REQ)else text(StaffStrings.STAFF_CLOSE_AND_REFRESH_THE_LATEST_RECORD_BEFORE_TRYING_AGAIN))}}}
    }
    fun openCheckIn(id:Int){if(!canRequest()||!can("manage_events"))return;val event=state.value.events.firstOrNull{it.id==id&&it.makerspaceId==state.value.scope?.id}?:return
        checkInVersion++;mutable.update{it.copy(section=StaffSection.CHECK_IN,checkInEvent=event,person=null,attendanceNotice=null,error=null)}}
    suspend fun resolveCheckIn(token:String){val event=state.value.checkInEvent?:return;if(!canRequest()||!can("manage_events"))return
        checkInVersion++;val version=checkInVersion;mutable.update{it.copy(person=null,attendanceNotice=null,error=null)}
        if(!validPayload(token)){invalid(text(StaffStrings.STAFF_ENTER_A_VALID_EVENT_CHECK_IN_CODE));return}
        mutate("manage_events",{api.resolveCheckIn(it,event.id,EventCheckInResolveRequest(token.trim()))}){person->if(version==checkInVersion)mutable.update{it.copy(person=person)}}}
    fun canConfirmAttendance()=canRequest()&&can("manage_events")&&state.value.person?.let{it.status=="registered"&&it.confirmable&&it.registrationId>0}==true
    suspend fun confirmAttendance(){if(!canConfirmAttendance())return;val person=state.value.person?:return;val version=checkInVersion
        mutate("manage_events",{api.markAttended(it,person.registrationId,EventAttendanceMark(source=EventAttendanceMarkSourceEnum.entries.first{it.value=="qr"}))}){row->
            if(version==checkInVersion){if(row.id!=person.registrationId||row.status.value!="attended")throw validation(text(StaffStrings.STAFF_ATTENDANCE_COULD_NOT_BE_CONFIRMED_SCAN_AGAIN_TO_CHECK_THE_LA));mutable.update{it.copy(person=null,attendanceNotice=text(StaffStrings.STAFF_ATTENDANCE_RECORDED_FOR_1_S, person.name))}}}
        if(state.value.error!=null)mutable.update{it.copy(person=null)}
    }
    private fun invalid(detail:String){mutable.update{it.copy(error=detail)}}
    private fun validation(detail:String)=ApiFailure(0,detail,"staff_validation",null)
    private suspend fun fail(f:Exception,expected:Long){if(!fresh(expected))return;val error=f as? ApiFailure
        if(error?.status==401){clear();onUnauthorized();return}
        if(error?.status==403){clear();onForbidden();return}
        val deadline=if(error?.status==429)now()+(error.retryAfterSeconds?:60).coerceIn(0,86400)*1000L else state.value.retryAtMillis
        mutable.update{it.copy(error=error?.let{text.apiFailure(it)}?:(if(f is MobileCopyException)text.failure(f)else text(StaffStrings.STAFF_COULDN_T_CONNECT_REFRESH_AND_TRY_AGAIN)),retryAtMillis=maxOf(it.retryAtMillis,deadline))}}
}
