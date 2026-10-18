package org.spaceworks.app.ui

import org.spaceworks.app.platform.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.member.*
import org.spaceworks.app.guidance.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

fun memberDate(value:String,zone:String?=null):String = try {
    val date=OffsetDateTime.parse(value).atZoneSameInstant(zone?.let(ZoneId::of) ?: ZoneId.systemDefault())
    date.format(DateTimeFormatter.ofPattern("d MMM, h:mm a",Locale.getDefault()))
}catch(_:Exception){try {LocalDate.parse(value).format(DateTimeFormatter.ofPattern("d MMM yyyy",Locale.getDefault()))}catch(_:Exception){value}}
fun memberStatus(value:String,text:MobileText=MobileText.ENGLISH):String=when(value){"pending"->text(UiStrings.MOBILE_PENDING);"accepted"->text(UiStrings.MOBILE_ACCEPTED);"active"->text(UiStrings.MOBILE_ACTIVE);"completed"->text(UiStrings.MOBILE_COMPLETED);"cancelled"->text(UiStrings.MOBILE_CANCELLED);"canceled"->text(UiStrings.MOBILE_CANCELED);"rejected"->text(UiStrings.MOBILE_REJECTED);"returned"->text(UiStrings.MOBILE_RETURNED);"approved"->text(UiStrings.MOBILE_APPROVED);"registered"->text(UiStrings.MOBILE_REGISTERED);"waitlisted"->text(UiStrings.MOBILE_WAITLISTED);"pending_approval"->text(UiStrings.MOBILE_PENDING_APPROVAL);"confirmed"->text(UiStrings.MOBILE_CONFIRMED);"paid_online"->text(UiStrings.MOBILE_PAID_ONLINE);"paid_offline"->text(UiStrings.MOBILE_PAID_OFFLINE);"unpaid"->text(UiStrings.MOBILE_UNPAID);"processing"->text(UiStrings.MOBILE_PROCESSING);"captured"->text(UiStrings.MOBILE_CAPTURED);"failed"->text(UiStrings.MOBILE_FAILED);"instant"->text(UiStrings.MOBILE_INSTANT);"review"->text(UiStrings.MOBILE_REVIEW);"Available"->text(UiStrings.MOBILE_AVAILABLE);"Limited"->text(UiStrings.MOBILE_LIMITED);"Unavailable"->text(UiStrings.MOBILE_UNAVAILABLE);"Full"->text(UiStrings.MOBILE_FULL);else->value.replace('_',' ').replaceFirstChar(Char::uppercase)}
fun memberAvailability(item:InventoryItem,text:MobileText=MobileText.ENGLISH):String {
    val count=item.availableCount
    if(item.availabilityMode=="exact_count" && count!=null)when(item.availability){
        "Available"->return text(UiStrings.MOBILE_VALUE_AVAILABLE,count)
        "Limited"->return text(UiStrings.MOBILE_VALUE_LEFT_B69A52,count)
    }
    return if(item.availability=="Ask your makerspace about availability.")text(UiStrings.MOBILE_ASK_YOUR_MAKERSPACE_ABOUT_AVAILABILITY)else memberStatus(item.availability,text)
}

@Composable internal fun MemberSection(title:String,content:@Composable ColumnScope.()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics {heading()})
        content()
    }
}
@Composable internal fun MemberPanel(content:@Composable ColumnScope.()->Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)}
}
@Composable internal fun MemberEmpty(filtered:Boolean=false) {
    val copyText=rememberMobileText()

    Text(if(filtered)copyText(UiStrings.MOBILE_NO_RESULTS_TRY_ANOTHER_SEARCH) else copyText(UiStrings.MOBILE_NOTHING_HERE_YET),color=MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable internal fun MemberLoading() {
    val copyText=rememberMobileText()

    Column(Modifier.fillMaxWidth().semantics {contentDescription=copyText(UiStrings.MOBILE_LOADING_MAKERSPACE_INFORMATION);liveRegion=LiveRegionMode.Polite},verticalArrangement=Arrangement.spacedBy(12.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        repeat(3){Surface(Modifier.fillMaxWidth().height(56.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=MaterialTheme.shapes.medium){}}
    }
}
@Composable internal fun <T> MemberLoaded(load:MemberLoad<T>,retry:()->Unit,content:@Composable (T)->Unit) {
    val copyText=rememberMobileText()

    if(load.loading && load.value==null || load.value==null && load.error==null)MemberLoading()
    load.error?.let {BrandError(it);BrandTextButton(copyText(UiStrings.MOBILE_TRY_AGAIN),retry,!load.loading)}
    load.value?.let {content(it)}
    if(load.loading && load.value!=null)LinearProgressIndicator(Modifier.fillMaxWidth())
}
@Composable private fun MemberPageColumn(title:String,subtitle:String?=null,content:@Composable ColumnScope.()->Unit) {
    Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.TopCenter) {
        Column(Modifier.widthIn(max=960.dp).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
            Text(title,style=MaterialTheme.typography.headlineLarge,modifier=Modifier.semantics {heading()})
            subtitle?.let {Text(it,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            content();Spacer(Modifier.height(12.dp))
        }
    }
}
@Composable private fun <T> MemberLazyPage(title:String,load:MemberLoad<MemberPage<T>>,state:LazyListState,
    retry:()->Unit,key:(T)->String,filtered:Boolean=false,header:@Composable ()->Unit={},footer:@Composable ()->Unit={},row:@Composable (T)->Unit) {
    val copyText=rememberMobileText()

    Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max=960.dp).fillMaxSize().imePadding(),state=state,contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
            item {Text(title,style=MaterialTheme.typography.headlineLarge,modifier=Modifier.semantics {heading()})}
            item {header()}
            if(load.value==null && load.error==null)item {MemberLoading()}
            load.error?.let {message->item {Column {BrandError(message);BrandTextButton(copyText(UiStrings.MOBILE_TRY_AGAIN),retry,!load.loading)}}}
            load.value?.let {page->
                if(page.items.isEmpty())item {MemberEmpty(filtered)}
                items(page.items,key=key){row(it)}
                if(load.loading)item {LinearProgressIndicator(Modifier.fillMaxWidth())}
            }
            item {footer()}
        }
    }
}
@Composable fun MemberHome(holder:MemberStateHolder,name:String,onInventory:()->Unit,demo:Boolean=false,requestedExtra:Pair<Long,String>?=null,onPayments:(()->Unit)?=null,guideProgress:GuideProgress?=null,bookingsEnabled:Boolean=false,onProfile:(()->Unit)?=null,usage:(@Composable ()->Unit)?=null) {
    val copyText=rememberMobileText()

    val state by holder.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    var extra by rememberSaveable(state.scope){mutableStateOf<String?>(null)}
    LaunchedEffect(requestedExtra){extra=requestedExtra?.second}
    ProtectSecrets(!demo && extra=="card")
    LaunchedEffect(holder,state.scope) {kotlinx.coroutines.yield();if(holder.state.value.overview.value==null && !holder.state.value.overview.loading)holder.refreshOverview()}
    BackHandler(extra!=null){extra=null}
    if(extra!=null) {
        when(extra){"card"->MemberCardScreen(holder,{extra=null});"events"->MemberEventsScreen(holder,{extra=null});"bookings"->MemberBookingsScreen(holder,{extra=null});else->MemberSpacesScreen(holder,{extra=null})}
        return
    }
    MemberPageColumn(copyText(UiStrings.MOBILE_WELCOME_BACK_VALUE,name),makerspaceLabel(state.scope?.slug.orEmpty())) {
        val dismissed=guideProgress?.dismissed?.collectAsStateWithLifecycle()?.value?:true
        guideProgress?.let {HomeStartHere(it,onInventory,if(bookingsEnabled){{extra="bookings"}}else null,onProfile)}
        MemberPanel {
            Text(copyText(UsageStrings.USAGE_QUICK_ACTIONS),style=MaterialTheme.typography.titleLarge)
            Text(copyText(UiStrings.MOBILE_FIND_THE_RIGHT_TOOLS_PLAN_YOUR_VISIT_AND_KEEP_YOUR_MAKING_MOVING))
            if(dismissed)BrandButton(copyText(UiStrings.MOBILE_BROWSE_INVENTORY),onInventory)
            BrandTextButton(copyText(UiStrings.MOBILE_BOOK_A_SPACE),{extra="spaces"})
            BrandTextButton(copyText(UiStrings.MOBILE_EXPLORE_EVENTS),{extra="events"})
            BrandTextButton(copyText(UiStrings.MOBILE_MEMBERSHIP_CARD),{extra="card";scope.launch {holder.refreshCard()}})
        }
        MemberLoaded(state.overview,{scope.launch {holder.refreshOverview()}}) {overview->
            MemberSection(copyText(UiStrings.MOBILE_MEMBERSHIP)) {
                MemberPanel {
                    Text(if(overview.membershipActive)copyText(UiStrings.MOBILE_MEMBERSHIP_ACTIVE) else copyText(UiStrings.MOBILE_MEMBERSHIP_INACTIVE),style=MaterialTheme.typography.titleMedium)
                    overview.term?.let {Text(it.planName);Text(copyText(UiStrings.MOBILE_VALUE_VALUE_7BC5F0,memberDate(it.startsAt),memberDate(it.endsAt)))}
                    if(overview.waiverRequired)Text(copyText(UiStrings.MOBILE_COMPLETE_YOUR_MAKERSPACE_WAIVER_BEFORE_BORROWING))
                    if(overview.term?.blocksBorrowing==true)Text(copyText(UiStrings.MOBILE_BORROWING_IS_RESTRICTED_ASK_YOUR_MAKERSPACE_FOR_HELP))
                    overview.restrictionCode?.let {Text(copyText(UiStrings.MOBILE_BORROWING_STATUS_VALUE,memberStatus(it,copyText)))}
                }
            }
            MemberSection(copyText(UiStrings.MOBILE_DUES)) {MemberDues(overview.dues);onPayments?.let {BrandTextButton(copyText(org.spaceworks.app.payments.PaymentStrings.PAYMENT_PAYMENTS),it)}}
            MemberSection(copyText(UiStrings.MOBILE_ACTIVE_LOANS)) {if(overview.loans.isEmpty())MemberEmpty() else overview.loans.take(3).forEach {MemberLoanPanel(it)}}
            MemberSection(copyText(UiStrings.MOBILE_UPCOMING_BOOKINGS)) {if(overview.bookings.items.isEmpty())MemberEmpty() else overview.bookings.items.take(3).forEach {MemberBookingPanel(it)}}
            MemberSection(copyText(UiStrings.MOBILE_EVENTS)) {if(overview.registrations.isEmpty())MemberEmpty() else overview.registrations.take(3).forEach {MemberRegistrationPanel(it)}}
            MemberSection(copyText(UiStrings.MOBILE_NOTICES)) {if(overview.notices.isEmpty())MemberEmpty() else overview.notices.forEach {notice->MemberPanel {Text(notice.title,style=MaterialTheme.typography.titleMedium);Text(notice.body)}}}
        }
        usage?.invoke()
        BrandTextButton(copyText(UiStrings.MOBILE_REFRESH_HOME),{scope.launch {holder.refreshOverview()}},!state.overview.loading)
    }
}
@Composable fun MemberDues(dues:MemberDuesSummary?) {
    val copyText=rememberMobileText()

    val balances=dues?.outstandingByCurrency
    MemberPanel {when {balances==null->Text(copyText(UiStrings.MOBILE_DUES_ARE_UNAVAILABLE_TRY_AGAIN_LATER));balances.isEmpty()->Text(copyText(UiStrings.MOBILE_NO_DUES_OUTSTANDING));else->balances.forEach {(currency,amount)->Text(copyText(UiStrings.MOBILE_VALUE_VALUE_78C505,currency,amount),style=MaterialTheme.typography.titleLarge)}}}
}
@Composable private fun MemberLoanPanel(loan:MemberLoan) {
    val copyText=rememberMobileText()

    val actions=LocalMemberActions.current
    MemberPanel {
        Text(loan.label,style=MaterialTheme.typography.titleMedium)
        Text(if(loan.overdue)copyText(UiStrings.MOBILE_OVERDUE) else copyText(UiStrings.MOBILE_ON_LOAN),color=if(loan.overdue)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text(loan.dueAt?.let {copyText(UiStrings.MOBILE_DUE_VALUE,memberDate(it))} ?: copyText(UiStrings.MOBILE_DUE_DATE_NOT_PROVIDED))
        loan.renewalRefusal?.takeIf {it.isNotBlank()}?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
        actions?.let {BrandTextButton(copyText(UiStrings.MOBILE_RENEW_LOAN),{it.openRenew(loan)})}
    }
}
@Composable private fun MemberBookingPanel(booking:MemberBooking) {
    val copyText=rememberMobileText()

    val actions=LocalMemberActions.current
    MemberPanel {Text(booking.spaceName,style=MaterialTheme.typography.titleMedium);Text(memberStatus(booking.status,copyText));Text(copyText(UiStrings.MOBILE_VALUE_VALUE_7BC5F0,memberDate(booking.startsAt,booking.timezone),memberDate(booking.endsAt,booking.timezone)));Text(copyText(UiStrings.MOBILE_TIMES_IN_VALUE,booking.timezone),style=MaterialTheme.typography.bodySmall);actions?.let {BrandTextButton(copyText(UiStrings.MOBILE_MANAGE_BOOKING),{it.openBooking(booking)})}}
}
@Composable private fun MemberRegistrationPanel(event:MemberRegistration) {
    val copyText=rememberMobileText()

    val actions=LocalMemberActions.current
    MemberPanel {Text(event.title,style=MaterialTheme.typography.titleMedium);Text(memberStatus(event.status,copyText));Text(memberDate(event.startsAt));event.waitlistPosition?.let {Text(copyText(UiStrings.MOBILE_WAITLIST_POSITION_VALUE_B06F51,it))};actions?.let {BrandTextButton(copyText(UiStrings.MOBILE_VIEW_REGISTRATION),{it.openRegistration(event)})}}
}
@Composable fun MemberInventory(holder:MemberStateHolder) {
    val copyText=rememberMobileText()

    val state by holder.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    var query by rememberSaveable(state.scope){mutableStateOf(state.query)}
    var menu by remember {mutableStateOf(false)}
    val listState=rememberLazyListState()
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(holder,state.scope) {kotlinx.coroutines.yield();if(holder.state.value.inventory.value==null && !holder.state.value.inventory.loading)holder.refreshInventory()}
    LaunchedEffect(query){if(query!=state.query){kotlinx.coroutines.delay(300);holder.search(query)}}
    BackHandler(state.productId!=null){holder.closeProduct()}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val split=maxWidth>=840.dp && LocalDensity.current.fontScale<1.6f
        if(state.productId!=null && !split){MemberProductDetail(holder,{holder.closeProduct()});return@BoxWithConstraints}
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {MemberLazyPage(copyText(UiStrings.MOBILE_INVENTORY),
                if(query!=state.query)MemberLoad(error=state.inventory.error,loading=state.inventory.error==null) else state.inventory,listState,
                retry={scope.launch {holder.search(query)}},key={it.id},filtered=query.isNotBlank() || state.category!=null,header={
                Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(makerspaceLabel(state.scope?.slug.orEmpty()),color=MaterialTheme.colorScheme.onSurfaceVariant)
                BrandField(query,{query=it.take(200)},copyText(UiStrings.MOBILE_SEARCH_INVENTORY),Modifier.testTag("inventorySearch"),onDone={focus.clearFocus();keyboard?.hide();scope.launch {holder.search(query)}})
                Box {
                    BrandTextButton(state.categories.value?.firstOrNull {it.slug==state.category}?.name ?: copyText(UiStrings.MOBILE_ALL_CATEGORIES),{menu=true})
                    DropdownMenu(menu,{menu=false}) {
                        DropdownMenuItem(text={Text(copyText(UiStrings.MOBILE_ALL_CATEGORIES))},onClick={menu=false;scope.launch {holder.search(query,null)}})
                        state.categories.value.orEmpty().forEach {category->DropdownMenuItem(text={Text(category.name)},onClick={menu=false;scope.launch {holder.search(query,category.slug)}})}
                    }
                }
                state.categories.error?.let {BrandError(it);BrandTextButton(copyText(UiStrings.MOBILE_RETRY_CATEGORIES),{scope.launch {holder.refreshCategories()}})}
                }
                },footer={
                    if(state.inventory.value?.next!=null)BrandButton(if(state.inventory.loading)copyText(UiStrings.MOBILE_LOADING) else copyText(UiStrings.MOBILE_LOAD_MORE),{scope.launch {holder.moreInventory()}},!state.inventory.loading)
                }) {item->
                    // Capture during composition; detached cards must not observe live state through semantics.
                    val picked=state.productId==item.id
                    OutlinedCard(onClick={scope.launch {holder.selectProduct(item.id)}},modifier=Modifier.fillMaxWidth().semantics {selected=picked}) {
                        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            MemberImage(item.imageUrl,item.name,Modifier.fillMaxWidth().height(144.dp))
                            Text(item.name,style=MaterialTheme.typography.titleLarge)
                            item.categoryName?.let {Text(it,style=MaterialTheme.typography.labelLarge)}
                            Text(memberAvailability(item,copyText),color=MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(copyText(UiStrings.MOBILE_VIEW_DETAILS),style=MaterialTheme.typography.labelLarge)
                        }
                    }}
            }
            if(split && state.productId!=null) {VerticalDivider();Box(Modifier.weight(1f)){MemberProductDetail(holder,{holder.closeProduct()})}}
        }
    }
}
@Composable private fun MemberProductDetail(holder:MemberStateHolder,onBack:()->Unit) {
    val copyText=rememberMobileText()

    val actions=LocalMemberActions.current
    val basket=actions?.state?.collectAsStateWithLifecycle()?.value
    val state by holder.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    LaunchedEffect(holder,state.scope,state.productId) {kotlinx.coroutines.yield();val current=holder.state.value;if(current.product.value==null && !current.product.loading)current.productId?.let {holder.selectProduct(it)}}
    MemberPageColumn(copyText(UiStrings.MOBILE_ITEM_DETAILS),makerspaceLabel(state.scope?.slug.orEmpty())) {
        BrandTextButton(copyText(UiStrings.MOBILE_BACK_TO_INVENTORY),onBack)
        MemberLoaded(state.product,{state.productId?.let {scope.launch {holder.selectProduct(it)}}}) {item->
            MemberImage(item.imageUrl,item.name,Modifier.fillMaxWidth().height(200.dp))
            Text(item.name,style=MaterialTheme.typography.headlineMedium)
            item.categoryName?.let {Text(it,style=MaterialTheme.typography.labelLarge)}
            Text(memberAvailability(item,copyText),color=MaterialTheme.colorScheme.onPrimaryContainer)
            Text(item.description.ifBlank {copyText(UiStrings.MOBILE_NO_DESCRIPTION_PROVIDED)})
            MemberSection(copyText(UiStrings.MOBILE_BORROWING_TERMS)) {Text(item.borrowingTerms.ifBlank {copyText(UiStrings.MOBILE_ASK_YOUR_MAKERSPACE_ABOUT_BORROWING_THIS_ITEM)})}
            actions?.let {action->BrandButton(copyText(UiStrings.MOBILE_ADD_TO_REQUEST),{action.add(item)});BrandTextButton(copyText(UiStrings.MOBILE_REVIEW_REQUEST),action::openCart);Text(copyText(UiStrings.MOBILE_VALUE_ITEMS_IN_YOUR_REQUEST,basket?.cart?.sumOf {it.quantity}?:0),Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
        }
    }
}
@Composable fun MemberActivityScreen(holder:MemberStateHolder) {
    val copyText=rememberMobileText()

    val actions=LocalMemberActions.current
    val actionState=actions?.state?.collectAsStateWithLifecycle()?.value
    val state by holder.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    LaunchedEffect(holder,state.scope) {kotlinx.coroutines.yield();if(holder.state.value.overview.value==null && !holder.state.value.overview.loading)holder.refreshOverview()}
    MemberPageColumn(copyText(UiStrings.MOBILE_ACTIVITY),makerspaceLabel(state.scope?.slug.orEmpty())) {
        MemberLoaded(state.overview,{scope.launch {holder.refreshOverview()}}) {overview->
            MemberSection(copyText(UiStrings.MOBILE_REQUESTS)) {if(actionState?.recentRequest!=null)BrandTextButton(copyText(UiStrings.MOBILE_TRACK_RECENT_REQUEST),{actions?.openRecentRequest()});if(overview.requests.isEmpty() && overview.serviceRequests.isEmpty())MemberEmpty()
                overview.requests.forEach {request->MemberPanel {Text(copyText(UiStrings.MOBILE_VALUE_ITEMS,request.itemCount),style=MaterialTheme.typography.titleMedium);Text(memberStatus(request.status,copyText));Text(memberDate(request.createdAt))}}
                overview.serviceRequests.forEach {request->MemberPanel {Text(request.title,style=MaterialTheme.typography.titleMedium);Text(memberStatus(request.status,copyText));request.queuePosition?.let {Text(copyText(UiStrings.MOBILE_QUEUE_POSITION_VALUE,it))};request.estimatedMinutes?.let {Text(copyText(UiStrings.MOBILE_ESTIMATED_TIME_VALUE_MINUTES,it))}}}
            }
            MemberSection(copyText(UiStrings.MOBILE_LOANS)) {if(overview.loans.isEmpty())MemberEmpty() else overview.loans.forEach {MemberLoanPanel(it)}}
            MemberSection(copyText(UiStrings.MOBILE_BOOKINGS)) {if(overview.bookings.items.isEmpty())MemberEmpty() else overview.bookings.items.forEach {MemberBookingPanel(it)}
                if(overview.bookings.next!=null)BrandButton(if(state.bookingMoreBusy)copyText(UiStrings.MOBILE_LOADING) else copyText(UiStrings.MOBILE_LOAD_MORE_BOOKINGS),{scope.launch {holder.moreBookings()}},!state.bookingMoreBusy)}
            MemberSection(copyText(UiStrings.MOBILE_EVENTS)) {if(overview.registrations.isEmpty())MemberEmpty() else overview.registrations.forEach {MemberRegistrationPanel(it)}}
            MemberSection(copyText(UiStrings.MOBILE_LOAN_HISTORY)) {if(overview.loanHistory.isEmpty())MemberEmpty() else overview.loanHistory.forEach {loan->MemberPanel {Text(loan.label,style=MaterialTheme.typography.titleMedium);Text(if(loan.returnedLate)copyText(UiStrings.MOBILE_RETURNED_LATE) else copyText(UiStrings.MOBILE_RETURNED));loan.returnedAt?.let {Text(memberDate(it))}}}}
        }
    }
}
@Composable fun MemberInboxScreen(holder:MemberStateHolder,notifications:@Composable ()->Unit={},onOpenUpdate:(String)->Unit={},navigationSequence:Long=0) {
    val copyText=rememberMobileText()

    val state by holder.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    LaunchedEffect(holder,state.scope) {kotlinx.coroutines.yield();if(holder.state.value.inbox.value==null && !holder.state.value.inbox.loading)holder.refreshInbox()}
    var selected by remember(state.scope){mutableStateOf<InboxItem?>(null)}
    val listState=rememberLazyListState()
    LaunchedEffect(navigationSequence){selected=null;if(navigationSequence>0)listState.scrollToItem(0)}
    BackHandler(selected!=null){selected=null}
    if(selected!=null)MemberPageColumn(copyText(UiStrings.MOBILE_MESSAGE)) {
            val message=requireNotNull(selected)
            BrandTextButton(copyText(UiStrings.MOBILE_BACK_TO_INBOX),{selected=null})
            Text(message.title,style=MaterialTheme.typography.headlineMedium);Text(memberDate(message.createdAt));Text(message.summary)
            Text(if(message.readAt!=null)copyText(UiStrings.MOBILE_READ) else copyText(UiStrings.MOBILE_UNREAD),style=MaterialTheme.typography.labelLarge)
            message.link?.takeIf {it.isNotBlank()}?.let {link->BrandButton(copyText(UiStrings.MOBILE_OPEN_UPDATE),{onOpenUpdate(link)})}
            if(message.readAt==null)BrandButton(copyText(UiStrings.MOBILE_MARK_AS_READ),{scope.launch {if(holder.markRead(message.id))selected=null}},!state.inboxBusy)
            BrandError(state.inbox.error)
    } else MemberLazyPage(copyText(UiStrings.MOBILE_INBOX),state.inbox,listState,retry={scope.launch {holder.refreshInbox()}},key={it.id},header={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(makerspaceLabel(state.scope?.slug.orEmpty()),color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                FilterChip(!state.unread,{scope.launch {holder.filterInbox(false)}},label={Text(copyText(UiStrings.MOBILE_ALL))})
                FilterChip(state.unread,{scope.launch {holder.filterInbox(true)}},label={Text(copyText(UiStrings.MOBILE_UNREAD))})
            }
            BrandTextButton(copyText(UiStrings.MOBILE_MARK_ALL_AS_READ),{scope.launch {holder.markAllRead()}},!state.inboxBusy && state.inbox.value?.items?.any {it.readAt==null}==true)
        }
            notifications()
    },footer={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(state.inbox.value?.next!=null)BrandButton(copyText(UiStrings.MOBILE_LOAD_MORE),{scope.launch {holder.moreInbox()}},!state.inbox.loading)
            state.notice?.let {Text(it,Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
        }
    }) {message->OutlinedCard(onClick={selected=message},modifier=Modifier.fillMaxWidth()) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(if(message.readAt==null)copyText(UiStrings.MOBILE_UNREAD) else copyText(UiStrings.MOBILE_READ),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(message.title,style=MaterialTheme.typography.titleLarge);Text(message.summary);Text(memberDate(message.createdAt),style=MaterialTheme.typography.bodySmall)
    }}}
}
@Composable fun MemberCardScreen(holder:MemberStateHolder,onBack:()->Unit) {
    val copyText=rememberMobileText()

    val state by holder.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    LaunchedEffect(holder,state.scope) {kotlinx.coroutines.yield();if(holder.state.value.card.value==null && !holder.state.value.card.loading)holder.refreshCard()}
    MemberPageColumn(copyText(UiStrings.MOBILE_MEMBERSHIP_CARD),makerspaceLabel(state.scope?.slug.orEmpty())) {
        BrandTextButton(copyText(UiStrings.MOBILE_BACK_TO_HOME),onBack)
        MemberLoaded(state.card,{scope.launch {holder.refreshCard()}}) {card->
            when {card.notIssued->Text(copyText(UiStrings.MOBILE_YOUR_MEMBERSHIP_CARD_HASN_T_BEEN_ISSUED_YET));!card.isActive || !card.qrActive->Text(copyText(UiStrings.MOBILE_THIS_CARD_IS_NOT_ACTIVE_ASK_YOUR_MAKERSPACE_FOR_HELP));card.qrImage.isNullOrBlank()->Text(copyText(UiStrings.MOBILE_YOUR_MEMBERSHIP_QR_CODE_IS_UNAVAILABLE_TRY_AGAIN_LATER));else->{
                Text(card.printedName,style=MaterialTheme.typography.headlineMedium)
                card.cardNumber?.let {Text(copyText(UiStrings.MOBILE_MEMBER_CARD_VALUE,it))}
                MemberImage(card.qrImage,copyText(UiStrings.MOBILE_MEMBERSHIP_QR_CODE),Modifier.fillMaxWidth().height(280.dp),qr=true)
                Text(copyText(UiStrings.MOBILE_ACTIVE_CARD),color=MaterialTheme.colorScheme.onPrimaryContainer)
            }}
        }
        BrandTextButton(copyText(UiStrings.MOBILE_REFRESH_CARD),{scope.launch {holder.refreshCard()}},!state.card.loading)
    }
}
@Composable private fun MemberEventsScreen(holder:MemberStateHolder,onBack:()->Unit) {
    val copyText=rememberMobileText()

    val actions=LocalMemberActions.current
    val actionScope=LocalMemberActionScope.current
    val state by holder.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    LaunchedEffect(holder,state.scope) {kotlinx.coroutines.yield();if(holder.state.value.events.value==null && !holder.state.value.events.loading)holder.refreshEvents()}
    var selected by remember(state.scope){mutableStateOf<MemberEvent?>(null)}
    BackHandler {if(selected!=null)selected=null else onBack()}
    MemberPageColumn(if(selected==null)copyText(UiStrings.MOBILE_EXPLORE_EVENTS) else copyText(UiStrings.MOBILE_EVENT_DETAILS),makerspaceLabel(state.scope?.slug.orEmpty())) {
        BrandTextButton(if(selected==null)copyText(UiStrings.MOBILE_BACK_TO_HOME) else copyText(UiStrings.MOBILE_BACK_TO_EVENTS),{if(selected==null)onBack() else selected=null})
        if(selected!=null){val event=requireNotNull(selected);MemberImage(event.imageUrl,event.title,Modifier.fillMaxWidth().height(180.dp));Text(event.title,style=MaterialTheme.typography.headlineMedium);Text(event.description);Text(memberDate(event.startsAt));Text(event.location);Text(memberStatus(event.availability,copyText));Text(copyText(UiStrings.MOBILE_VALUE_VALUE_78C505,event.currency,event.paymentAmount));actions?.let {action->BrandButton(copyText(UiStrings.MOBILE_REGISTER_FOR_THIS_EVENT),{(actionScope?:scope).launch {action.openEvent(event.id)}},event.registrationOpen)}}
        else MemberLoaded(state.events,{scope.launch {holder.refreshEvents()}}) {events->if(events.isEmpty())MemberEmpty() else events.forEach {event->OutlinedCard(onClick={selected=event},modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(event.title,style=MaterialTheme.typography.titleLarge);Text(memberDate(event.startsAt));Text(memberStatus(event.availability,copyText))}}}}
    }
}
@Composable private fun MemberSpacesScreen(holder:MemberStateHolder,onBack:()->Unit) {
    val copyText=rememberMobileText()

    val actions=LocalMemberActions.current
    val actionScope=LocalMemberActionScope.current
    val state by holder.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    LaunchedEffect(holder,state.scope) {kotlinx.coroutines.yield();if(holder.state.value.spaces.value==null && !holder.state.value.spaces.loading)holder.refreshSpaces()}
    var selected by remember(state.scope){mutableStateOf<MemberSpace?>(null)}
    BackHandler {if(selected!=null)selected=null else onBack()}
    MemberPageColumn(if(selected==null)copyText(UiStrings.MOBILE_FIND_A_SPACE) else copyText(UiStrings.MOBILE_SPACE_DETAILS),makerspaceLabel(state.scope?.slug.orEmpty())) {
        BrandTextButton(if(selected==null)copyText(UiStrings.MOBILE_BACK_TO_HOME) else copyText(UiStrings.MOBILE_BACK_TO_SPACES),{if(selected==null)onBack() else selected=null})
        if(selected!=null){val space=requireNotNull(selected);MemberImage(space.imageUrl,space.name,Modifier.fillMaxWidth().height(180.dp));Text(space.name,style=MaterialTheme.typography.headlineMedium);Text(space.description);Text(space.location);Text(copyText(UiStrings.MOBILE_CAPACITY_VALUE,space.capacity));Text(copyText(UiStrings.MOBILE_APPROVAL_VALUE,memberStatus(space.approvalMode,copyText)));if(!space.showAvailability)Text(copyText(UiStrings.MOBILE_ASK_YOUR_MAKERSPACE_ABOUT_AVAILABILITY))}
        else MemberLoaded(state.spaces,{scope.launch {holder.refreshSpaces()}}) {spaces->if(spaces.isEmpty())MemberEmpty() else spaces.forEach {space->OutlinedCard(onClick={if(actions!=null)(actionScope?:scope).launch {actions.openSpace(space.id)}else selected=space},modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(space.name,style=MaterialTheme.typography.titleLarge);Text(space.location);Text(copyText(UiStrings.MOBILE_CAPACITY_VALUE,space.capacity))}}}}
    }
}
@Composable fun MemberProfileSummary(holder:MemberStateHolder) {
    val copyText=rememberMobileText()

    val state by holder.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    var scenarios by remember {mutableStateOf(false)}
    state.overview.value?.term?.let {term->MemberPanel {
        Text(copyText(UiStrings.MOBILE_YOUR_MEMBERSHIP),style=MaterialTheme.typography.titleLarge)
        Text(term.planName);Text(copyText(UiStrings.MOBILE_VALUE_VALUE_7BC5F0,memberDate(term.startsAt),memberDate(term.endsAt)))
        Text(if(term.current)copyText(UiStrings.MOBILE_CURRENT_TERM) else copyText(UiStrings.MOBILE_TERM_ENDED))
    }}
    if(holder.hasPreview) {
        Text(copyText(UiStrings.MOBILE_DEMO_SCENARIOS),style=MaterialTheme.typography.titleLarge)
        Text(copyText(UiStrings.MOBILE_PREVIEW_LOCAL_STATES_THESE_CONTROLS_NEVER_CHANGE_A_REAL_ACCOUNT),style=MaterialTheme.typography.bodySmall)
        Box {
            BrandTextButton(copyText(UiStrings.MOBILE_CHOOSE_A_DEMO_STATE),{scenarios=true})
            DropdownMenu(scenarios,{scenarios=false}) {MemberPreviewScenario.entries.forEach {scenario->
                DropdownMenuItem(text={Text(scenario.label)},onClick={scenarios=false;scope.launch {holder.preview(scenario)}})
            }}
        }
    }
}


/** Typed member booking destination; no booking identity is inferred from display names. */
@Composable fun MemberBookingsScreen(holder:MemberStateHolder,onBack:()->Unit) {
    val copyText=rememberMobileText()
    val state by holder.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    LaunchedEffect(holder,state.scope){kotlinx.coroutines.yield();if(!holder.state.value.overview.loading)holder.refreshOverview()}
    MemberPageColumn(copyText(UiStrings.MOBILE_BOOKINGS),makerspaceLabel(state.scope?.slug.orEmpty())) {
        BrandTextButton(copyText(UiStrings.MOBILE_BACK),onBack)
        MemberLoaded(state.overview,{scope.launch {holder.refreshOverview()}}) {overview->
            if(overview.bookings.items.isEmpty())MemberEmpty()
            overview.bookings.items.forEach {MemberBookingPanel(it)}
            if(overview.bookings.next!=null)BrandButton(
                if(state.bookingMoreBusy)copyText(UiStrings.MOBILE_LOADING)else copyText(UiStrings.MOBILE_LOAD_MORE_BOOKINGS),
                {scope.launch {holder.moreBookings()}},!state.bookingMoreBusy)
        }
        BrandTextButton(copyText(UsageStrings.USAGE_REFRESH_BOOKINGS),{scope.launch {holder.refreshOverview()}},!state.overview.loading)
    }
}
