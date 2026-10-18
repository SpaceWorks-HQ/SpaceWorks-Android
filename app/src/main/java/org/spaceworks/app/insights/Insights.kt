package org.spaceworks.app.insights

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.spaceworks.app.auth.requireBody
import org.spaceworks.app.member.MemberLoad
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.network.ApiSession
import org.spaceworks.app.network.retryDeadlineMillis
import org.spaceworks.generated.api.DashboardApi
import org.spaceworks.generated.api.MemberActivityApi
import org.spaceworks.generated.api.MessagingApi
import org.spaceworks.generated.model.Channel06eEnum
import org.spaceworks.generated.model.ConsentWrite
import org.spaceworks.generated.model.ConsentWriteActionEnum
import org.spaceworks.generated.model.MemberUsageSeries
import org.spaceworks.generated.model.MessagingChannel

/** One labelled point of a server series; [value] is the server's number as text, never recomputed. */
data class UsagePoint(val label:String,val value:String,val amount:Double)
/** A server series reduced to its x label and first declared measure. Empty points means table-only. */
data class UsageSeries(val points:List<UsagePoint>,val unit:String,val summary:String)
data class LoanUsage(val count:Int,val returned:Int,val currentlyOut:Int,val monthly:UsageSeries)
data class MachineUsage(val hours:String,val monthly:UsageSeries,val top:UsageSeries)
data class CountUsage(val count:Int,val monthly:UsageSeries)
data class PaymentUsage(val paidByCurrency:Map<String,String>,val outstandingByCurrency:Map<String,String>)
/** Absent sections are gated or unavailable; they are never shown as zero usage. */
data class UsageSummary(val available:Boolean,val loans:LoanUsage?,val machines:MachineUsage?,val bookings:CountUsage?,
    val eventsAttended:Int?,val payments:PaymentUsage?)

enum class StaffScope { FULL, MACHINE, HANDOVER }
data class StaffCounter(val key:String,val value:Int)
data class StaffWidget(val key:String,val title:String,val kind:String,val headline:String?)
data class StaffDashboard(val scope:StaffScope,val counters:List<StaffCounter>,val queue:List<StaffCounter>,
    val widgets:List<StaffWidget>,val outstandingByCurrency:Map<String,String>)

data class ConsentChannel(val available:Boolean,val consented:Boolean,val phoneVerified:Boolean,val phoneMasked:String,val suppressed:Boolean) {
    val canOptIn get()=available && phoneVerified && !suppressed && !consented
}
data class MessagingConsent(val sms:ConsentChannel,val whatsapp:ConsentChannel)

interface InsightsGateway {
    suspend fun usage(scope:MemberScope):UsageSummary
    suspend fun dashboard(scope:MemberScope):StaffDashboard
    suspend fun messaging(scope:MemberScope):MessagingConsent
    suspend fun consent(scope:MemberScope,channel:String,optIn:Boolean):MessagingConsent
}

/** Generated operations only; every call carries the selected makerspace header. */
class GeneratedInsightsGateway(private val session:ApiSession):InsightsGateway {
    private fun <T> api(type:Class<T>,scope:MemberScope)=session.scopedApi(type,scope.id)
    override suspend fun usage(scope:MemberScope):UsageSummary {
        val value=api(MemberActivityApi::class.java,scope).apiV1MemberMakerspacesMyUsageRetrieve(scope.id).requireBody()
        return UsageSummary(value.usageAvailable,
            value.loans?.let {LoanUsage(it.count,it.returnedCount,it.currentlyOut,series(it.monthly))},
            value.machines?.let {MachineUsage(it.hours,series(it.monthly),series(it.topMachines))},
            value.bookings?.let {CountUsage(it.count,series(it.monthly))},
            value.events?.attendedCount,
            value.payments?.let {PaymentUsage(it.paidByCurrency,it.outstandingByCurrency)})
    }
    override suspend fun dashboard(scope:MemberScope):StaffDashboard {
        val value=api(DashboardApi::class.java,scope).apiV1AdminMakerspaceDashboardRetrieve(scope.id).requireBody()
        // The schema declares default 0 for every counter, so an omitted (unavailable) counter cannot be
        // told apart from zero. Only server-reported non-zero counters are shown.
        val counters=listOf("pending_requests" to value.pendingRequests,"awaiting_issue" to value.awaitingIssue,"overdue_loans" to value.overdueLoans,
            "pending_bookings" to value.pendingBookings,"upcoming_bookings" to value.upcomingBookings,"upcoming_events" to value.upcomingEvents,
            "pending_event_registrations" to value.pendingEventRegistrations,"open_problem_reports" to value.openProblemReports,"low_stock" to value.lowStock,
            "pending_prints" to value.pendingPrints,"active_prints" to value.activePrints,"prints_awaiting_collection" to value.printsAwaitingCollection,
            "maintenance_overdue" to value.maintenanceOverdue,"warranty_expiring" to value.warrantyExpiring,
            "stocktakes_awaiting_approval" to value.stocktakesAwaitingApproval,"pending_payments" to value.pendingPayments,"failed_emails" to value.failedEmails)
            .mapNotNull {(key,count)->count?.takeIf {it>0}?.let {StaffCounter(key,it)}}
        val queue=value.queueTiles?.let {tiles->listOfNotNull(tiles.readyToIssue?.let {StaffCounter("ready_to_issue",it)},
            tiles.dueReturns?.let {StaffCounter("due_returns",it)},tiles.pickups?.let {StaffCounter("pickups",it)})}.orEmpty()
        val widgets=value.widgets.orEmpty().map {StaffWidget(it.key,it.title,it.kind.value,headline(it.`data`))}
        return StaffDashboard(when(value.scopeMode.value){"handover"->StaffScope.HANDOVER;"machine"->StaffScope.MACHINE;else->StaffScope.FULL},
            counters,queue,widgets,value.outstandingByCurrency.orEmpty())
    }
    override suspend fun messaging(scope:MemberScope)=consent(api(MessagingApi::class.java,scope).apiV1MeMessagingRetrieve(scope.id).requireBody())
    override suspend fun consent(scope:MemberScope,channel:String,optIn:Boolean):MessagingConsent {
        val wire=requireNotNull(Channel06eEnum.entries.firstOrNull {it.value==channel})
        val action=if(optIn)ConsentWriteActionEnum.opted_in else ConsentWriteActionEnum.opted_out
        return consent(api(MessagingApi::class.java,scope).apiV1MeMessagingConsentCreate(ConsentWrite(scope.id,wire,action)).requireBody())
    }
    private fun consent(value:org.spaceworks.generated.model.MemberMessaging)=MessagingConsent(channel(value.sms),channel(value.whatsapp))
    private fun channel(value:MessagingChannel)=ConsentChannel(value.available,value.consented,value.phoneVerified,value.phoneMasked,value.suppressed)
    companion object {
        private fun text(value:JsonElement?)=(value as? JsonPrimitive)?.contentOrNull
        /** Uses only the series' declared x field and first measure; rows without them are skipped. */
        fun series(value:MemberUsageSeries):UsageSeries {
            val x=value.chartSpec.x.`field`
            val measure=value.chartSpec.propertyValues.firstOrNull()
            val points=if(measure==null)emptyList() else value.rows.mapNotNull {row->
                val label=text(row[x])?:return@mapNotNull null
                val raw=text(row[measure.`field`])?:return@mapNotNull null
                raw.toDoubleOrNull()?.let {UsagePoint(label,raw,it)}
            }
            return UsageSeries(points,measure?.unit.orEmpty(),value.chartSpec.altTemplate)
        }
        /** A count/queue widget's single headline number, when the report object exposes one. */
        fun headline(data:Map<String,JsonElement>):String? =
            listOf("count","total","value").firstNotNullOfOrNull {key->text(data[key])?.takeIf {it.toDoubleOrNull()!=null}}
    }
}

data class InsightsState(val scope:MemberScope?=null,val usage:MemberLoad<UsageSummary> = MemberLoad(),
    val dashboard:MemberLoad<StaffDashboard> = MemberLoad(),val messaging:MemberLoad<MessagingConsent> = MemberLoad(),
    val consentBusy:String?=null,val consentError:String?=null,val retryAtMillis:Long=0)

/** Per-session, per-makerspace insight state. Scope changes and sign-out discard everything cached. */
class InsightsStateHolder(private val api:InsightsGateway,private val now:()->Long=System::currentTimeMillis,
    private val failureText:(ApiFailure)->String={it.detail},private val unavailableText:()->String={"Couldn't connect. Try again."},
    private val onUnauthorized:suspend()->Unit={}) {
    private val mutable=MutableStateFlow(InsightsState());val state=mutable.asStateFlow()
    private var generation=0L
    fun activate(scope:MemberScope){if(state.value.scope!=scope){generation++;mutable.value=InsightsState(scope=scope,retryAtMillis=state.value.retryAtMillis)}}
    fun clear(){generation++;mutable.value=InsightsState()}
    private suspend fun <T> load(read:(InsightsState)->MemberLoad<T>,write:(InsightsState,MemberLoad<T>)->InsightsState,block:suspend(MemberScope)->T) {
        val scope=state.value.scope?:return
        if(read(state.value).loading || now()<state.value.retryAtMillis)return
        val owner=generation
        mutable.update {write(it,read(it).copy(loading=true,error=null))}
        try {
            val value=block(scope)
            if(owner==generation)mutable.update {write(it,MemberLoad(value))}
        }catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){
            if(owner!=generation)return
            val message=(failure as? ApiFailure)?.let(failureText)?:unavailableText()
            val retry=(failure as? ApiFailure)?.takeIf {it.status==429}?.let {retryDeadlineMillis(now(),it.retryAfterSeconds?:60)}?:state.value.retryAtMillis
            mutable.update {write(it,read(it).copy(loading=false,error=message)).copy(retryAtMillis=retry)}
            if((failure as? ApiFailure)?.status==401)onUnauthorized()
        }finally {if(owner==generation)mutable.update {write(it,read(it).copy(loading=false))}}
    }
    suspend fun refreshUsage()=load({it.usage},{s,v->s.copy(usage=v)}) {api.usage(it)}
    suspend fun refreshDashboard()=load({it.dashboard},{s,v->s.copy(dashboard=v)}) {api.dashboard(it)}
    suspend fun refreshMessaging()=load({it.messaging},{s,v->s.copy(messaging=v)}) {api.messaging(it)}
    /** The server decides consent; the switch shows its returned state, and any refusal refetches it. */
    suspend fun setConsent(channel:String,optIn:Boolean) {
        val scope=state.value.scope?:return
        if(state.value.consentBusy!=null || now()<state.value.retryAtMillis)return
        val current=state.value.messaging.value?:return
        val target=if(channel=="sms")current.sms else current.whatsapp
        if(optIn && !target.canOptIn)return
        val owner=generation
        mutable.update {it.copy(consentBusy=channel,consentError=null)}
        try {
            val value=api.consent(scope,channel,optIn)
            if(owner==generation)mutable.update {it.copy(messaging=MemberLoad(value))}
        }catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){
            if(owner!=generation)return
            mutable.update {it.copy(consentError=(failure as? ApiFailure)?.let(failureText)?:unavailableText(),consentBusy=null)}
            refreshMessaging()
        }finally {if(owner==generation)mutable.update {it.copy(consentBusy=null)}}
    }
}
