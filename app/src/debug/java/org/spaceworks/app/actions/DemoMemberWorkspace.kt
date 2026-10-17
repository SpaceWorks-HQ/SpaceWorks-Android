package org.spaceworks.app.actions

import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.spaceworks.app.member.*
import org.spaceworks.app.network.ApiFailure
import java.time.OffsetDateTime
import java.util.UUID

/** Shared per-account DEBUG domain: mutations are reflected in read screens; never calls any SDK/API. */
class DemoMemberWorkspace(private val base:DemoMemberGateway=DemoMemberGateway(),private val initialProfile:()->ActionProfile={ActionProfile("Meera Nair","Meera","Nair","")}):MemberGateway by base,MemberPreviewControl,MemberActionsGateway,ActionAccountReset,MakerCapabilitiesGateway {
    override suspend fun load(scope:MemberScope)=MakerCapabilities.DEMO
    private val requests=mutableMapOf<Int,MutableList<Pair<SubmittedRequest,RequestStatus>>>()
    private val renewals=mutableMapOf<Int,MutableMap<String,LoanRenewalResult>>()
    private val bookingChanges=mutableMapOf<Int,MutableMap<String,MemberBooking>>()
    private val registrations=mutableMapOf<Int,MutableMap<String,MemberRegistration>>()
    private var contact:ActionProfile?=null
    private val prefs=mutableMapOf<Int,List<ActionPreference>>()
    private val idempotent=mutableMapOf<String,SubmittedRequest>()
    private var nextRegistration=600
    override fun resetAccount(){base.scenario(MemberPreviewScenario.POPULATED);requests.clear();renewals.clear();bookingChanges.clear();registrations.clear();contact=null;prefs.clear();idempotent.clear();nextRegistration=600}
    override fun scenario(value:MemberPreviewScenario){base.scenario(value)}
    private suspend fun ready(scope:MemberScope){base.categories(scope);delay(100)}
    override suspend fun card(scope:MemberScope)=base.card(scope).copy(printedName=(contact?:initialProfile()).displayName.orEmpty())
    override suspend fun overview(scope:MemberScope):MemberOverview {
        val value=base.overview(scope)
        return value.copy(loans=value.loans.map {loan->renewals[scope.id]?.get(loan.requestId)?.let {loan.copy(dueAt=it.dueAt,renewalCount=it.renewalCount)}?:loan},
            requests=requests[scope.id].orEmpty().map {(_,status)->MemberRequestRow(status.status,status.createdAt,status.items.sumOf {it.quantity},0,0,0)}+value.requests,
            bookings=bookings(scope),registrations=mergeRegistrations(value.registrations,scope))
    }
    private fun mergeRegistrations(base:List<MemberRegistration>,scope:MemberScope):List<MemberRegistration> {
        val changes=registrations[scope.id].orEmpty();return base.map {changes[it.id]?:it}+changes.filterKeys {id->base.none {it.id==id}}.values
    }
    override suspend fun bookings(scope:MemberScope,period:String,next:String?):MemberPage<MemberBooking>{
        val original=base.bookings(scope,period,next);val changes=bookingChanges[scope.id].orEmpty()
        val values=original.items.map {changes[it.id]?:it}+changes.filterKeys {id->original.items.none {it.id==id}}.values
        return MemberPage(values,null,values.size)
    }
    override suspend fun submitRequest(scope:MemberScope,purpose:String,lines:List<RequestLine>,idempotencyKey:String?):SubmittedRequest {
        ready(scope);require(lines.isNotEmpty()&&lines.size<=20&&lines.all {it.quantity in 1..99})
        val key="${scope.id}:$idempotencyKey";if(idempotencyKey!=null)idempotent[key]?.let {return it}
        val products=lines.map {RequestedProduct(base.product(scope,it.productId).name,it.quantity)}
        val result=SubmittedRequest(UUID.randomUUID().toString(),"pending")
        requests.getOrPut(scope.id){mutableListOf()}.add(result to RequestStatus(result.status,OffsetDateTime.now().toString(),products))
        if(idempotencyKey!=null)idempotent[key]=result
        return result
    }
    override suspend fun requestStatus(scope:MemberScope,publicToken:String):RequestStatus {ready(scope);return requests[scope.id]?.firstOrNull {it.first.publicToken==publicToken}?.second?:throw ApiFailure(404,"This item wasn't found.",null,null)}
    override suspend fun renew(scope:MemberScope,requestId:String):LoanRenewalResult {
        ready(scope);val loan=base.overview(scope).loans.firstOrNull {it.requestId==requestId}?:throw ApiFailure(404,"This item wasn't found.",null,null)
        if(!loan.canRenew)throw ApiFailure(409,loan.renewalRefusal?:"This loan cannot be renewed.",null,null)
        val old=renewals[scope.id]?.get(requestId);val count=(old?.renewalCount?:loan.renewalCount)+1
        if(count>2)throw ApiFailure(409,"This loan has reached its renewal limit.","renewal_limit",null)
        val due=OffsetDateTime.parse(old?.dueAt?:loan.dueAt).plusDays(7).toString()
        return LoanRenewalResult(requestId,due,count).also {renewals.getOrPut(scope.id){mutableMapOf()}[requestId]=it}
    }
    private fun questions()=ActionForm(listOf(ActionQuestion("project","What will you work on?","short_text",true,emptyList()),ActionQuestion("safety","Have you completed the safety introduction?","yes_no",true,emptyList())))
    override suspend fun space(scope:MemberScope,publicToken:String)=ActionSpace(base.spaces(scope).first {it.id==publicToken},questions())
    override suspend fun availability(scope:MemberScope,publicToken:String,startsAtISO:String,endsAtISO:String):ActionAvailability {
        ready(scope);space(scope,publicToken);return ActionAvailability(publicToken,startsAtISO,endsAtISO,"Asia/Kolkata",listOf(ActionWindow(startsAtISO,endsAtISO)),emptyList())
    }
    override suspend fun book(scope:MemberScope,publicToken:String,startsAtISO:String,endsAtISO:String,answers:Map<String,JsonElement>):String {
        ready(scope);val space=space(scope,publicToken);require(ActionForms.errors(space.form,answers).isEmpty())
        val conflict=bookings(scope).items.any {it.status!="cancelled"&&OffsetDateTime.parse(it.startsAt).isBefore(OffsetDateTime.parse(endsAtISO))&&OffsetDateTime.parse(it.endsAt).isAfter(OffsetDateTime.parse(startsAtISO))&&it.spaceName==space.space.name}
        if(conflict)throw ApiFailure(409,"That time is no longer available. Choose another time.",null,null)
        val status=if(space.space.approvalMode=="instant")"approved"else"pending"
        val booking=MemberBooking(UUID.randomUUID().toString(),space.space.name,"Asia/Kolkata",startsAtISO,endsAtISO,status,true,OffsetDateTime.parse(startsAtISO).minusHours(24).toString())
        bookingChanges.getOrPut(scope.id){mutableMapOf()}[booking.id]=booking;return status
    }
    override suspend fun reschedule(scope:MemberScope,bookingToken:String,startsAtISO:String,endsAtISO:String):MemberBooking {ready(scope);val booking=bookings(scope).items.first {it.id==bookingToken};return booking.copy(startsAt=startsAtISO,endsAt=endsAtISO).also {bookingChanges.getOrPut(scope.id){mutableMapOf()}[it.id]=it}}
    override suspend fun cancelBooking(scope:MemberScope,bookingToken:String):MemberBooking {ready(scope);val booking=bookings(scope).items.first {it.id==bookingToken};return booking.copy(status="cancelled",canCancel=false).also {bookingChanges.getOrPut(scope.id){mutableMapOf()}[it.id]=it}}
    override suspend fun event(scope:MemberScope,publicToken:String):ActionEvent {val value=base.publicEvents(scope).first {it.id==publicToken};return ActionEvent(value,questions(),"members",listOf(ActionTicket("1","Member place",value.paymentAmount,"members",10)),false,true)}
    override suspend fun register(scope:MemberScope,eventToken:String,answers:Map<String,JsonElement>,phone:String?,email:String?,inviteCode:String?,ticketId:String?,promoCode:String?):String {
        ready(scope);val event=event(scope,eventToken);require(ActionForms.errors(event.form,answers).isEmpty());require(ticketId=="1")
        val status=if(event.event.requiresApproval)"waitlisted"else"registered"
        val value=MemberRegistration((nextRegistration++).toString(),event.event.title,event.event.startsAt,event.event.endsAt,status,true,OffsetDateTime.parse(event.event.startsAt).minusHours(24).toString(),null,if(status=="waitlisted")3 else null,false,null)
        registrations.getOrPut(scope.id){mutableMapOf()}[value.id]=value;return status
    }
    override suspend fun cancelRegistration(scope:MemberScope,registrationId:String):String {ready(scope);val value=overview(scope).registrations.first {it.id==registrationId};registrations.getOrPut(scope.id){mutableMapOf()}[value.id]=value.copy(status="cancelled",canCancel=false,checkinToken=null);return "cancelled"}
    override suspend fun registrationQR(scope:MemberScope,registrationId:String):ByteArray {ready(scope);if(overview(scope).registrations.none {it.id==registrationId&&it.status=="registered"})throw ApiFailure(403,"A ticket is available only for a confirmed registration.",null,null);return DEMO_TICKET_SVG.toByteArray()}
    override suspend fun profile(scope:MemberScope):ActionProfile {ready(scope);return contact?:initialProfile()}
    override suspend fun updateProfile(scope:MemberScope,value:ActionProfile):ActionProfile {ready(scope);contact=value;return value}
    override suspend fun preferences(scope:MemberScope):List<ActionPreference> {ready(scope);return prefs.getOrPut(scope.id){listOf(ActionPreference("hardware","email",true,true,listOf("loan_overdue"),false),ActionPreference("events","in_app",true,true,emptyList(),null),ActionPreference("events","native_push",false,false,emptyList(),null))}}
    override suspend fun updatePreference(scope:MemberScope,feature:String,channel:String,enabled:Boolean,dailyDigest:Boolean?):List<ActionPreference> {ready(scope);return preferences(scope).map {if(it.feature==feature&&it.channel==channel){if(!it.available&&enabled)throw ApiFailure(403,"This channel is unavailable for your makerspace.",null,null);it.copy(enabled=enabled,dailyDigest=dailyDigest)}else it}.also {prefs[scope.id]=it}}
}
