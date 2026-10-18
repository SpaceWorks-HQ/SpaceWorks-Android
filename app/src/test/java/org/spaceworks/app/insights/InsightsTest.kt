package org.spaceworks.app.insights

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.*
import java.util.concurrent.TimeUnit

private const val SPEC="""{"series":["issued_count"],"x":{"field":"period","kind":"time"},"values":[{"field":"issued_count","unit":"loans"}],"currency_field":null,"partition":null,"negatives":"clip","truncation":null,"alt_template":"Loans by month"}"""
private const val HOURS_SPEC="""{"series":["hours"],"x":{"field":"period","kind":"time"},"values":[{"field":"hours","unit":"h"}],"currency_field":null,"partition":"unit","negatives":"clip","truncation":null,"alt_template":"Hours"}"""
private const val TOP_SPEC="""{"series":["hours"],"x":{"field":"machine","kind":"category"},"values":[{"field":"hours","unit":"h"}],"currency_field":null,"partition":null,"negatives":"clip","truncation":{"top_n":5,"other_label":"Other"},"alt_template":"Top"}"""
private const val TABLE_SPEC="""{"series":[],"x":{"field":"currency","kind":"category"},"values":[],"currency_field":"currency","partition":"currency","negatives":"allow","truncation":null,"alt_template":"Totals"}"""
private const val CHANNEL_ON="""{"available":true,"consented":true,"phone_verified":true,"phone_masked":"•••• 3210","suppressed":false}"""
private const val CHANNEL_OFF="""{"available":true,"consented":false,"phone_verified":false,"phone_masked":"","suppressed":false}"""

class InsightsGatewayTest {
    private val scope=MemberScope(7,"workshop")
    private fun json(body:String,code:Int=200)=MockResponse().setResponseCode(code).setHeader("Content-Type","application/json").setBody(body)
    private fun withGateway(block:suspend(GeneratedInsightsGateway,MockWebServer)->Unit)=runBlocking {
        MockWebServer().use {server->
            val tokens=MemoryTokenStore().apply {replace(TokenPair("access","refresh"))}
            block(GeneratedInsightsGateway(ApiSession(server.url("/"),tokens)),server)
        }
    }
    private fun MockWebServer.next()=requireNotNull(takeRequest(5,TimeUnit.SECONDS))

    @Test fun usageKeepsServerNumbersAndOmitsGatedSections()=withGateway {gateway,server->
        server.enqueue(json("""{"usage_available":true,
            "loans":{"count":12,"returned_count":10,"currently_out":2,"monthly":{"rows":[{"period":"2026-09","issued_count":4,"return_events":3},{"period":"2026-10","issued_count":"3"},{"issued_count":9}],"chart_spec":$SPEC}},
            "machines":{"hours":"7.50","monthly":{"rows":[{"period":"2026-10","hours":"7.50"}],"chart_spec":$HOURS_SPEC},"top_machines":{"rows":[{"machine_id":1,"machine":"Laser","hours":"4.5"}],"chart_spec":$TOP_SPEC}},
            "payments":{"paid_by_currency":{"INR":"1250.00","USD":"5.00"},"outstanding_by_currency":{},"totals":{"rows":[],"chart_spec":$TABLE_SPEC}}}"""))
        val usage=gateway.usage(scope)
        assertTrue(usage.available);assertNull(usage.bookings);assertNull(usage.eventsAttended)
        assertEquals(12,usage.loans!!.count);assertEquals(2,usage.loans!!.currentlyOut)
        // Only rows with both the declared x and first measure are kept; numbers stay as the server sent them.
        assertEquals(listOf("2026-09" to "4","2026-10" to "3"),usage.loans!!.monthly.points.map {it.label to it.value})
        assertEquals("loans",usage.loans!!.monthly.unit);assertEquals("7.50",usage.machines!!.hours)
        assertEquals("Laser",usage.machines!!.top.points.single().label)
        assertEquals(mapOf("INR" to "1250.00","USD" to "5.00"),usage.payments!!.paidByCurrency)
        val request=server.next();assertEquals("/api/v1/member/makerspaces/7/my-usage",request.path);assertEquals("7",request.getHeader("X-Makerspace-Id"))
    }
    @Test fun tableOnlySeriesHasNoPoints() {
        val series=GeneratedInsightsGateway.series(org.spaceworks.generated.model.MemberUsageSeries(listOf(mapOf("currency" to JsonPrimitive("INR"))),
            Json {ignoreUnknownKeys=true}.decodeFromString(org.spaceworks.generated.model.ReportChartSpec.serializer(),TABLE_SPEC)))
        assertTrue(series.points.isEmpty());assertEquals("Totals",series.summary)
    }
    @Test fun dashboardShowsReportedCountersQueueAndWidgets()=withGateway {gateway,server->
        server.enqueue(json("""{"scope_mode":"full","pending_requests":3,"overdue_loans":0,"outstanding_by_currency":{"INR":"250.00"},
            "widgets":[{"key":"loans","title":"Loans this month","kind":"count","required_action":"view_reports","required_modules":[],"report_keys":["loans"],"default_period":30,"data":{"count":18},"chart_spec":null},
                       {"key":"heat","title":"Busy hours","kind":"heatmap","required_action":"view_reports","required_modules":[],"report_keys":[],"default_period":7,"data":{"rows":[]},"chart_spec":null}]}"""))
        val dashboard=gateway.dashboard(scope)
        assertEquals(StaffScope.FULL,dashboard.scope);assertEquals(listOf(StaffCounter("pending_requests",3)),dashboard.counters)
        assertEquals(listOf("18",null),dashboard.widgets.map {it.headline});assertEquals(mapOf("INR" to "250.00"),dashboard.outstandingByCurrency)
        assertEquals("/api/v1/admin/makerspace/7/dashboard",server.next().path)
        server.enqueue(json("""{"scope_mode":"handover","widgets":[],"queue_tiles":{"ready_to_issue":4,"due_returns":0,"pickups":1}}"""))
        val handover=gateway.dashboard(scope)
        assertEquals(StaffScope.HANDOVER,handover.scope);assertTrue(handover.counters.isEmpty())
        assertEquals(listOf(StaffCounter("ready_to_issue",4),StaffCounter("due_returns",0),StaffCounter("pickups",1)),handover.queue);server.next()
    }
    @Test fun messagingReadsScopedSettingsAndWritesExactConsent()=withGateway {gateway,server->
        server.enqueue(json("""{"sms":$CHANNEL_ON,"whatsapp":$CHANNEL_OFF}"""))
        val consent=gateway.messaging(scope)
        assertTrue(consent.sms.consented);assertFalse(consent.whatsapp.canOptIn);assertEquals("•••• 3210",consent.sms.phoneMasked)
        val read=server.next();assertEquals("/api/v1/me/messaging?makerspace=7",read.path);assertEquals("7",read.getHeader("X-Makerspace-Id"))
        server.enqueue(json("""{"sms":$CHANNEL_OFF,"whatsapp":$CHANNEL_OFF}"""))
        assertFalse(gateway.consent(scope,"sms",false).sms.consented)
        val write=server.next();assertEquals("POST",write.method);assertEquals("/api/v1/me/messaging/consent",write.path)
        assertEquals(buildJsonObject {put("makerspace",7);put("channel","sms");put("action","opted_out")},Json.parseToJsonElement(write.body.readUtf8()))
        server.enqueue(json("""{"detail":"Number suppressed after STOP."}""",409))
        try {gateway.consent(scope,"whatsapp",true);fail()}catch(f:ApiFailure){assertEquals("Number suppressed after STOP.",f.detail)};server.next()
    }
}

private class FakeInsights:InsightsGateway {
    var usageCalls=0;var consentCalls=0;var messagingCalls=0
    var consentFailure:ApiFailure?=null
    var usageFailure:ApiFailure?=null
    var state=MessagingConsent(ConsentChannel(true,false,true,"x",false),ConsentChannel(true,false,false,"",false))
    override suspend fun usage(scope:MemberScope):UsageSummary {usageCalls++;usageFailure?.let {throw it};return UsageSummary(true,null,null,null,scope.id,null)}
    override suspend fun dashboard(scope:MemberScope)=StaffDashboard(StaffScope.FULL,emptyList(),emptyList(),emptyList(),emptyMap())
    override suspend fun messaging(scope:MemberScope):MessagingConsent {messagingCalls++;return state}
    override suspend fun consent(scope:MemberScope,channel:String,optIn:Boolean):MessagingConsent {
        consentCalls++;consentFailure?.let {throw it}
        state=state.copy(sms=state.sms.copy(consented=optIn));return state
    }
}

class InsightsStateTest {
    @Test fun scopeChangeDiscardsCachedUsage()=runTest {
        val api=FakeInsights();val holder=InsightsStateHolder(api)
        holder.activate(MemberScope(7,"a"));holder.refreshUsage();assertEquals(7,holder.state.value.usage.value!!.eventsAttended)
        holder.activate(MemberScope(8,"b"));assertNull(holder.state.value.usage.value)
        holder.refreshUsage();assertEquals(8,holder.state.value.usage.value!!.eventsAttended)
        holder.clear();assertNull(holder.state.value.scope)
    }
    @Test fun consentReflectsServerAndRefetchesAfterRefusal()=runTest {
        val api=FakeInsights();val holder=InsightsStateHolder(api);holder.activate(MemberScope(7,"a"));holder.refreshMessaging()
        holder.setConsent("whatsapp",true);assertEquals(0,api.consentCalls)
        holder.setConsent("sms",true);assertTrue(holder.state.value.messaging.value!!.sms.consented)
        api.consentFailure=ApiFailure(409,"Number changed. Verify again.",null,null)
        holder.setConsent("sms",false)
        assertEquals("Number changed. Verify again.",holder.state.value.consentError);assertEquals(2,api.messagingCalls)
        assertTrue(holder.state.value.messaging.value!!.sms.consented);assertNull(holder.state.value.consentBusy)
    }
    @Test fun throttledUsageWaitsForRetryAfter()=runTest {
        var clock=0L
        val api=FakeInsights().apply {usageFailure=ApiFailure(429,"Wait.",null,30)}
        val holder=InsightsStateHolder(api,now={clock});holder.activate(MemberScope(7,"a"))
        holder.refreshUsage();assertEquals("Wait.",holder.state.value.usage.error);holder.refreshUsage();assertEquals(1,api.usageCalls)
        clock=30_000;api.usageFailure=null;holder.refreshUsage();assertEquals(2,api.usageCalls);assertNotNull(holder.state.value.usage.value)
    }
}
