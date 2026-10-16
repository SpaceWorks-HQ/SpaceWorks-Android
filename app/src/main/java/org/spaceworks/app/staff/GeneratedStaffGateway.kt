package org.spaceworks.app.staff

import org.spaceworks.app.platform.MobileText

import kotlinx.serialization.json.*
import org.spaceworks.app.auth.requireBody
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.network.ApiSession
import org.spaceworks.generated.api.*
import org.spaceworks.generated.model.*

/** Only generated interfaces send API requests. Authority is denied until a real native grant exists. */
class GeneratedStaffGateway(private val session:ApiSession,private val authorizer:StaffAuthorizer=StaffAuthorizer.DENY,private val text:MobileText=MobileText.ENGLISH):StaffGateway {
    private fun allowed(scope:MemberScope,action:String?){if(!authorizer.allows(scope,action))throw ApiFailure(403,text(StaffStrings.STAFF_STAFF_TOOLS_ARE_UNAVAILABLE_FOR_THIS_DEVICE_SESSION),"native_staff_unavailable",null)}
    private suspend fun <T> checked(scope:MemberScope,action:String?=null,work:suspend()->T):T {allowed(scope,action);val result=work();allowed(scope,action);return result}
    private fun <T> api(type:Class<T>,scope:MemberScope)=session.scopedApi(type,scope.id)
    private fun requests(scope:MemberScope)=api(AdminRequestsApi::class.java,scope)
    private fun events(scope:MemberScope)=api(AdminEventsApi::class.java,scope)
    private fun bookings(scope:MemberScope)=api(AdminBookingsApi::class.java,scope)
    private fun requireId(value:Int){require(value>0){text(StaffStrings.STAFF_CHOOSE_AN_EXISTING_RECORD_TO_CONTINUE)}}
    private fun request(value:AdminRequest,scope:MemberScope,expectedId:Int?=null):AdminRequest {
        if(value.makerspaceId!=scope.id || (expectedId!=null && value.id!=expectedId))throw ApiFailure(403,text(StaffStrings.STAFF_YOUR_MAKERSPACE_ACCESS_CHANGED_CHOOSE_AGAIN),null,null)
        return value
    }
    private fun validatePage(next:String?,scope:MemberScope,path:String,current:Int,search:String?=null){StaffPagination.validatedNext(next,current,session.server,path,search)}
    override suspend fun requests(scope:MemberScope,queue:StaffQueue,page:Int)=checked(scope){
        requireId(page);val result=when(queue){StaffQueue.PENDING->requests(scope).apiV1AdminMakerspacePendingRequestsList(scope.id,page=page).requireBody();StaffQueue.ACCEPTED->requests(scope).apiV1AdminMakerspaceAcceptedRequestsList(scope.id,page=page).requireBody();StaffQueue.ACTIVE->requests(scope).apiV1AdminMakerspaceActiveLoansList(scope.id,page=page).requireBody()}
        val suffix=when(queue){StaffQueue.PENDING->"pending-requests";StaffQueue.ACCEPTED->"accepted-requests";StaffQueue.ACTIVE->"active-loans"}
        validatePage(result.next?.toString(),scope,"/api/v1/admin/makerspace/${scope.id}/$suffix",page);result.results.forEach{request(it,scope)};result
    }
    override suspend fun accept(scope:MemberScope,id:Int,body:AcceptRequest)=checked(scope,"accept_request"){
        requireId(id);body.acceptedQuantities?.let{rows->require(rows.map{it.itemId}.distinct().size==rows.size);rows.forEach{require(it.itemId>0&&it.quantity>=0)}}
        request(requests(scope).apiV1AdminRequestsAcceptCreate(id,body).requireBody(),scope,id)
    }
    override suspend fun reject(scope:MemberScope,id:Int,body:RejectRequest)=checked(scope,"reject_request"){
        requireId(id);require(body.reason.isNotBlank());request(requests(scope).apiV1AdminRequestsRejectCreate(id,body).requireBody(),scope,id)
    }
    override suspend fun assignBox(scope:MemberScope,id:Int,body:AssignBox)=checked(scope,"assign_box"){
        requireId(id);require(body.boxCode.isNotBlank());request(requests(scope).apiV1AdminRequestsAssignBoxCreate(id,body).requireBody(),scope,id)
    }
    override suspend fun issue(scope:MemberScope,id:Int,body:IssueRequest)=checked(scope,"issue_request"){
        requireId(id);requireId(body.evidenceId);kits(body.kitChecklist);payloads(body.assetQrPayloads)
        request(requests(scope).apiV1AdminRequestsIssueCreate(id,body).requireBody(),scope,id)
    }
    override suspend fun returnRequest(scope:MemberScope,id:Int,body:ReturnRequest)=checked(scope,"return_request"){
        requireId(id);requireId(body.evidenceId);require(body.remark.isNotBlank());kits(body.kitChecklist);resolutions(body.resolutions)
        request(requests(scope).apiV1AdminRequestsReturnCreate(id,body).requireBody(),scope,id)
    }
    override suspend fun resolveQR(scope:MemberScope,body:QrResolve)=checked(scope){
        require(body.payload.isNotBlank()&&body.payload.toByteArray().size<=4096&&body.payload.none(Char::isISOControl))
        val result=api(QRAssetsApi::class.java,scope).apiV1AdminQrResolveCreate(body).requireBody()
        if(result.qr.makerspace!=scope.id)throw ApiFailure(403,text(StaffStrings.STAFF_THIS_CODE_BELONGS_TO_ANOTHER_MAKERSPACE),null,null)
        result
    }
    override suspend fun directLoans(scope:MemberScope,page:Int)=checked(scope){requireId(page);requests(scope).apiV1AdminMakerspaceDirectLoansList(scope.id,page=page).requireBody().also{validatePage(it.next?.toString(),scope,"/api/v1/admin/makerspace/${scope.id}/direct-loans",page)}}
    override suspend fun directMembers(scope:MemberScope,page:Int)=checked(scope){requireId(page);api(ApiApi::class.java,scope).apiV1AdminMakerspaceDirectLoanMembersList(scope.id,page=page).requireBody().also{validatePage(it.next?.toString(),scope,"/api/v1/admin/makerspace/${scope.id}/direct-loan-members",page)}}
    override suspend fun inventory(scope:MemberScope,page:Int,search:String)=checked(scope){
        requireId(page);val result=api(AdminInventoryApi::class.java,scope).apiV1AdminMakerspaceInventoryList(scope.id,page=page,q=search.takeIf{it.isNotEmpty()}).requireBody()
        validatePage(result.next?.toString(),scope,"/api/v1/admin/makerspace/${scope.id}/inventory",page,search)
        if(result.results.any{it.makerspace!=scope.id})throw ApiFailure(403,text(StaffStrings.STAFF_YOUR_MAKERSPACE_ACCESS_CHANGED_CHOOSE_AGAIN),null,null)
        result
    }
    override suspend fun containers(scope:MemberScope,page:Int)=checked(scope){
        requireId(page);val result=api(ContainersApi::class.java,scope).apiV1AdminMakerspaceContainersList(scope.id,page=page).requireBody()
        validatePage(result.next?.toString(),scope,"/api/v1/admin/makerspace/${scope.id}/containers",page)
        if(result.results.any{it.makerspace!=scope.id})throw ApiFailure(403,text(StaffStrings.STAFF_YOUR_MAKERSPACE_ACCESS_CHANGED_CHOOSE_AGAIN),null,null)
        result
    }
    override suspend fun createDirect(scope:MemberScope,body:DirectLoanIssue)=checked(scope,"issue_direct_loan"){
        requireId(body.borrowerId);requireId(body.evidenceId);body.containerId?.let(::requireId);kits(body.kitChecklist);payloads(body.qrPayloads)
        body.items?.let{items->require(items.map{it.productId}.distinct().size==items.size);items.forEach{require(it.productId>0&&it.quantity>0)}}
        requests(scope).apiV1AdminMakerspaceDirectLoansCreate(scope.id,body).requireBody().also{this.requireId(it.id)}
    }
    override suspend fun returnDirect(scope:MemberScope,id:Int,body:DirectLoanReturn)=checked(scope,"return_request"){
        requireId(id);requireId(body.evidenceId);require(body.notes.isNotBlank());body.qrPayload?.let{payloads(listOf(it))};kits(body.kitChecklist);resolutions(body.resolutions)
        requests(scope).apiV1AdminDirectLoansReturnCreate(id,body).requireBody().also{require(it.id==id)}
    }
    override suspend fun evidence(scope:MemberScope,body:EvidenceUrlRequest)=checked(scope,"upload_evidence"){
        require(body.contentType in setOf("image/jpeg","image/png")&&(body.sizeBytes==null||body.sizeBytes>=0))
        api(ApiApi::class.java,scope).apiV1AdminMakerspacesUploadsEvidenceUrlCreate(scope.id,body).requireBody().also{this.requireId(it.evidenceId)}
    }
    override suspend fun kitPreview(scope:MemberScope,body:KitPreviewInput)=checked(scope){
        body.requestId?.let(::requireId);body.loanId?.let(::requireId);body.containerId?.let(::requireId);payloads(body.qrPayloads);resolutions(body.resolutions)
        requests(scope).apiV1AdminMakerspaceKitChecklistPreviewCreate(scope.id,body).requireBody().also{result->require(result.components.all{it.quantity>=0})}
    }
    override suspend fun events(scope:MemberScope,page:Int)=checked(scope){
        requireId(page);val result=events(scope).apiV1AdminMakerspacesEventsRetrieve(scope.id,page=page).requireBody()
        validatePage(result.next,scope,"/api/v1/admin/makerspaces/${scope.id}/events/",page)
        if(result.results.any{it.makerspaceId!=scope.id})throw ApiFailure(403,text(StaffStrings.STAFF_YOUR_MAKERSPACE_ACCESS_CHANGED_CHOOSE_AGAIN),null,null)
        result
    }
    override suspend fun resolveCheckIn(scope:MemberScope,eventId:Int,body:EventCheckInResolveRequest)=checked(scope){
        requireId(eventId);require(body.checkinToken.isNotBlank()&&body.checkinToken.toByteArray().size<=4096)
        events(scope).apiV1AdminEventsCheckInResolveCreate(eventId,body).requireBody()
    }
    override suspend fun markAttended(scope:MemberScope,registrationId:Int,body:EventAttendanceMark)=checked(scope,"manage_events"){
        requireId(registrationId);events(scope).apiV1AdminEventRegistrationsMarkAttendedCreate(registrationId,body).requireBody().also{require(it.id==registrationId)}
    }
    override suspend fun spaces(scope:MemberScope,page:Int)=checked(scope){
        requireId(page);val result=bookings(scope).apiV1AdminMakerspacesSpacesRetrieve(scope.id,page=page).requireBody()
        validatePage(result.next,scope,"/api/v1/admin/makerspaces/${scope.id}/spaces/",page)
        if(result.results.any{it.makerspaceId!=scope.id})throw ApiFailure(403,text(StaffStrings.STAFF_YOUR_MAKERSPACE_ACCESS_CHANGED_CHOOSE_AGAIN),null,null)
        result
    }
    override suspend fun bookings(scope:MemberScope,spaceId:Int,page:Int)=checked(scope){
        requireId(spaceId);requireId(page);bookings(scope).apiV1AdminSpacesBookingsRetrieve(spaceId,page=page).requireBody().also{
            validatePage(it.next,scope,"/api/v1/admin/spaces/$spaceId/bookings/",page);require(it.results.all{row->row.spaceId==spaceId})
        }
    }
    override suspend fun approveBooking(scope:MemberScope,id:Int)=checked(scope,"manage_bookings"){requireId(id);bookings(scope).apiV1AdminBookingsApproveCreate(id).requireBody().also{require(it.id==id)}}
    override suspend fun rejectBooking(scope:MemberScope,id:Int)=checked(scope,"manage_bookings"){requireId(id);bookings(scope).apiV1AdminBookingsRejectCreate(id).requireBody().also{require(it.id==id)}}
    private fun kits(values:List<KitCount>?){values?.let{require(it.size<=3550&&it.map{row->row.key}.distinct().size==it.size);it.forEach{row->require(row.present>=0)}}}
    private fun payloads(values:List<String>?){values?.let{require(it.size<=50&&it.distinct().size==it.size);require(it.all{value->value.isNotBlank()&&value.toByteArray().size<=64&&value.none(Char::isISOControl)})}}
    private fun resolutions(values:List<ReturnItemResolution>?){values?.let{require(it.map{row->row.itemId}.distinct().size==it.size);it.forEach{row->require(row.itemId>0&&(row.returned?:0)>=0&&(row.damaged?:0)>=0&&(row.missing?:0)>=0);row.assets?.let{assets->require(assets.map{a->a.assetId}.distinct().size==assets.size);assets.forEach{asset->requireId(asset.assetId)}}}}}
}

/** Scalar fields remain byte-for-byte strings for storage SDKs; nested/null values fail closed. */
object StaffEvidence {
    fun upload(value:EvidenceUrlResponse):StaffEvidenceUpload {
        require(value.evidenceId>0)
        val method=value.method?.uppercase(java.util.Locale.ROOT)?:"POST";require(method in setOf("POST","PUT")){throw org.spaceworks.app.platform.MobileCopyException(StaffStrings.STAFF_THIS_EVIDENCE_UPLOAD_IS_UNAVAILABLE)}
        fun scalar(values:Map<String,JsonElement>):Map<String,String> = values.mapValues{(_,element)->
            require(element is JsonPrimitive && element !is JsonNull){throw org.spaceworks.app.platform.MobileCopyException(StaffStrings.STAFF_THIS_EVIDENCE_UPLOAD_IS_UNAVAILABLE)};element.content
        }
        return StaffEvidenceUpload(value.evidenceId,value.uploadUrl.toString(),method,scalar(value.fields),scalar(value.headers.orEmpty()))
    }
}
