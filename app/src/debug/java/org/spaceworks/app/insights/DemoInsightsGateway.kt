package org.spaceworks.app.insights

import kotlinx.coroutines.delay
import org.spaceworks.app.member.MemberScope

/** Debug-only sample insights. No API, SMS provider or consent ledger is ever contacted. */
class DemoInsightsGateway(private val staff:()->Boolean={false}):InsightsGateway {
    private var sms=ConsentChannel(true,false,true,"•••• ••• 3210",false)
    private var whatsapp=ConsentChannel(true,false,false,"",false)
    private fun months(vararg values:Int)=UsageSeries(listOf("Jul","Aug","Sep","Oct").zip(values.toList()).map {(m,v)->UsagePoint("$m 2026",v.toString(),v.toDouble())},"","Sample monthly activity")
    override suspend fun usage(scope:MemberScope):UsageSummary {
        delay(150)
        return UsageSummary(true,LoanUsage(12,10,2,months(2,4,3,3)),
            MachineUsage("7.5",UsageSeries(listOf(UsagePoint("Sep 2026","3.0",3.0),UsagePoint("Oct 2026","4.5",4.5)),"h","Sample machine hours"),
                UsageSeries(listOf(UsagePoint("Laser cutter","4.5",4.5),UsagePoint("3D printer","3.0",3.0)),"h","Sample top machines")),
            CountUsage(5,months(1,1,2,1)),3,PaymentUsage(mapOf("inr" to "1250.00"),mapOf("inr" to "250.00")))
    }
    override suspend fun dashboard(scope:MemberScope):StaffDashboard {
        delay(150)
        return StaffDashboard(StaffScope.FULL,listOf(StaffCounter("pending_requests",3),StaffCounter("awaiting_issue",2),StaffCounter("overdue_loans",1)),
            emptyList(),listOf(StaffWidget("loans_trend","Loans this month","count","18")),mapOf("inr" to "250.00"))
    }
    override suspend fun messaging(scope:MemberScope):MessagingConsent {delay(100);return MessagingConsent(sms,whatsapp)}
    override suspend fun consent(scope:MemberScope,channel:String,optIn:Boolean):MessagingConsent {
        delay(200)
        if(channel=="sms")sms=sms.copy(consented=optIn) else whatsapp=whatsapp.copy(consented=optIn && whatsapp.phoneVerified)
        return MessagingConsent(sms,whatsapp)
    }
}
