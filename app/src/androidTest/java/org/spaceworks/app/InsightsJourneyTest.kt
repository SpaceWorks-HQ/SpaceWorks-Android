package org.spaceworks.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.spaceworks.app.auth.*
import org.spaceworks.app.insights.*
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.*
import org.spaceworks.app.theme.SpaceWorksTheme
import org.spaceworks.app.ui.AccountFlowScreen
import org.spaceworks.generated.model.*

private class JourneyInsights:InsightsGateway {
    var sms=ConsentChannel(true,false,true,"•••• 3210",false)
    var whatsapp=ConsentChannel(true,false,false,"",false)
    var writes=mutableListOf<Pair<String,Boolean>>()
    private fun series(vararg points:Pair<String,Int>)=UsageSeries(points.map {UsagePoint(it.first,it.second.toString(),it.second.toDouble())},"","")
    override suspend fun usage(scope:MemberScope)=UsageSummary(true,LoanUsage(12,10,2,series("Sep 2026" to 4,"Oct 2026" to 3)),null,
        CountUsage(5,series("Oct 2026" to 2)),3,PaymentUsage(mapOf("inr" to "1250.00"),emptyMap()))
    override suspend fun dashboard(scope:MemberScope)=StaffDashboard(StaffScope.HANDOVER,emptyList(),
        listOf(StaffCounter("ready_to_issue",4),StaffCounter("due_returns",1)),listOf(StaffWidget("hidden","Analytics","count","9")),emptyMap())
    override suspend fun messaging(scope:MemberScope)=MessagingConsent(sms,whatsapp)
    override suspend fun consent(scope:MemberScope,channel:String,optIn:Boolean):MessagingConsent {
        writes+=channel to optIn;sms=sms.copy(consented=optIn);return MessagingConsent(sms,whatsapp)
    }
}

class InsightsJourneyTest {
    @get:Rule val compose=createComposeRule()
    private val journey by lazy {NativeJourney(compose,"v2")}
    private fun show(content:@androidx.compose.runtime.Composable ()->Unit) {
        DeviceWindowGuard.awaitClear()
        compose.setContent {SpaceWorksTheme(DeviceProfile.dark) {Surface(Modifier.fillMaxSize()) {Box(Modifier.fillMaxSize().safeDrawingPadding()) {content()}}}}
    }
    private fun holder(api:InsightsGateway)=InsightsStateHolder(api).apply {activate(MemberScope(7,"fab-lab"))}
    @Test fun memberUsageShowsServerNumbersWithWrittenValues() {
        val insights=holder(JourneyInsights())
        show {Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {MemberUsagePanel(insights)}}
        journey.waitText("Your activity");journey.waitText("Out now")
        journey.visible(compose.onNodeWithContentDescription("Sep 2026: 4",substring=true))
        journey.waitText("INR 1250.00");journey.shot("v2-member-usage")
    }
    @Test fun handoverStaffSeeQueueTilesWithoutAnalytics() {
        val insights=holder(JourneyInsights())
        show {Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {StaffDashboardPanel(insights)}}
        journey.waitText("Handover queue");journey.waitText("Ready to issue");journey.waitText("Due back")
        compose.onNodeWithText("Analytics").assertDoesNotExist();journey.shot("v2-staff-handover")
    }
    @Test fun consentSwitchFollowsServerAndExplainsVerification() {
        val api=JourneyInsights();val insights=holder(api)
        show {MessagingConsentScreen(insights,{},{})}
        journey.waitText("Text and WhatsApp messages");journey.waitText("Messages go to •••• 3210.")
        journey.waitText("Verify your phone number in Account and security before turning this on.")
        compose.onNodeWithTag("consent-whatsapp").assertIsNotEnabled()
        journey.clickTag("consent-sms")
        compose.waitUntil(10000) {api.writes.isNotEmpty()}
        assertEquals(listOf("sms" to true),api.writes);journey.waitText("On");journey.shot("v2-consent")
    }
    @Test fun phoneRestoreSendsCodeAndConfirmsRestoration() {
        val auth=object:AuthGateway {
            override suspend fun config()=PublicConfig(false,100)
            override suspend fun login(username:String,password:String)=throw AssertionError()
            override suspend fun enroll(challenge:String)=throw AssertionError()
            override suspend fun verify(challenge:String,code:String,enrollment:Boolean)=throw AssertionError()
            override suspend fun me()=throw AssertionError()
            override suspend fun logout() {}
            override suspend fun phoneStart(phone:String)=PhoneChallenge("c",300)
            override suspend fun restorePhone(phone:String,code:String,newPassword:String,challenge:PhoneChallenge)=AccountRestoreResult(true,"Account restored.")
        }
        val accounts=AccountStateHolder(auth,{false}).apply {open(AccountRoute.RESTORE_PHONE)}
        show {AccountFlowScreen(accounts,false)}
        journey.waitText("Restore a deleted account");journey.shot("v2-restore-phone")
        journey.replace(compose.onNodeWithTag("restorePhone"),"+919876543210");journey.clickTag("restoreSend")
        journey.waitText("Enter the code we sent and choose a new password.")
        journey.replace(compose.onNodeWithTag("restoreCode"),"123456")
        journey.replace(compose.onNodeWithTag("restorePassword"),"new-password-1")
        journey.replace(compose.onNodeWithTag("restoreConfirmation"),"new-password-1")
        journey.clickTag("restoreSubmit");journey.waitText("Account restored");journey.shot("v2-restore-done")
    }
}
