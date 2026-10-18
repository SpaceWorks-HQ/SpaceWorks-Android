package org.spaceworks.app.staff

import org.spaceworks.app.member.MemberScope
import org.spaceworks.generated.model.*

enum class StaffQueue { PENDING, ACCEPTED, ACTIVE }
/** Local authorization seam, not a new server field or transport model. Unknown authority denies. */
fun interface StaffAuthorizer {
    fun allows(scope:MemberScope,action:String?):Boolean
    companion object {val DENY=StaffAuthorizer{_,_->false}}
}
/** Generated types preserve issued assets, actual resolution IDs and nullable server metadata. */
interface StaffGateway {
    suspend fun requests(scope:MemberScope,queue:StaffQueue,page:Int):PaginatedAdminRequestList
    suspend fun accept(scope:MemberScope,id:Int,body:AcceptRequest):AdminRequest
    suspend fun reject(scope:MemberScope,id:Int,body:RejectRequest):AdminRequest
    suspend fun assignBox(scope:MemberScope,id:Int,body:AssignBox):AdminRequest
    suspend fun issue(scope:MemberScope,id:Int,body:IssueRequest):AdminRequest
    suspend fun returnRequest(scope:MemberScope,id:Int,body:ReturnRequest):AdminRequest
    suspend fun resolveQR(scope:MemberScope,body:QrResolve):QrResolveResult
    suspend fun directLoans(scope:MemberScope,page:Int):PaginatedDirectLoanList
    suspend fun directMembers(scope:MemberScope,page:Int):PaginatedDirectLoanMemberList
    suspend fun inventory(scope:MemberScope,page:Int,search:String):PaginatedInventoryProductAdminList
    suspend fun containers(scope:MemberScope,page:Int):PaginatedBoxList
    suspend fun createDirect(scope:MemberScope,body:DirectLoanIssue):DirectLoan
    suspend fun returnDirect(scope:MemberScope,id:Int,body:DirectLoanReturn):DirectLoan
    suspend fun evidence(scope:MemberScope,body:EvidenceUrlRequest):EvidenceUrlResponse
    suspend fun kitPreview(scope:MemberScope,body:KitPreviewInput):KitPreview
    suspend fun events(scope:MemberScope,page:Int):EventListWithDefaults
    suspend fun resolveCheckIn(scope:MemberScope,eventId:Int,body:EventCheckInResolveRequest):EventCheckInResolveResponse
    suspend fun markAttended(scope:MemberScope,registrationId:Int,body:EventAttendanceMark):EventAttendanceResult
    suspend fun spaces(scope:MemberScope,page:Int):BookableSpaceListResponse
    suspend fun bookings(scope:MemberScope,spaceId:Int,page:Int):BookingListResponse
    suspend fun approveBooking(scope:MemberScope,id:Int):BookingAdmin
    suspend fun rejectBooking(scope:MemberScope,id:Int):BookingAdmin
}

/** Local storage-SDK instructions projected from a generated presign, never an API DTO. */
class StaffEvidenceUpload(val evidenceId:Int,val uploadUrl:String,val method:String,
    val fields:Map<String,String>,val headers:Map<String,String>) {
    override fun toString()="StaffEvidenceUpload(redacted)"
}

/** The attendance response is a generated union; both arms carry the registration ID and its status. */
val EventAttendanceResult.registrationId:Int get()=when(this){
    is EventAttendanceResult.EventRegistrationAdminValue->value.id
    is EventAttendanceResult.EventCheckInResultValue->value.id
}
val EventAttendanceResult.attendanceStatus:String get()=when(this){
    is EventAttendanceResult.EventRegistrationAdminValue->value.status.value
    is EventAttendanceResult.EventCheckInResultValue->value.status
}
