package org.spaceworks.app.ui

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
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

fun memberDate(value:String,zone:String?=null):String = try {
    val date=OffsetDateTime.parse(value).atZoneSameInstant(zone?.let(ZoneId::of) ?: ZoneId.systemDefault())
    date.format(DateTimeFormatter.ofPattern("d MMM, h:mm a",Locale.getDefault()))
}catch(_:Exception){try {LocalDate.parse(value).format(DateTimeFormatter.ofPattern("d MMM yyyy",Locale.getDefault()))}catch(_:Exception){value}}
fun memberStatus(value:String)=value.replace('_',' ').replaceFirstChar(Char::uppercase)
fun memberAvailability(item:InventoryItem):String=when {
    item.availabilityMode!="exact_count" || item.availableCount==null->item.availability
    item.availability=="Available"->"${item.availableCount} available"
    item.availability=="Limited"->"${item.availableCount} left"
    else->item.availability
}

@Composable private fun MemberSection(title:String,content:@Composable ColumnScope.()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics {heading()})
        content()
    }
}
@Composable private fun MemberPanel(content:@Composable ColumnScope.()->Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)}
}
@Composable private fun MemberEmpty(filtered:Boolean=false) {
    Text(if(filtered)"No results. Try another search." else "Nothing here yet.",color=MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable private fun MemberLoading() {
    Column(Modifier.fillMaxWidth().semantics {contentDescription="Loading makerspace information";liveRegion=LiveRegionMode.Polite},verticalArrangement=Arrangement.spacedBy(12.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        repeat(3){Surface(Modifier.fillMaxWidth().height(56.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=MaterialTheme.shapes.medium){}}
    }
}
@Composable private fun <T> MemberLoaded(load:MemberLoad<T>,retry:()->Unit,content:@Composable (T)->Unit) {
    if(load.loading && load.value==null || load.value==null && load.error==null)MemberLoading()
    load.error?.let {BrandError(it);BrandTextButton("Try again",retry,!load.loading)}
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
    Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max=960.dp).fillMaxSize().imePadding(),state=state,contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
            item {Text(title,style=MaterialTheme.typography.headlineLarge,modifier=Modifier.semantics {heading()})}
            item {header()}
            if(load.value==null && load.error==null)item {MemberLoading()}
            load.error?.let {message->item {Column {BrandError(message);BrandTextButton("Try again",retry,!load.loading)}}}
            load.value?.let {page->
                if(page.items.isEmpty())item {MemberEmpty(filtered)}
                items(page.items,key=key){row(it)}
                if(load.loading)item {LinearProgressIndicator(Modifier.fillMaxWidth())}
            }
            item {footer()}
        }
    }
}
@Composable fun MemberHome(holder:MemberStateHolder,name:String,onInventory:()->Unit,demo:Boolean=false) {
    val state by holder.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    var extra by rememberSaveable(state.scope){mutableStateOf<String?>(null)}
    ProtectSecrets(!demo && extra=="card")
    LaunchedEffect(holder,state.scope) {kotlinx.coroutines.yield();if(holder.state.value.overview.value==null && !holder.state.value.overview.loading)holder.refreshOverview()}
    BackHandler(extra!=null){extra=null}
    if(extra!=null) {
        when(extra){"card"->MemberCardScreen(holder,{extra=null});"events"->MemberEventsScreen(holder,{extra=null});else->MemberSpacesScreen(holder,{extra=null})}
        return
    }
    MemberPageColumn("Welcome back, $name.",makerspaceLabel(state.scope?.slug.orEmpty())) {
        MemberPanel {
            Text("Make something meaningful",style=MaterialTheme.typography.titleLarge)
            Text("Find the right tools, plan your visit and keep your making moving.")
            BrandButton("Browse inventory",onInventory)
            BrandTextButton("Book a space",{extra="spaces"})
            BrandTextButton("Explore events",{extra="events"})
            BrandTextButton("Membership card",{extra="card";scope.launch {holder.refreshCard()}})
        }
        MemberLoaded(state.overview,{scope.launch {holder.refreshOverview()}}) {overview->
            MemberSection("Membership") {
                MemberPanel {
                    Text(if(overview.membershipActive)"Membership active" else "Membership inactive",style=MaterialTheme.typography.titleMedium)
                    overview.term?.let {Text(it.planName);Text("${memberDate(it.startsAt)} – ${memberDate(it.endsAt)}")}
                    if(overview.waiverRequired)Text("Complete your makerspace waiver before borrowing.")
                    if(overview.term?.blocksBorrowing==true)Text("Borrowing is restricted. Ask your makerspace for help.")
                    overview.restrictionCode?.let {Text("Borrowing status: ${memberStatus(it)}")}
                }
            }
            MemberSection("Dues") {MemberDues(overview.dues)}
            MemberSection("Active loans") {if(overview.loans.isEmpty())MemberEmpty() else overview.loans.take(3).forEach {MemberLoanPanel(it)}}
            MemberSection("Upcoming bookings") {if(overview.bookings.items.isEmpty())MemberEmpty() else overview.bookings.items.take(3).forEach {MemberBookingPanel(it)}}
            MemberSection("Events") {if(overview.registrations.isEmpty())MemberEmpty() else overview.registrations.take(3).forEach {MemberRegistrationPanel(it)}}
            MemberSection("Notices") {if(overview.notices.isEmpty())MemberEmpty() else overview.notices.forEach {notice->MemberPanel {Text(notice.title,style=MaterialTheme.typography.titleMedium);Text(notice.body)}}}
        }
        BrandTextButton("Refresh Home",{scope.launch {holder.refreshOverview()}},!state.overview.loading)
    }
}
@Composable fun MemberDues(dues:MemberDuesSummary?) {
    val balances=dues?.outstandingByCurrency
    MemberPanel {when {balances==null->Text("Dues are unavailable. Try again later.");balances.isEmpty()->Text("No dues outstanding.");else->balances.forEach {(currency,amount)->Text("$currency $amount",style=MaterialTheme.typography.titleLarge)}}}
}
@Composable private fun MemberLoanPanel(loan:MemberLoan) {
    MemberPanel {
        Text(loan.label,style=MaterialTheme.typography.titleMedium)
        Text(if(loan.overdue)"Overdue" else "On loan",color=if(loan.overdue)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text(loan.dueAt?.let {"Due ${memberDate(it)}"} ?: "Due date not provided")
        loan.renewalRefusal?.takeIf {it.isNotBlank()}?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
    }
}
@Composable private fun MemberBookingPanel(booking:MemberBooking) {
    MemberPanel {Text(booking.spaceName,style=MaterialTheme.typography.titleMedium);Text(memberStatus(booking.status));Text("${memberDate(booking.startsAt,booking.timezone)} – ${memberDate(booking.endsAt,booking.timezone)}");Text("Times in ${booking.timezone}",style=MaterialTheme.typography.bodySmall)}
}
@Composable private fun MemberRegistrationPanel(event:MemberRegistration) {
    MemberPanel {Text(event.title,style=MaterialTheme.typography.titleMedium);Text(memberStatus(event.status));Text(memberDate(event.startsAt));event.waitlistPosition?.let {Text("Waitlist position: $it")}}
}
@Composable fun MemberInventory(holder:MemberStateHolder) {
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
            Box(Modifier.weight(1f)) {MemberLazyPage("Inventory",
                if(query!=state.query)MemberLoad(error=state.inventory.error,loading=state.inventory.error==null) else state.inventory,listState,
                retry={scope.launch {holder.search(query)}},key={it.id},filtered=query.isNotBlank() || state.category!=null,header={
                Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(makerspaceLabel(state.scope?.slug.orEmpty()),color=MaterialTheme.colorScheme.onSurfaceVariant)
                BrandField(query,{query=it.take(200)},"Search inventory",Modifier.testTag("inventorySearch"),onDone={focus.clearFocus();keyboard?.hide();scope.launch {holder.search(query)}})
                Box {
                    BrandTextButton(state.categories.value?.firstOrNull {it.slug==state.category}?.name ?: "All categories",{menu=true})
                    DropdownMenu(menu,{menu=false}) {
                        DropdownMenuItem(text={Text("All categories")},onClick={menu=false;scope.launch {holder.search(query,null)}})
                        state.categories.value.orEmpty().forEach {category->DropdownMenuItem(text={Text(category.name)},onClick={menu=false;scope.launch {holder.search(query,category.slug)}})}
                    }
                }
                state.categories.error?.let {BrandError(it);BrandTextButton("Retry categories",{scope.launch {holder.refreshCategories()}})}
                }
                },footer={
                    if(state.inventory.value?.next!=null)BrandButton(if(state.inventory.loading)"Loading…" else "Load more",{scope.launch {holder.moreInventory()}},!state.inventory.loading)
                }) {item->OutlinedCard(onClick={scope.launch {holder.selectProduct(item.id)}},modifier=Modifier.fillMaxWidth().semantics {selected=state.productId==item.id}) {
                        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            MemberImage(item.imageUrl,item.name,Modifier.fillMaxWidth().height(144.dp))
                            Text(item.name,style=MaterialTheme.typography.titleLarge)
                            item.categoryName?.let {Text(it,style=MaterialTheme.typography.labelLarge)}
                            Text(memberAvailability(item),color=MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("View details",style=MaterialTheme.typography.labelLarge)
                        }
                    }}
            }
            if(split && state.productId!=null) {VerticalDivider();Box(Modifier.weight(1f)){MemberProductDetail(holder,{holder.closeProduct()})}}
        }
    }
}
@Composable private fun MemberProductDetail(holder:MemberStateHolder,onBack:()->Unit) {
    val state by holder.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    LaunchedEffect(holder,state.scope,state.productId) {kotlinx.coroutines.yield();val current=holder.state.value;if(current.product.value==null && !current.product.loading)current.productId?.let {holder.selectProduct(it)}}
    MemberPageColumn("Item details",makerspaceLabel(state.scope?.slug.orEmpty())) {
        BrandTextButton("Back to inventory",onBack)
        MemberLoaded(state.product,{state.productId?.let {scope.launch {holder.selectProduct(it)}}}) {item->
            MemberImage(item.imageUrl,item.name,Modifier.fillMaxWidth().height(200.dp))
            Text(item.name,style=MaterialTheme.typography.headlineMedium)
            item.categoryName?.let {Text(it,style=MaterialTheme.typography.labelLarge)}
            Text(memberAvailability(item),color=MaterialTheme.colorScheme.onPrimaryContainer)
            Text(item.description.ifBlank {"No description provided."})
            MemberSection("Borrowing terms") {Text(item.borrowingTerms.ifBlank {"Ask your makerspace about borrowing this item."})}
        }
    }
}
@Composable fun MemberActivityScreen(holder:MemberStateHolder) {
    val state by holder.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    LaunchedEffect(holder,state.scope) {kotlinx.coroutines.yield();if(holder.state.value.overview.value==null && !holder.state.value.overview.loading)holder.refreshOverview()}
    MemberPageColumn("Activity",makerspaceLabel(state.scope?.slug.orEmpty())) {
        MemberLoaded(state.overview,{scope.launch {holder.refreshOverview()}}) {overview->
            MemberSection("Requests") {if(overview.requests.isEmpty() && overview.serviceRequests.isEmpty())MemberEmpty()
                overview.requests.forEach {request->MemberPanel {Text("${request.itemCount} items",style=MaterialTheme.typography.titleMedium);Text(memberStatus(request.status));Text(memberDate(request.createdAt))}}
                overview.serviceRequests.forEach {request->MemberPanel {Text(request.title,style=MaterialTheme.typography.titleMedium);Text(memberStatus(request.status));request.queuePosition?.let {Text("Queue position: $it")};request.estimatedMinutes?.let {Text("Estimated time: $it minutes")}}}
            }
            MemberSection("Loans") {if(overview.loans.isEmpty())MemberEmpty() else overview.loans.forEach {MemberLoanPanel(it)}}
            MemberSection("Bookings") {if(overview.bookings.items.isEmpty())MemberEmpty() else overview.bookings.items.forEach {MemberBookingPanel(it)}
                if(overview.bookings.next!=null)BrandButton(if(state.bookingMoreBusy)"Loading…" else "Load more bookings",{scope.launch {holder.moreBookings()}},!state.bookingMoreBusy)}
            MemberSection("Events") {if(overview.registrations.isEmpty())MemberEmpty() else overview.registrations.forEach {MemberRegistrationPanel(it)}}
            MemberSection("Loan history") {if(overview.loanHistory.isEmpty())MemberEmpty() else overview.loanHistory.forEach {loan->MemberPanel {Text(loan.label,style=MaterialTheme.typography.titleMedium);Text(if(loan.returnedLate)"Returned late" else "Returned");loan.returnedAt?.let {Text(memberDate(it))}}}}
        }
    }
}
@Composable fun MemberInboxScreen(holder:MemberStateHolder) {
    val state by holder.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    LaunchedEffect(holder,state.scope) {kotlinx.coroutines.yield();if(holder.state.value.inbox.value==null && !holder.state.value.inbox.loading)holder.refreshInbox()}
    var selected by remember(state.scope){mutableStateOf<InboxItem?>(null)}
    val listState=rememberLazyListState()
    BackHandler(selected!=null){selected=null}
    if(selected!=null)MemberPageColumn("Message") {
            val message=requireNotNull(selected)
            BrandTextButton("Back to Inbox",{selected=null})
            Text(message.title,style=MaterialTheme.typography.headlineMedium);Text(memberDate(message.createdAt));Text(message.summary)
            Text(if(message.readAt!=null)"Read" else "Unread",style=MaterialTheme.typography.labelLarge)
            if(message.readAt==null)BrandButton("Mark as read",{scope.launch {if(holder.markRead(message.id))selected=null}},!state.inboxBusy)
            BrandError(state.inbox.error)
    } else MemberLazyPage("Inbox",state.inbox,listState,retry={scope.launch {holder.refreshInbox()}},key={it.id},header={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(makerspaceLabel(state.scope?.slug.orEmpty()),color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                FilterChip(!state.unread,{scope.launch {holder.filterInbox(false)}},label={Text("All")})
                FilterChip(state.unread,{scope.launch {holder.filterInbox(true)}},label={Text("Unread")})
            }
            BrandTextButton("Mark all as read",{scope.launch {holder.markAllRead()}},!state.inboxBusy && state.inbox.value?.items?.any {it.readAt==null}==true)
        }
    },footer={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(state.inbox.value?.next!=null)BrandButton("Load more",{scope.launch {holder.moreInbox()}},!state.inbox.loading)
            state.notice?.let {Text(it,Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
        }
    }) {message->OutlinedCard(onClick={selected=message},modifier=Modifier.fillMaxWidth()) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(if(message.readAt==null)"Unread" else "Read",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(message.title,style=MaterialTheme.typography.titleLarge);Text(message.summary);Text(memberDate(message.createdAt),style=MaterialTheme.typography.bodySmall)
    }}}
}
@Composable fun MemberCardScreen(holder:MemberStateHolder,onBack:()->Unit) {
    val state by holder.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    LaunchedEffect(holder,state.scope) {kotlinx.coroutines.yield();if(holder.state.value.card.value==null && !holder.state.value.card.loading)holder.refreshCard()}
    MemberPageColumn("Membership card",makerspaceLabel(state.scope?.slug.orEmpty())) {
        BrandTextButton("Back to Home",onBack)
        MemberLoaded(state.card,{scope.launch {holder.refreshCard()}}) {card->
            when {card.notIssued->Text("Your membership card hasn't been issued yet.");!card.isActive || !card.qrActive->Text("This card is not active. Ask your makerspace for help.");card.qrImage.isNullOrBlank()->Text("Your membership QR code is unavailable. Try again later.");else->{
                Text(card.printedName,style=MaterialTheme.typography.headlineMedium)
                card.cardNumber?.let {Text("Member card $it")}
                MemberImage(card.qrImage,"Membership QR code",Modifier.fillMaxWidth().height(280.dp),qr=true)
                Text("Active card",color=MaterialTheme.colorScheme.onPrimaryContainer)
            }}
        }
        BrandTextButton("Refresh card",{scope.launch {holder.refreshCard()}},!state.card.loading)
    }
}
@Composable private fun MemberEventsScreen(holder:MemberStateHolder,onBack:()->Unit) {
    val state by holder.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    LaunchedEffect(holder,state.scope) {kotlinx.coroutines.yield();if(holder.state.value.events.value==null && !holder.state.value.events.loading)holder.refreshEvents()}
    var selected by remember(state.scope){mutableStateOf<MemberEvent?>(null)}
    BackHandler {if(selected!=null)selected=null else onBack()}
    MemberPageColumn(if(selected==null)"Explore events" else "Event details",makerspaceLabel(state.scope?.slug.orEmpty())) {
        BrandTextButton(if(selected==null)"Back to Home" else "Back to events",{if(selected==null)onBack() else selected=null})
        if(selected!=null){val event=requireNotNull(selected);MemberImage(event.imageUrl,event.title,Modifier.fillMaxWidth().height(180.dp));Text(event.title,style=MaterialTheme.typography.headlineMedium);Text(event.description);Text(memberDate(event.startsAt));Text(event.location);Text(event.availability);Text("${event.currency} ${event.paymentAmount}")}
        else MemberLoaded(state.events,{scope.launch {holder.refreshEvents()}}) {events->if(events.isEmpty())MemberEmpty() else events.forEach {event->OutlinedCard(onClick={selected=event},modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(event.title,style=MaterialTheme.typography.titleLarge);Text(memberDate(event.startsAt));Text(event.availability)}}}}
    }
}
@Composable private fun MemberSpacesScreen(holder:MemberStateHolder,onBack:()->Unit) {
    val state by holder.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    LaunchedEffect(holder,state.scope) {kotlinx.coroutines.yield();if(holder.state.value.spaces.value==null && !holder.state.value.spaces.loading)holder.refreshSpaces()}
    var selected by remember(state.scope){mutableStateOf<MemberSpace?>(null)}
    BackHandler {if(selected!=null)selected=null else onBack()}
    MemberPageColumn(if(selected==null)"Find a space" else "Space details",makerspaceLabel(state.scope?.slug.orEmpty())) {
        BrandTextButton(if(selected==null)"Back to Home" else "Back to spaces",{if(selected==null)onBack() else selected=null})
        if(selected!=null){val space=requireNotNull(selected);MemberImage(space.imageUrl,space.name,Modifier.fillMaxWidth().height(180.dp));Text(space.name,style=MaterialTheme.typography.headlineMedium);Text(space.description);Text(space.location);Text("Capacity: ${space.capacity}");Text("Approval: ${memberStatus(space.approvalMode)}");if(!space.showAvailability)Text("Ask your makerspace about availability.")}
        else MemberLoaded(state.spaces,{scope.launch {holder.refreshSpaces()}}) {spaces->if(spaces.isEmpty())MemberEmpty() else spaces.forEach {space->OutlinedCard(onClick={selected=space},modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(space.name,style=MaterialTheme.typography.titleLarge);Text(space.location);Text("Capacity: ${space.capacity}")}}}}
    }
}
@Composable fun MemberProfileSummary(holder:MemberStateHolder) {
    val state by holder.state.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    var scenarios by remember {mutableStateOf(false)}
    state.overview.value?.term?.let {term->MemberPanel {
        Text("Your membership",style=MaterialTheme.typography.titleLarge)
        Text(term.planName);Text("${memberDate(term.startsAt)} – ${memberDate(term.endsAt)}")
        Text(if(term.current)"Current term" else "Term ended")
    }}
    if(holder.hasPreview) {
        Text("Demo scenarios",style=MaterialTheme.typography.titleLarge)
        Text("Preview local states. These controls never change a real account or makerspace.",style=MaterialTheme.typography.bodySmall)
        Box {
            BrandTextButton("Choose a demo state",{scenarios=true})
            DropdownMenu(scenarios,{scenarios=false}) {MemberPreviewScenario.entries.forEach {scenario->
                DropdownMenuItem(text={Text(scenario.label)},onClick={scenarios=false;scope.launch {holder.preview(scenario)}})
            }}
        }
    }
}
