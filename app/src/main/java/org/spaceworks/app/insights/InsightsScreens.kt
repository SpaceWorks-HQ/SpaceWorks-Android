package org.spaceworks.app.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.platform.rememberMobileText
import org.spaceworks.app.ui.*

/** Brand StatCard: a neutral panel, Gilroy 600 tabular number in accent ink and a full-opacity label. */
@Composable private fun StatCard(label:String,value:String,modifier:Modifier=Modifier) {
    OutlinedCard(modifier.semantics(mergeDescendants=true) {},colors=CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(value,style=MaterialTheme.typography.headlineSmall.copy(fontWeight=FontWeight.SemiBold,fontFeatureSettings="tnum"),
                color=MaterialTheme.colorScheme.onPrimaryContainer)
            Text(label,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurface)
        }
    }
}
/** Equal-width stat grid: the column count follows the available width and the user's font scale. */
@Composable private fun StatRow(stats:List<Pair<String,String>>) {
    if(stats.isEmpty())return
    val scale=androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns=(maxWidth/(104.dp*scale)).toInt().coerceIn(1,minOf(3,stats.size))
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            stats.chunked(columns).forEach {row->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    row.forEach {(label,value)->StatCard(label,value,Modifier.weight(1f).fillMaxHeight())}
                    repeat(columns-row.size) {Spacer(Modifier.weight(1f))}
                }
            }
        }
    }
}
/** Status word on a soft pill: the word carries the meaning, colour only reinforces it. */
@Composable private fun StatusPill(word:String,on:Boolean) {
    Surface(shape=RoundedCornerShape(50),color=if(on)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(word,Modifier.heightIn(min=32.dp).padding(horizontal=14.dp,vertical=6.dp),style=MaterialTheme.typography.labelLarge,
            color=if(on)MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
/** Labelled bars with each value written out; the bar itself is decorative. No totals across units. */
@Composable private fun SeriesBars(title:String,series:UsageSeries) {
    val copyText=rememberMobileText()
    Text(title,style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics {heading()})
    if(series.points.isEmpty()){Text(copyText(InsightsStrings.INSIGHTS_NO_ACTIVITY_YET),color=MaterialTheme.colorScheme.onSurfaceVariant);return}
    val max=series.points.maxOf {it.amount}.takeIf {it>0}?:1.0
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        series.points.forEach {point->
            val description=copyText(InsightsStrings.INSIGHTS_POINT_VALUE,point.label,point.value,series.unit).trim()
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants=true) {contentDescription=description},verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(point.label,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(1f))
                    Text(if(series.unit.isBlank())point.value else "${point.value} ${series.unit}",style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.SemiBold,fontFeatureSettings="tnum"))
                }
                Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.primaryContainer,RoundedCornerShape(4.dp))) {
                    Box(Modifier.fillMaxWidth((point.amount.coerceAtLeast(0.0)/max).toFloat().coerceIn(0f,1f)).fillMaxHeight()
                        // Accent ink reads on the soft accent track in both themes (dark accent alone is under 3:1).
                        .background(MaterialTheme.colorScheme.onPrimaryContainer,RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}
@Composable private fun Money(values:Map<String,String>):String {
    val copyText=rememberMobileText()
    // Each currency stays separate; amounts are never added across currencies.
    return values.entries.joinToString("\n") {(currency,amount)->copyText(InsightsStrings.INSIGHTS_MONEY_VALUE,currency.uppercase(),amount)}
}

/** Member Home: the caller's own usage. Missing sections are omitted, never shown as zero. */
@Composable fun MemberUsagePanel(holder:InsightsStateHolder) {
    val copyText=rememberMobileText()
    val s by holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    LaunchedEffect(holder,s.scope) {if(s.scope!=null && s.usage.value==null && !s.usage.loading && s.usage.error==null)holder.refreshUsage()}
    MemberSection(copyText(InsightsStrings.INSIGHTS_YOUR_ACTIVITY)) {
        MemberLoaded(s.usage,{scope.launch {holder.refreshUsage()}}) {usage->
            Column(Modifier.testTag("member-usage"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(!usage.available)Text(copyText(InsightsStrings.INSIGHTS_USAGE_UNAVAILABLE),color=MaterialTheme.colorScheme.onSurfaceVariant)
                usage.loans?.let {loans->MemberPanel {
                    Text(copyText(InsightsStrings.INSIGHTS_LOANS),style=MaterialTheme.typography.titleLarge)
                    StatRow(listOf(copyText(InsightsStrings.INSIGHTS_BORROWED) to loans.count.toString(),copyText(InsightsStrings.INSIGHTS_RETURNED) to loans.returned.toString(),
                        copyText(InsightsStrings.INSIGHTS_OUT_NOW) to loans.currentlyOut.toString()))
                    SeriesBars(copyText(InsightsStrings.INSIGHTS_BY_MONTH),loans.monthly)
                }}
                usage.machines?.let {machines->MemberPanel {
                    Text(copyText(InsightsStrings.INSIGHTS_MACHINES),style=MaterialTheme.typography.titleLarge)
                    StatRow(listOf(copyText(InsightsStrings.INSIGHTS_MACHINE_HOURS) to machines.hours))
                    SeriesBars(copyText(InsightsStrings.INSIGHTS_BY_MONTH),machines.monthly)
                    SeriesBars(copyText(InsightsStrings.INSIGHTS_TOP_MACHINES),machines.top)
                }}
                if(usage.bookings!=null || usage.eventsAttended!=null)MemberPanel {
                    StatRow(listOfNotNull(usage.bookings?.let {copyText(InsightsStrings.INSIGHTS_BOOKINGS) to it.count.toString()},
                        usage.eventsAttended?.let {copyText(InsightsStrings.INSIGHTS_EVENTS_ATTENDED) to it.toString()}))
                    usage.bookings?.let {SeriesBars(copyText(InsightsStrings.INSIGHTS_BY_MONTH),it.monthly)}
                }
                usage.payments?.let {payments->MemberPanel {
                    Text(copyText(InsightsStrings.INSIGHTS_PAYMENTS),style=MaterialTheme.typography.titleLarge)
                    StatRow(listOfNotNull(payments.paidByCurrency.takeIf {it.isNotEmpty()}?.let {copyText(InsightsStrings.INSIGHTS_PAID) to Money(it)},
                        payments.outstandingByCurrency.takeIf {it.isNotEmpty()}?.let {copyText(InsightsStrings.INSIGHTS_OUTSTANDING) to Money(it)}))
                    if(payments.paidByCurrency.isEmpty() && payments.outstandingByCurrency.isEmpty())
                        Text(copyText(InsightsStrings.INSIGHTS_NO_ACTIVITY_YET),color=MaterialTheme.colorScheme.onSurfaceVariant)
                }}
            }
        }
    }
}

private fun counterLabel(key:String)=when(key) {
    "pending_requests"->InsightsStrings.INSIGHTS_PENDING_REQUESTS;"awaiting_issue"->InsightsStrings.INSIGHTS_AWAITING_ISSUE
    "overdue_loans"->InsightsStrings.INSIGHTS_OVERDUE_LOANS;"pending_bookings"->InsightsStrings.INSIGHTS_PENDING_BOOKINGS
    "upcoming_bookings"->InsightsStrings.INSIGHTS_UPCOMING_BOOKINGS;"upcoming_events"->InsightsStrings.INSIGHTS_UPCOMING_EVENTS
    "pending_event_registrations"->InsightsStrings.INSIGHTS_PENDING_EVENT_REGISTRATIONS;"open_problem_reports"->InsightsStrings.INSIGHTS_OPEN_PROBLEM_REPORTS
    "low_stock"->InsightsStrings.INSIGHTS_LOW_STOCK;"pending_prints"->InsightsStrings.INSIGHTS_PENDING_PRINTS;"active_prints"->InsightsStrings.INSIGHTS_ACTIVE_PRINTS
    "prints_awaiting_collection"->InsightsStrings.INSIGHTS_PRINTS_AWAITING_COLLECTION;"maintenance_overdue"->InsightsStrings.INSIGHTS_MAINTENANCE_OVERDUE
    "warranty_expiring"->InsightsStrings.INSIGHTS_WARRANTY_EXPIRING;"stocktakes_awaiting_approval"->InsightsStrings.INSIGHTS_STOCKTAKES_AWAITING_APPROVAL
    "pending_payments"->InsightsStrings.INSIGHTS_PENDING_PAYMENTS;"failed_emails"->InsightsStrings.INSIGHTS_FAILED_EMAILS
    "ready_to_issue"->InsightsStrings.INSIGHTS_READY_TO_ISSUE;"due_returns"->InsightsStrings.INSIGHTS_DUE_RETURNS
    else->InsightsStrings.INSIGHTS_PICKUPS
}

/** Staff landing card. Handover-only staff see their queue tiles and no analytics. */
@Composable fun StaffDashboardPanel(holder:InsightsStateHolder) {
    val copyText=rememberMobileText()
    val s by holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    LaunchedEffect(holder,s.scope) {if(s.scope!=null && s.dashboard.value==null && !s.dashboard.loading && s.dashboard.error==null)holder.refreshDashboard()}
    MemberSection(copyText(InsightsStrings.INSIGHTS_TODAY_AT_A_GLANCE)) {
        MemberLoaded(s.dashboard,{scope.launch {holder.refreshDashboard()}}) {dashboard->
            Column(Modifier.testTag("staff-dashboard"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(dashboard.queue.isNotEmpty())MemberPanel {
                    Text(copyText(InsightsStrings.INSIGHTS_HANDOVER_QUEUE),style=MaterialTheme.typography.titleMedium)
                    StatRow(dashboard.queue.map {copyText(counterLabel(it.key)) to it.value.toString()})
                }
                if(dashboard.counters.isNotEmpty())StatRow(dashboard.counters.map {copyText(counterLabel(it.key)) to it.value.toString()})
                if(dashboard.scope!=StaffScope.HANDOVER && dashboard.outstandingByCurrency.isNotEmpty())
                    StatRow(listOf(copyText(InsightsStrings.INSIGHTS_OUTSTANDING_PAYMENTS) to Money(dashboard.outstandingByCurrency)))
                if(dashboard.scope!=StaffScope.HANDOVER && dashboard.widgets.isNotEmpty())MemberPanel {
                    Text(copyText(InsightsStrings.INSIGHTS_REPORTS),style=MaterialTheme.typography.titleMedium)
                    dashboard.widgets.forEach {widget->
                        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).semantics(mergeDescendants=true) {},verticalAlignment=Alignment.CenterVertically) {
                            Text(widget.title,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
                            widget.headline?.let {Text(it,style=MaterialTheme.typography.titleMedium.copy(fontFeatureSettings="tnum"),color=MaterialTheme.colorScheme.onPrimaryContainer)}
                        }
                    }
                }
                if(dashboard.queue.isEmpty() && dashboard.counters.isEmpty() && (dashboard.scope==StaffScope.HANDOVER || dashboard.widgets.isEmpty()))
                    Text(copyText(InsightsStrings.INSIGHTS_NOTHING_NEEDS_ATTENTION),color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        BrandTextButton(copyText(InsightsStrings.INSIGHTS_REFRESH_DASHBOARD),{scope.launch {holder.refreshDashboard()}},!s.dashboard.loading)
    }
}

/** Per-makerspace SMS/WhatsApp consent. Each switch reflects only the server's returned state. */
@Composable fun MessagingConsentScreen(holder:InsightsStateHolder,onBack:()->Unit,onAccount:(()->Unit)?) {
    val copyText=rememberMobileText()
    val s by holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    androidx.activity.compose.BackHandler(s.consentBusy==null,onBack)
    LaunchedEffect(holder,s.scope) {if(s.scope!=null)holder.refreshMessaging()}
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
        Column(Modifier.widthIn(max=720.dp).fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            BrandTextButton(copyText(UiStrings.MOBILE_BACK),onBack,s.consentBusy==null)
            Text(copyText(InsightsStrings.INSIGHTS_MESSAGES),style=MaterialTheme.typography.headlineLarge,modifier=Modifier.semantics {heading()})
            Text(copyText(InsightsStrings.INSIGHTS_MESSAGES_HELP),color=MaterialTheme.colorScheme.onSurfaceVariant)
            BrandError(s.consentError)
            MemberLoaded(s.messaging,{scope.launch {holder.refreshMessaging()}}) {consent->
                listOf("sms" to consent.sms,"whatsapp" to consent.whatsapp).forEach {(key,channel)->
                    val title=copyText(if(key=="sms")InsightsStrings.INSIGHTS_SMS else InsightsStrings.INSIGHTS_WHATSAPP)
                    val busy=s.consentBusy==key
                    val enabled=s.consentBusy==null && (channel.consented || channel.canOptIn)
                    MemberPanel {
                        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(channel.consented,enabled,Role.Switch) {scope.launch {holder.setConsent(key,it)}}
                            .testTag("consent-$key"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text(title,style=MaterialTheme.typography.titleMedium)
                                StatusPill(copyText(if(channel.consented)InsightsStrings.INSIGHTS_ON else InsightsStrings.INSIGHTS_OFF),channel.consented)
                            }
                            if(busy)CircularProgressIndicator(Modifier.size(24.dp)) else Switch(channel.consented,null,enabled=enabled)
                        }
                        when {
                            !channel.available->Text(copyText(InsightsStrings.INSIGHTS_CHANNEL_UNAVAILABLE),color=MaterialTheme.colorScheme.onSurfaceVariant)
                            channel.suppressed->Text(copyText(InsightsStrings.INSIGHTS_SUPPRESSED))
                            !channel.phoneVerified->{Text(copyText(InsightsStrings.INSIGHTS_VERIFY_PHONE_FIRST));onAccount?.let {BrandTextButton(copyText(InsightsStrings.INSIGHTS_OPEN_ACCOUNT),it)}}
                            channel.phoneMasked.isNotBlank()->Text(copyText(InsightsStrings.INSIGHTS_SENDING_TO,channel.phoneMasked),color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
