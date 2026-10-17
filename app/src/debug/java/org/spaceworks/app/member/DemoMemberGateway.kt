package org.spaceworks.app.member

import kotlinx.coroutines.delay
import org.spaceworks.app.network.ApiFailure

/** In-memory DEBUG fixtures. No API, notification, payment or real membership side effects. */
class DemoMemberGateway:MemberGateway,MemberPreviewControl {
    private var scenario=MemberPreviewScenario.POPULATED
    private val read=mutableMapOf<Int,MutableSet<String>>()
    override fun scenario(value:MemberPreviewScenario){scenario=value}
    private suspend fun available() {
        delay(220)
        when(scenario){
            MemberPreviewScenario.OFFLINE->throw java.io.IOException("Simulated offline")
            MemberPreviewScenario.RESTRICTED->throw ApiFailure(403,"You do not have permission to do this.",null,null)
            else->Unit
        }
    }
    private fun items(scope:MemberScope):List<InventoryItem> {
        if(scenario==MemberPreviewScenario.EMPTY)return emptyList()
        val names=if(scope.id==1)listOf("Cordless drill","Digital multimeter","Soldering station","Safety goggles")
            else listOf("Orbital sander","Precision calipers","Heat gun","Face shield")
        return names.mapIndexed {index,name->InventoryItem((scope.id*100+index+1).toString(),name,
            listOf("A dependable tool for your next build. Ask the team if you need a quick introduction.","Measure with care. Please return the leads and accessories together.","Use at a ventilated workstation after your safety introduction.","Personal protection for hands-on work. Check the fit before use.")[index],
            if(index<2)"1" else "2",if(index<2)"Tools & measurement" else "Workshop essentials",if(index<2)"tools" else "essentials",
            "For use by trained members. Check the tool before borrowing and return all accessories clean and complete.",null,
            listOf("3 available","1 left","Unavailable","Ask your makerspace about availability.")[index],
            listOf("exact_count","exact_count","status_only","hidden")[index],if(index<2)listOf(3,1)[index]else null)}
    }
    override suspend fun inventory(scope:MemberScope,query:String,category:String?,next:String?):MemberPage<InventoryItem> {
        available();val filtered=items(scope).filter {it.name.contains(query,true) && (category==null || it.categorySlug==category)}
        val offset=next?.removePrefix("demo-page:")?.toIntOrNull() ?: 0
        return MemberPage(filtered.drop(offset).take(2),if(offset+2<filtered.size)"demo-page:${offset+2}" else null,filtered.size)
    }
    override suspend fun categories(scope:MemberScope):List<InventoryCategory> {
        available();return if(scenario==MemberPreviewScenario.EMPTY)emptyList() else listOf(InventoryCategory("1","Tools & measurement","tools",2),InventoryCategory("2","Workshop essentials","essentials",2))
    }
    override suspend fun product(scope:MemberScope,id:String):InventoryItem {
        available();return items(scope).firstOrNull {it.id==id} ?: throw ApiFailure(404,"This item wasn't found.",null,null)
    }
    private fun bookingRows(scope:MemberScope)=if(scenario==MemberPreviewScenario.EMPTY)emptyList() else listOf(
        MemberBooking("00000000-0000-0000-0000-000000000011",if(scope.id==1)"Electronics bench" else "Woodworking bench","Asia/Kolkata","2026-10-18T10:00:00+05:30","2026-10-18T12:00:00+05:30","approved",true,"2026-10-17T10:00:00+05:30"),
        MemberBooking("00000000-0000-0000-0000-000000000012","Project room","Asia/Kolkata","2026-10-20T14:00:00+05:30","2026-10-20T16:00:00+05:30","pending",true,"2026-10-19T14:00:00+05:30"))
    override suspend fun bookings(scope:MemberScope,period:String,next:String?):MemberPage<MemberBooking> {
        available();val rows=bookingRows(scope);return MemberPage(rows,null,rows.size)
    }
    override suspend fun overview(scope:MemberScope):MemberOverview {
        available();val empty=scenario==MemberPreviewScenario.EMPTY;val bookings=bookingRows(scope)
        return MemberOverview(
            if(empty)emptyList() else listOf(MemberNoticeRow("info","workshop","A little planning goes a long way","Bring your project notes to this weekend's open build session."),MemberNoticeRow("warning","loan_due","One loan needs your attention","Your multimeter is overdue. Open Activity to review it.")),
            if(empty)emptyList() else listOf(MemberLoan("101","Cordless drill","2026-10-15T10:00:00+05:30","2026-10-22T18:00:00+05:30",false,true,null,0),MemberLoan("102","Digital multimeter","2026-10-05T11:00:00+05:30","2026-10-10T18:00:00+05:30",true,false,"Return this overdue item before requesting another renewal.",1)),
            if(empty)emptyList() else listOf(MemberLoanHistoryRow("Precision screwdriver set","2026-09-10T10:00:00+05:30","2026-09-12T16:00:00+05:30","2026-09-17T18:00:00+05:30",false)),
            if(empty)emptyList() else listOf(MemberRequestRow("pending","2026-10-16T09:00:00+05:30",2,0,0,0),MemberRequestRow("accepted","2026-10-15T09:00:00+05:30",1,0,0,0)),
            MemberPage(bookings,null,bookings.size),
            if(empty)emptyList() else listOf(MemberRegistration("501","Make a desk light","2026-10-24T10:00:00+05:30","2026-10-24T13:00:00+05:30","registered",true,"2026-10-23T10:00:00+05:30","demo-ticket-only",null,false,null),MemberRegistration("502","Introduction to electronics","2026-10-25T10:00:00+05:30","2026-10-25T12:00:00+05:30","pending",true,"2026-10-24T10:00:00+05:30",null,null,false,null),MemberRegistration("503","Woodworking basics","2026-10-26T10:00:00+05:30","2026-10-26T13:00:00+05:30","waitlisted",true,"2026-10-25T10:00:00+05:30",null,2,false,null)),
            if(scenario==MemberPreviewScenario.UNKNOWN_DUES)null else MemberDuesSummary(if(empty)"0" else "350.00",if(empty)emptyMap() else mapOf("INR" to "350.00")),
            MemberTermSummary("Maker membership","2026-10-01","2026-10-31",true,false),true,false,null,false,
            if(empty)emptyList() else listOf(MemberServiceRequest("demo-print-1","Enclosure prototype","queued","2026-10-15T15:00:00+05:30","printing",2,45)))
    }
    override suspend fun card(scope:MemberScope):MemberCard {
        available()
        val notIssued=scenario in listOf(MemberPreviewScenario.NO_CARD,MemberPreviewScenario.EMPTY)
        val active=!notIssued && scenario!=MemberPreviewScenario.REVOKED_CARD
        return MemberCard(if(notIssued)null else "member-card-${scope.id}",if(notIssued)null else 1042,"Meera Nair",if(active)DEMO_CARD_IMAGE else null,active,active,notIssued)
    }
    private fun inboxRows(scope:MemberScope):List<InboxItem> {
        if(scenario==MemberPreviewScenario.EMPTY)return emptyList()
        val readIds=read[scope.id].orEmpty()
        return listOf("Loan due soon" to "Your drill is due on 22 October. Plan a return or review renewal in Activity.","You're registered" to "Your spot at Make a desk light is confirmed.","A place to make" to "Your makerspace has shared this month's open workshop schedule.").mapIndexed {i,pair->
            InboxItem((i+1).toString(),pair.first,pair.second,listOf("hardware","events","membership")[i],"demo_update",null,if((i+1).toString() in readIds)"2026-10-16T12:00:00+05:30" else null,"2026-10-16T09:00:00+05:30")}
    }
    override suspend fun inbox(scope:MemberScope,unread:Boolean,next:String?):MemberPage<InboxItem> {
        available();val rows=inboxRows(scope).filter {!unread || it.readAt==null};val offset=next?.removePrefix("demo-page:")?.toIntOrNull() ?: 0
        return MemberPage(rows.drop(offset).take(2),if(offset+2<rows.size)"demo-page:${offset+2}" else null,rows.size)
    }
    override suspend fun markRead(scope:MemberScope,id:String){available();read.getOrPut(scope.id){mutableSetOf()}.add(id)}
    override suspend fun markAllRead(scope:MemberScope){available();read.getOrPut(scope.id){mutableSetOf()}.addAll(inboxRows(scope).map {it.id})}
    override suspend fun publicEvents(scope:MemberScope):List<MemberEvent> {
        available();return if(scenario==MemberPreviewScenario.EMPTY)emptyList() else listOf(
            MemberEvent("00000000-0000-0000-0000-000000000021","Make a desk light","Design, solder and assemble a small light with the workshop team.","2026-10-24T10:00:00+05:30","2026-10-24T13:00:00+05:30","Electronics studio","350.00","INR","Places available",true,false,null),
            MemberEvent("00000000-0000-0000-0000-000000000022","Woodworking basics","Build confidence with essential hand tools and safe workshop habits.","2026-10-26T10:00:00+05:30","2026-10-26T13:00:00+05:30","Wood workshop","0.00","INR","Waitlist available",true,true,null))
    }
    override suspend fun spaces(scope:MemberScope):List<MemberSpace> {
        available();return if(scenario==MemberPreviewScenario.EMPTY)emptyList() else listOf(
            MemberSpace("00000000-0000-0000-0000-000000000031","Electronics bench","A shared bench for circuits, soldering and small assemblies.","workstation",2,"Ground floor",null,"instant",true),
            MemberSpace("00000000-0000-0000-0000-000000000032","Project room","A quiet place to plan and work with your project team.","room",6,"First floor",null,"manual",true))
    }
}
