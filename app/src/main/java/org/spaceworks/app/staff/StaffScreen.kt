package org.spaceworks.app.staff

import org.spaceworks.app.network.retrySecondsRemaining

import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.rememberMobileText
import org.spaceworks.app.platform.rememberActionFeedback
import org.spaceworks.app.platform.FeedbackKind

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.spaceworks.app.platform.CameraQrScanner
import org.spaceworks.app.ui.*
import org.spaceworks.generated.model.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

private data class StaffConfirmation(val title:String,val detail:String,val action:suspend()->Unit)
@Composable private fun StaffCard(content:@Composable ColumnScope.()->Unit){OutlinedCard(
    colors=CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer),
    border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),shape=RoundedCornerShape(16.dp)){
    Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)}}

/** All physical mutations require visible review and confirmation. Camera callbacks resolve only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun StaffScreen(holder:StaffStateHolder,demo:Boolean,onBack:()->Unit,previewScope:org.spaceworks.app.member.MemberScope?=null){
    val text=rememberMobileText()

    val s by holder.state.collectAsStateWithLifecycle();val work=rememberCoroutineScope()
    val feedback=rememberActionFeedback(holder to (s.scope to s.section))
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    val scroll=rememberScrollState();var confirmation by remember(s.scope,s.section){mutableStateOf<StaffConfirmation?>(null)}
    var previewOptionsOpen by remember(holder){mutableStateOf(false)}
    var clock by remember{mutableLongStateOf(System.currentTimeMillis())}
    val preview=demo&&holder.isDemo
    ProtectSecrets(!preview)
    LaunchedEffect(s.retryAtMillis){do{clock=System.currentTimeMillis();if(clock<s.retryAtMillis)kotlinx.coroutines.delay(250)}while(clock<s.retryAtMillis)}
    LaunchedEffect(s.scope,s.section){when(s.section){StaffSection.QUEUE->holder.loadQueue();StaffSection.DIRECT->holder.loadDirect();StaffSection.EVENTS->holder.loadEvents();StaffSection.SPACES->holder.loadSpaces();else->Unit}}
    LaunchedEffect(s.error){if(s.error!=null){focus.clearFocus();keyboard?.hide();scroll.scrollTo(0)}}
    val enabled=!s.busy&&clock>=s.retryAtMillis
    fun launch(action:suspend()->Unit){
        focus.clearFocus();keyboard?.hide()
        val before=holder.state.value
        val ticket=feedback.begin()
        work.launch {
            action()
            val after=holder.state.value
            val recorded=after.scope==before.scope && after.error==null &&
                ((after.handover?.receipt!=null && before.handover?.receipt==null) ||
                 (after.attendanceNotice!=null && before.attendanceNotice==null))
            feedback.finish(ticket,if(recorded)FeedbackKind.CONFIRM else null)
        }
    }
    fun close(){if(!s.busy){if(s.section in setOf(StaffSection.QUEUE,StaffSection.DIRECT,StaffSection.EVENTS,StaffSection.SPACES))onBack()else holder.close()}}
    BackHandler{close()}
    confirmation?.let{pending->
        val dismiss={if(!s.busy)confirmation=null}
        val confirm={confirmation=null;launch(pending.action)}
        val fullHeight=LocalDensity.current.fontScale>=1.6f||LocalConfiguration.current.screenHeightDp<520
        if(fullHeight)Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
            Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface){
                Box(Modifier.fillMaxSize().safeDrawingPadding(),contentAlignment=Alignment.TopCenter){
                    Column(Modifier.widthIn(max=520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
                        Text(pending.title,style=MaterialTheme.typography.headlineSmall,modifier=Modifier.semantics{heading()})
                        Text(pending.detail)
                        BrandButton(text(StaffStrings.STAFF_CONFIRM),confirm,enabled,Modifier.fillMaxWidth().testTag("staff-confirm-action"))
                        BrandTextButton(text(StaffStrings.STAFF_CANCEL),dismiss,!s.busy,Modifier.fillMaxWidth())
                    }
                }
            }
        }else AlertDialog(onDismissRequest=dismiss,title={Text(pending.title)},
            text={Text(pending.detail,Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState()))},
            confirmButton={BrandButton(text(StaffStrings.STAFF_CONFIRM),confirm,enabled,Modifier.testTag("staff-confirm-action"))},
            dismissButton={BrandTextButton(text(StaffStrings.STAFF_CANCEL),dismiss,!s.busy)})
    }
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter){
        Column(Modifier.widthIn(max=960.dp).fillMaxWidth().imePadding().verticalScroll(scroll).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
            BrandTextButton(text(StaffStrings.STAFF_BACK),{close()},!s.busy)
            Text(text(StaffStrings.STAFF_STAFF_TOOLS),style=MaterialTheme.typography.headlineLarge,modifier=Modifier.semantics{heading()})
            if(preview)Text(text(StaffStrings.STAFF_DEMO_SAMPLE_STAFF_WORKSPACE_NO_LIVE_EQUIPMENT_ATTENDANCE_OR),color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(preview&&holder.previewOptions.isNotEmpty()){
                BrandTextButton(text(StaffStrings.STAFF_PREVIEW_SCENARIO),{previewOptionsOpen=!previewOptionsOpen},!s.busy,Modifier.testTag("staff-preview-options"))
                if(previewOptionsOpen)FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    holder.previewOptions.forEach{option->
                        val chosen=holder.previewScenarioId==option.id
                        BrandTextButton(text(option.label),{val target=previewScope?:s.scope;if(target!=null){previewOptionsOpen=false;launch{holder.previewScenario(option.id,target)}}},!s.busy,
                            Modifier.semantics{selected=chosen}.testTag("staff-preview-${option.id}"))
                    }
                }
            }
            if(!s.available){Text(text(StaffStrings.STAFF_STAFF_TOOLS_ARE_UNAVAILABLE_FOR_THIS_DEVICE_SESSION_USE_THE));return@Column}
            if(s.busy||s.loading.isNotEmpty())LinearProgressIndicator(Modifier.fillMaxWidth().semantics{contentDescription=text(StaffStrings.STAFF_LOADING_STAFF_WORKSPACE)})
            BrandError(s.error);s.notice?.let{Text(it,Modifier.semantics{liveRegion=LiveRegionMode.Polite})}
            if(clock<s.retryAtMillis)Text(text(StaffStrings.STAFF_RETRY_SECONDS, retrySecondsRemaining(clock,s.retryAtMillis)))
            if(s.section in setOf(StaffSection.QUEUE,StaffSection.DIRECT,StaffSection.EVENTS,StaffSection.SPACES))FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                listOf(StaffSection.QUEUE to text(StaffStrings.STAFF_REQUESTS),StaffSection.DIRECT to text(StaffStrings.STAFF_DIRECT_HANDOUTS),StaffSection.EVENTS to text(StaffStrings.STAFF_EVENT_CHECK_IN),StaffSection.SPACES to text(StaffStrings.STAFF_BOOKINGS)).forEach{(section,label)->
                    val sectionSelected=s.section==section
                    BrandTextButton(label,{holder.select(section)},enabled,Modifier.semantics{selected=sectionSelected}.testTag("staff-tab-${section.name.lowercase()}"))}}
            when(s.section){
                StaffSection.QUEUE->{
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){StaffQueue.entries.forEach{queue->
                        val queueSelected=s.queue==queue
                        BrandTextButton(text(when(queue){StaffQueue.PENDING->StaffStrings.STAFF_PENDING;StaffQueue.ACCEPTED->StaffStrings.STAFF_ACCEPTED;StaffQueue.ACTIVE->StaffStrings.STAFF_ACTIVE}), {launch{holder.changeQueue(queue)}},enabled,Modifier.semantics{selected=queueSelected}.testTag("staff-queue-${queue.name.lowercase()}"))}}
                    if("queue" !in s.loading&&s.requests.isEmpty())Text(text(StaffStrings.STAFF_NO_REQUESTS_IN_THIS_QUEUE))
                    s.requests.forEach{row->StaffCard{Text(text(StaffStrings.STAFF_REQUEST_1_S, row.id),style=MaterialTheme.typography.titleLarge);Text(row.requesterDisplay);Text(row.requestedFor);Text(text(StaffStrings.STAFF_STATUS_1_S, row.status.replace('_',' ')))
                        BrandTextButton(text(StaffStrings.STAFF_REVIEW_REQUEST),{holder.openRequest(row.id)},enabled,Modifier.testTag("staff-request-${row.id}"))}}
                    BrandTextButton(text(StaffStrings.STAFF_REFRESH_REQUESTS),{launch{holder.loadQueue()}},enabled)
                    MoreStaff(s,"queue",enabled){launch{holder.loadQueue(true)}}
                }
                StaffSection.REQUEST->{s.selectedRequest?.let{row->StaffRequestDetail(holder,row,enabled){title,detail,action->confirmation=StaffConfirmation(title,detail,action)}}}
                StaffSection.DIRECT->{
                    if(holder.can("issue_direct_loan")&&holder.can("upload_evidence"))BrandButton(text(StaffStrings.STAFF_NEW_DIRECT_HANDOUT),{holder.beginHandover(StaffHandoverMode.DIRECT_ISSUE)},enabled,Modifier.testTag("staff-direct-new"))
                    if("direct" !in s.loading&&s.directLoans.isEmpty())Text(text(StaffStrings.STAFF_NO_DIRECT_LOANS))
                    s.directLoans.forEach{loan->StaffCard{Text(text(StaffStrings.STAFF_LOAN_1_S, loan.id),style=MaterialTheme.typography.titleLarge);Text(loan.targetLabel);Text(text(StaffStrings.STAFF_STATUS_1_S, loan.status))
                        loan.items.forEach{Text("${it.productName} · ${it.quantity}")}
                        if(loan.status=="active"&&holder.can("return_request")&&holder.can("upload_evidence"))BrandTextButton(text(StaffStrings.STAFF_RECORD_RETURN),{holder.beginHandover(StaffHandoverMode.DIRECT_RETURN,loan.id)},enabled,Modifier.testTag("staff-direct-return-${loan.id}"))}}
                    BrandTextButton(text(StaffStrings.STAFF_REFRESH_DIRECT_LOANS),{launch{holder.loadDirect()}},enabled);MoreStaff(s,"direct",enabled){launch{holder.loadDirect(true)}}
                }
                StaffSection.HANDOVER->StaffHandover(holder,preview,enabled){title,detail,action->confirmation=StaffConfirmation(title,detail,action)}
                StaffSection.EVENTS->{
                    if("events" !in s.loading&&s.events.isEmpty())Text(text(StaffStrings.STAFF_NO_EVENTS_AVAILABLE_FOR_STAFF_CHECK_IN))
                    s.events.forEach{event->StaffCard{Text(event.title,style=MaterialTheme.typography.titleLarge);Text(event.location)
                        BrandTextButton(text(StaffStrings.STAFF_OPEN_CHECK_IN),{holder.openCheckIn(event.id)},enabled&&holder.can("manage_events"),Modifier.testTag("staff-event-${event.id}"))}}
                    BrandTextButton(text(StaffStrings.STAFF_REFRESH_EVENTS),{launch{holder.loadEvents()}},enabled);MoreStaff(s,"events",enabled){launch{holder.loadEvents(true)}}
                }
                StaffSection.CHECK_IN->{
                    Text(s.checkInEvent?.title.orEmpty(),style=MaterialTheme.typography.titleLarge)
                    Text(text(StaffStrings.STAFF_SCAN_THE_EVENT_CODE_REVIEW_THE_PERSON_AND_ELIGIBILITY_THEN_C))
                    StaffScanInput(holder,StaffScanKind.CHECK_IN,text(StaffStrings.STAFF_CHECK_IN_CODE),preview,enabled,"staff-checkin-code","DEMO-CHECKIN")
                    s.person?.let{person->StaffCard{Text(person.name,style=MaterialTheme.typography.titleLarge);Text(text(StaffStrings.STAFF_REGISTRATION_1_S, person.status.replace('_',' ')));Text(text(StaffStrings.STAFF_PAYMENT_1_S, person.paymentStatus?.replace('_',' ')?:text(StaffStrings.STAFF_UNKNOWN)))
                        if(person.confirmable&&person.status=="registered")BrandButton(text(StaffStrings.STAFF_CONFIRM_ATTENDANCE),{confirmation=StaffConfirmation(text(StaffStrings.STAFF_CONFIRM_ATTENDANCE_C1F390),text(StaffStrings.STAFF_RECORD_ATTENDANCE_FOR_1_S, person.name)){holder.confirmAttendance()}},enabled&&holder.canConfirmAttendance(),Modifier.testTag("staff-checkin-confirm"))
                        else Text(text(StaffStrings.STAFF_THIS_REGISTRATION_CANNOT_BE_CHECKED_IN_RESOLVE_ANY_APPROVAL))}}
                    s.attendanceNotice?.let{Text(it,Modifier.semantics{liveRegion=LiveRegionMode.Polite}.testTag("staff-attendance-receipt"))}
                }
                StaffSection.SPACES->{
                    if("spaces" !in s.loading&&s.spaces.isEmpty())Text(text(StaffStrings.STAFF_NO_BOOKABLE_SPACES))
                    s.spaces.forEach{space->StaffCard{Text(space.name,style=MaterialTheme.typography.titleLarge);Text(space.location)
                        BrandTextButton(text(StaffStrings.STAFF_REVIEW_BOOKINGS),{launch{holder.openBookings(space)}},enabled,Modifier.testTag("staff-space-${space.id}"))}}
                    BrandTextButton(text(StaffStrings.STAFF_REFRESH_SPACES),{launch{holder.loadSpaces()}},enabled);MoreStaff(s,"spaces",enabled){launch{holder.loadSpaces(true)}}
                }
                StaffSection.BOOKINGS->{
                    Text(s.selectedSpace?.name.orEmpty(),style=MaterialTheme.typography.titleLarge)
                    if("bookings" !in s.loading&&s.bookings.isEmpty())Text(text(StaffStrings.STAFF_NO_BOOKINGS_FOR_THIS_SPACE))
                    s.bookings.forEach{row->StaffCard{Text(row.name,style=MaterialTheme.typography.titleLarge);Text("${row.startsAt} – ${row.endsAt}");Text(text(StaffStrings.STAFF_STATUS_1_S, row.status.value.replace('_',' ')))
                        row.payment?.let{Text(text(StaffStrings.STAFF_PAYMENT_1_S, it.status))}
                        if(row.status.value=="pending"&&holder.can("manage_bookings"))FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                            BrandButton(text(StaffStrings.STAFF_APPROVE),{confirmation=StaffConfirmation(text(StaffStrings.STAFF_APPROVE_BOOKING),text(StaffStrings.STAFF_APPROVE_1_S_S_BOOKING_FOR_2_S, row.name, s.selectedSpace?.name.orEmpty())){holder.decideBooking(row.id,true)}},enabled,Modifier.testTag("staff-booking-approve-${row.id}"))
                            BrandTextButton(text(StaffStrings.STAFF_REJECT),{confirmation=StaffConfirmation(text(StaffStrings.STAFF_REJECT_BOOKING),text(StaffStrings.STAFF_REJECT_1_S_S_BOOKING, row.name)){holder.decideBooking(row.id,false)}},enabled)}}}
                    BrandTextButton(text(StaffStrings.STAFF_REFRESH_BOOKINGS),{launch{holder.loadBookings()}},enabled);MoreStaff(s,"bookings",enabled){launch{holder.loadBookings(true)}}
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
@Composable private fun MoreStaff(s:StaffViewState,key:String,enabled:Boolean,more:()->Unit){
    val text=rememberMobileText()
if(s.nextPages[key]!=null)BrandTextButton(text(StaffStrings.STAFF_LOAD_MORE),more,enabled&&key !in s.loading)}

@Composable private fun StaffRequestDetail(holder:StaffStateHolder,row:AdminRequest,enabled:Boolean,confirm:(String,String,suspend()->Unit)->Unit){
    val text=rememberMobileText()

    var quantities by remember(row.id){mutableStateOf<Map<Int,String>>(emptyMap())};var reason by remember(row.id){mutableStateOf("")}
    Text(text(StaffStrings.STAFF_REQUEST_1_S, row.id),style=MaterialTheme.typography.titleLarge);Text(row.requesterDisplay);Text(row.requestedFor);Text(text(StaffStrings.STAFF_STATUS_1_S, row.status.replace('_',' ')))
    row.items.forEach{item->StaffCard{
        Text(item.productName,style=MaterialTheme.typography.titleMedium);Text(text(StaffStrings.STAFF_REQUESTED_1_S_ACCEPTED_2_S_ISSUED_3_S, item.requestedQuantity, item.acceptedQuantity, item.issuedQuantity))
        if(row.status in setOf("pending","pending_approval")&&holder.can("accept_request"))BrandField(quantities[item.id].orEmpty(),{value->quantities=quantities+(item.id to value.filter(Char::isDigit).take(10))},text(StaffStrings.STAFF_ACCEPT_QUANTITY_FOR_1_S, item.productName),Modifier.testTag("staff-accept-quantity-${item.id}"),enabled,role=FieldRole.NUMBER)
        Text(text(StaffStrings.STAFF_STORAGE_1_S, item.storageLocation))}}
    if(row.status in setOf("pending","pending_approval")){
        if(holder.can("accept_request"))BrandButton(text(StaffStrings.STAFF_ACCEPT_SELECTED_QUANTITIES),{confirm(text(StaffStrings.STAFF_ACCEPT_THIS_REQUEST),text(StaffStrings.STAFF_ONLY_THE_QUANTITIES_ENTERED_ABOVE_WILL_BE_ACCEPTED)){holder.accept(row.items.associate{it.id to (quantities[it.id]?.toIntOrNull()?:0)})}},enabled,Modifier.testTag("staff-accept"))
        if(holder.can("reject_request")){BrandField(reason,{reason=it.take(1000)},text(StaffStrings.STAFF_REASON_FOR_REJECTION),enabled=enabled)
            BrandTextButton(text(StaffStrings.STAFF_REJECT_REQUEST),{confirm(text(StaffStrings.STAFF_REJECT_REQUEST_920B91),reason){holder.reject(reason)}},enabled&&reason.isNotBlank(),Modifier.testTag("staff-reject"))}
    }
    if(row.status=="accepted"&&holder.can("issue_request")&&holder.can("assign_box")&&holder.can("upload_evidence"))BrandButton(text(StaffStrings.STAFF_PREPARE_ISSUE),{holder.beginHandover(StaffHandoverMode.ISSUE,row.id)},enabled,Modifier.testTag("staff-prepare-issue"))
    if(row.status in setOf("issued","partially_returned")&&holder.can("return_request")&&holder.can("upload_evidence"))BrandButton(text(StaffStrings.STAFF_PREPARE_RETURN),{holder.beginHandover(StaffHandoverMode.RETURN_REQUEST,row.id)},enabled,Modifier.testTag("staff-prepare-return"))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun StaffHandover(holder:StaffStateHolder,demo:Boolean,enabled:Boolean,confirm:(String,String,suspend()->Unit)->Unit){
    val text=rememberMobileText()

    val s by holder.state.collectAsStateWithLifecycle();val h=s.handover?:return;val work=rememberCoroutineScope()
    fun launch(action:suspend()->Unit){work.launch{action()}}
    LaunchedEffect(h.mode,h.request?.id,h.loan?.id){if(h.mode==StaffHandoverMode.DIRECT_ISSUE){holder.loadMembers();holder.loadProducts()}}
    Text(if(h.returning)text(StaffStrings.STAFF_RETURN_EQUIPMENT)else if(h.mode==StaffHandoverMode.DIRECT_ISSUE)text(StaffStrings.STAFF_DIRECT_HANDOUT)else text(StaffStrings.STAFF_ISSUE_EQUIPMENT),style=MaterialTheme.typography.titleLarge)
    Text(text(StaffStrings.STAFF_STEP_1_S_OF_2_S_3_S, h.step+1, h.steps.size, staffStep(h.steps[h.step])),Modifier.semantics{heading()})
    if(h.assignmentRecorded)Text(text(StaffStrings.STAFF_BOX_ASSIGNMENT_HAS_BEEN_RECORDED),Modifier.semantics{liveRegion=LiveRegionMode.Polite})
    if(h.needsReload){Text(text(StaffStrings.STAFF_THE_OUTCOME_NEEDS_TO_BE_RECONCILED_CLOSE_THIS_HANDOVER_AND_R));return}
    if(h.receipt!=null){Text(h.receipt,style=MaterialTheme.typography.titleLarge,modifier=Modifier.testTag("staff-handover-receipt"));Text(text(StaffStrings.STAFF_THE_SERVER_RECORDED_THIS_ACTION_CLOSE_AND_REFRESH_THE_QUEUE));return}
    when(h.steps[h.step]){
        "Container"->{
            if(h.requiresBox){StaffScanInput(holder,StaffScanKind.CONTAINER,text(StaffStrings.STAFF_CONTAINER_OR_LOAN_CODE),demo,enabled,"staff-box-code","DEMO-BOX")
                if(h.boxCode.isNotEmpty())Text(text(StaffStrings.STAFF_VERIFIED_1_S, h.boxLabel),Modifier.testTag("staff-box-verified"))}
            else Text(text(StaffStrings.STAFF_THIS_DIRECT_LOAN_DOES_NOT_REQUIRE_A_CONTAINER_SCAN))
        }
        "Borrower"->{
            if(h.mode==StaffHandoverMode.DIRECT_ISSUE){
                if("members" !in s.loading&&s.members.isEmpty())Text(text(StaffStrings.STAFF_NO_ELIGIBLE_BORROWERS_REFRESH_BEFORE_CONTINUING))
                s.members.forEach{member->StaffCard{Text(member.displayName);BrandTextButton(if(h.borrowerId==member.userId)text(StaffStrings.STAFF_BORROWER_SELECTED)else text(StaffStrings.STAFF_SELECT_BORROWER),{holder.setBorrower(member.userId)},enabled,Modifier.semantics{selected=h.borrowerId==member.userId}.testTag("staff-borrower-${member.userId}"))}}
                MoreStaff(s,"members",enabled){launch{holder.loadMembers(true)}}
            }else{
                Text(h.request?.requesterDisplay.orEmpty());Text(text(StaffStrings.STAFF_CONFIRM_THE_BORROWER_BEFORE_HANDING_OVER_EQUIPMENT))
                Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(h.borrowerConfirmed,enabled=enabled,role=Role.Checkbox,onValueChange={holder.confirmBorrower(it)}).testTag("staff-borrower-confirmed"),verticalAlignment=Alignment.CenterVertically){Checkbox(h.borrowerConfirmed,null);Spacer(Modifier.width(12.dp));Text(text(StaffStrings.STAFF_I_CHECKED_THE_BORROWER_S_IDENTITY))}
            }
        }
        "Equipment"->{
            if(h.mode==StaffHandoverMode.DIRECT_ISSUE){s.products.forEach{product->StaffCard{Text(product.name,style=MaterialTheme.typography.titleMedium);Text(text(StaffStrings.STAFF_AVAILABLE_1_S, product.availableQuantity?:text(StaffStrings.STAFF_UNKNOWN)))
                if(product.trackingMode?.value!="individual"&&product.isArchived!=true)StaffNumber(h.quantities[product.id],text(StaffStrings.STAFF_QUANTITY_FOR_1_S, product.name),enabled,"staff-product-${product.id}"){holder.setQuantity(product.id,it)}}};MoreStaff(s,"products",enabled){launch{holder.loadProducts(true)}}}
            else h.request?.items?.forEach{Text(text(StaffStrings.STAFF_1_S_ACCEPTED_2_S_3_S, it.productName, it.acceptedQuantity, if(it.requiresAssetQr)text(StaffStrings.STAFF_SCAN_EACH_ASSET)else ""))}
            StaffScanInput(holder,StaffScanKind.EQUIPMENT,text(StaffStrings.STAFF_EQUIPMENT_OR_ASSET_CODE),demo,enabled,"staff-equipment-code","DEMO-ASSET")
            h.scans.forEach{scan->StaffCard{Text("${scan.qr.targetType.value} #${scan.qr.targetId}");BrandTextButton(text(StaffStrings.STAFF_REMOVE_SCANNED_EQUIPMENT),{holder.removeScan(scan.qr.id)},enabled)}}
        }
        "Returned items"->{h.returns.forEach{row->StaffCard{Text(row.label,style=MaterialTheme.typography.titleMedium);Text(text(StaffStrings.STAFF_OUTSTANDING_1_S, row.remaining))
            if(!row.metadataValid)BrandError(text(StaffStrings.STAFF_ASSET_METADATA_IS_INCOMPLETE_REFRESH_THIS_LOAN_BEFORE_RETURN))
            if(row.assets.isEmpty()){
                StaffNumber(row.returned,text(StaffStrings.STAFF_RETURNED_1_S, row.label),enabled,"staff-returned-${row.itemId}"){holder.setReturn(row.itemId,it,row.damaged,row.missing)}
                StaffNumber(row.damaged,text(StaffStrings.STAFF_DAMAGED_1_S, row.label),enabled,"staff-damaged-${row.itemId}"){holder.setReturn(row.itemId,row.returned,it,row.missing)}
                StaffNumber(row.missing,text(StaffStrings.STAFF_MISSING_1_S, row.label),enabled,"staff-missing-${row.itemId}"){holder.setReturn(row.itemId,row.returned,row.damaged,it)}
            }else row.assets.forEach{asset->Column(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.semantics(mergeDescendants=true){contentDescription="${row.label}, ${asset.label}"}){
                Text(asset.label);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){StaffAssetOutcome.entries.forEach{outcome->val outcomeLabel=staffOutcome(outcome);BrandTextButton(outcomeLabel,{holder.setAsset(row.itemId,asset.id,outcome)},enabled,Modifier.semantics{selected=asset.outcome==outcome;contentDescription=text(StaffStrings.STAFF_ASSET_OUTCOME,asset.label,outcomeLabel)})}}}}
            if(!row.valid)BrandError(text(StaffStrings.STAFF_CHOOSE_NONNEGATIVE_COUNTS_TOTALING_NO_MORE_THAN_THE_OUTSTAND))}}
        }
        "Photo and review"->{
            Text(text(StaffStrings.STAFF_REVIEW_ALL_EQUIPMENT_AND_COUNTS_BEFORE_CONFIRMING_AN_UPLOADE))
            if(h.returning)h.returns.forEach{Text(text(StaffStrings.STAFF_1_S_RETURNED_2_S_DAMAGED_3_S_MISSING_4_S, it.label, it.returned, it.damaged, it.missing))}
            else if(h.request!=null)h.request.items.forEach{Text(text(StaffStrings.STAFF_1_S_ACCEPTED_2_S, it.productName, it.acceptedQuantity))}
            else s.products.filter{(h.quantities[it.id]?:0)>0}.forEach{Text("${it.name}: ${h.quantities[it.id]}")}
            BrandButton(text(StaffStrings.STAFF_LOAD_KIT_CHECKLIST),{launch{holder.prepareKits()}},enabled&&holder.itemsReady(),Modifier.testTag("staff-kit-preview"))
            if(h.kitsReady&&h.kits.isEmpty())Text(text(StaffStrings.STAFF_NO_KIT_COMPONENTS_ARE_REQUIRED_FOR_THIS_HANDOVER))
            h.kits.forEach{kit->StaffCard{Text("${kit.ownerLabel} · ${kit.label}");Text(text(StaffStrings.STAFF_EXPECTED_1_S, kit.quantity))
                StaffNumber(h.kitCounts[kit.key],text(StaffStrings.STAFF_PRESENT_1_S_FOR_2_S, kit.label, kit.ownerLabel),enabled,"staff-kit-${kit.key}"){holder.setKit(kit.key,it)}}}
            StaffPhotoPicker(holder,demo,enabled,h.photoReady,h.photoRevision)
            if(h.photoReady)BrandButton(if(h.evidenceId==null)text(StaffStrings.STAFF_UPLOAD_EVIDENCE_PHOTO)else text(StaffStrings.STAFF_EVIDENCE_PHOTO_UPLOADED),{launch{holder.uploadPhoto()}},enabled&&h.evidenceId==null,Modifier.testTag("staff-upload-evidence"))
            BrandField(h.remark,{holder.setRemark(it)},if(h.returning)text(StaffStrings.STAFF_RETURN_NOTES_REQUIRED)else text(StaffStrings.STAFF_HANDOVER_NOTES),enabled=enabled)
            BrandButton(if(h.returning)text(StaffStrings.STAFF_CONFIRM_RETURN)else text(StaffStrings.STAFF_CONFIRM_ISSUE),{confirm(if(h.returning)text(StaffStrings.STAFF_RECORD_THIS_RETURN)else text(StaffStrings.STAFF_ISSUE_THIS_EQUIPMENT),text(StaffStrings.STAFF_CONFIRM_THE_REVIEWED_BORROWER_EQUIPMENT_COUNTS_AND_EVIDENCE)){holder.confirmHandover()}},enabled&&holder.ready(),Modifier.testTag("staff-handover-confirm"))
        }
    }
    FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        if(h.step>0)BrandTextButton(text(StaffStrings.STAFF_PREVIOUS_STEP),{holder.moveStep(h.step-1)},enabled)
        if(h.step<h.reviewStep){val allowed=when(h.steps[h.step]){"Container"->!h.requiresBox||h.boxCode.isNotEmpty();"Borrower"->if(h.mode==StaffHandoverMode.DIRECT_ISSUE)h.borrowerId!=null else h.borrowerConfirmed;"Equipment","Returned items"->holder.itemsReady();else->true}
            BrandButton(text(StaffStrings.STAFF_CONTINUE),{holder.moveStep(h.step+1)},enabled&&allowed,Modifier.testTag("staff-handover-next"))}}
}

@Composable private fun StaffNumber(value:Int?,label:String,enabled:Boolean,tag:String,changed:(Int)->Unit){
    var text by remember(value){mutableStateOf(value?.toString().orEmpty())}
    BrandField(text,{incoming->text=incoming.filter(Char::isDigit).take(10);changed(text.toIntOrNull()?:-1)},label,Modifier.testTag(tag),enabled,role=FieldRole.NUMBER)
}
@Composable private fun StaffScanInput(holder:StaffStateHolder,kind:StaffScanKind,label:String,demo:Boolean,enabled:Boolean,tag:String,demoPayload:String){
    val text=rememberMobileText();val work=rememberCoroutineScope()
    val observed by holder.state.collectAsStateWithLifecycle()
    val feedback=rememberActionFeedback(holder to (observed.scope to kind))
    val target=if(observed.scope==null)null else holder.scanTarget(kind)
    var code by remember(holder,tag,target){mutableStateOf("")}
    var camera by remember(holder,tag,target){mutableStateOf<StaffScanTarget?>(null)}
    var failure by remember(holder,tag,target){mutableStateOf<String?>(null)}
    var session by remember(holder,tag,target){mutableIntStateOf(0)}
    var mounted by remember{mutableStateOf(true)}
    val currentHolder by rememberUpdatedState(holder)
    val currentTarget by rememberUpdatedState(target)
    val currentCamera by rememberUpdatedState(camera)
    DisposableEffect(Unit){onDispose{mounted=false}}
    fun resolve(raw:String,owner:StaffScanTarget){
        if(!mounted||currentHolder!==holder||currentTarget!=owner||!holder.acceptsScanTarget(owner))return
        code="";camera=null;failure=null
        val before=holder.state.value
        val ticket=feedback.begin()
        work.launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED){
            val accepted=holder.resolveScan(owner,raw)
            val after=holder.state.value
            val resolved=accepted && after.scope==before.scope && after.error==null && when(kind) {
                StaffScanKind.CHECK_IN->after.person!=null && after.person!=before.person
                StaffScanKind.CONTAINER->!after.handover?.boxCode.isNullOrBlank() && after.handover?.boxCode!=before.handover?.boxCode
                StaffScanKind.EQUIPMENT->!after.handover?.scans.isNullOrEmpty() && after.handover?.scans!=before.handover?.scans
            }
            feedback.finish(ticket,if(resolved)FeedbackKind.SELECTION else null)
        }
    }
    BrandField(code,{code=it.take(2048)},label,Modifier.testTag(tag),enabled)
    BrandError(failure)
    BrandTextButton(text(StaffStrings.STAFF_RESOLVE_CODE),{target?.let{resolve(code,it)}},enabled&&target!=null&&code.isNotBlank())
    if(demo)BrandTextButton(text(StaffStrings.STAFF_USE_SAMPLE_CODE),{target?.let{resolve(demoPayload,it)}},enabled&&target!=null,Modifier.testTag("$tag-demo"))
    else {
        BrandTextButton(if(camera!=null)text(StaffStrings.STAFF_CLOSE_CAMERA)else text(StaffStrings.STAFF_SCAN_QR_CODE),{
            if(camera!=null)camera=null else holder.scanTarget(kind)?.let{camera=it;failure=null;session++}
        },enabled&&target!=null)
        camera?.let{opened->
            val scanHolder=holder
            // Force separate scanner callback state for each original context/session. Otherwise
            // CameraQrScanner's latest-lambda seam can give an old frame a new owner's ticket.
            key(opened,session){CameraQrScanner(onCode={raw->
                if(mounted&&currentHolder===scanHolder&&camera===opened&&currentCamera===opened&&currentTarget==opened&&scanHolder.acceptsScanTarget(opened))resolve(raw,opened)
            },onError={message->
                if(mounted&&currentHolder===scanHolder&&camera===opened&&currentCamera===opened&&currentTarget==opened&&scanHolder.acceptsScanTarget(opened)){
                    camera=null;failure=message.ifBlank{text(StaffStrings.STAFF_SCANNER_UNAVAILABLE)}
                }
            },enabled=enabled,sessionKey=opened to session)}
        }
    }
}

private class StaffCameraCapture(val target:StaffPhotoTarget,val file:File,val uri:Uri)

@Composable private fun StaffPhotoPicker(holder:StaffStateHolder,demo:Boolean,enabled:Boolean,ready:Boolean,photoRevision:Long){
    val text=rememberMobileText()
    val context=LocalContext.current;val work=rememberCoroutineScope()
    // Keep the launch ticket through recomposition/holder replacement; a late callback must reject
    // its original owner, rather than being re-armed against the currently visible handover.
    var pendingGallery by remember{mutableStateOf<StaffPhotoTarget?>(null)}
    var pendingPermission by remember{mutableStateOf<StaffPhotoTarget?>(null)}
    var pendingCamera by remember{mutableStateOf<StaffCameraCapture?>(null)}
    var preparing by remember{mutableStateOf<StaffPhotoTarget?>(null)}
    fun cleanup(uri:Uri,temporary:File?,camera:Boolean){
        if(camera)runCatching {context.revokeUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
        temporary?.delete()
    }
    fun cleanupCamera(value:StaffCameraCapture)=cleanup(value.uri,value.file,true)
    fun receive(uri:Uri,target:StaffPhotoTarget,temporary:File?=null,camera:Boolean=false){
        if(!holder.acceptsPhotoTarget(target)){cleanup(uri,temporary,camera);return}
        preparing=target
        work.launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { holder.receivePhoto(target,decode={withContext(Dispatchers.IO){StaffPhotoPreparation.jpeg(context,uri)}},cleanup={cleanup(uri,temporary,camera)}) }
            catch(c:CancellationException){throw c}
            catch(_:Exception){if(holder.acceptsPhotoTarget(target))holder.showError(text(StaffStrings.STAFF_THIS_PHOTO_COULD_NOT_BE_OPENED_CHOOSE_ANOTHER_IMAGE))}
            finally{if(preparing===target)preparing=null}
        }
    }
    val gallery=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->
        val target=pendingGallery;pendingGallery=null
        if(uri!=null&&target!=null)receive(uri,target)
    }
    val capture=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){ok->
        val pending=pendingCamera;pendingCamera=null
        if(pending!=null){
            if(ok&&holder.acceptsPhotoTarget(pending.target))try {
                receive(pending.uri,pending.target,pending.file,true)
            }catch(_:Exception){cleanupCamera(pending);if(holder.acceptsPhotoTarget(pending.target))holder.showError(text(StaffStrings.STAFF_CAMERA_CAPTURE_IS_UNAVAILABLE_CHOOSE_A_PHOTO_INSTEAD))}
            else cleanupCamera(pending)
        }
    }
    fun launchCamera(target:StaffPhotoTarget){
        if(!holder.acceptsPhotoTarget(target))return
        var created:File?=null
        try {
            val directory=File(context.cacheDir,"native-documents").apply{mkdirs()}
            directory.listFiles()?.filter{it.name.startsWith("staff-photo-")&&it.name.endsWith(".jpg")&&System.currentTimeMillis()-it.lastModified()>24*60*60*1000L}?.forEach{it.delete()}
            val file=File.createTempFile("staff-photo-",".jpg",directory);created=file
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.documents",file)
            pendingCamera=StaffCameraCapture(target,file,uri)
            capture.launch(uri)
        }catch(_:Exception){pendingCamera?.let(::cleanupCamera);pendingCamera=null;created?.delete();if(holder.acceptsPhotoTarget(target))holder.showError(text(StaffStrings.STAFF_CAMERA_CAPTURE_IS_UNAVAILABLE_CHOOSE_A_PHOTO_INSTEAD))}
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        val target=pendingPermission;pendingPermission=null
        if(target!=null&&holder.acceptsPhotoTarget(target)) {
            if(granted)launchCamera(target)
            else holder.showError(text(StaffStrings.STAFF_CAMERA_PERMISSION))
        }
    }
    DisposableEffect(Unit){onDispose{pendingGallery=null;pendingPermission=null;pendingCamera?.let(::cleanupCamera);pendingCamera=null}}
    val sourceBusy=preparing!=null||pendingGallery!=null||pendingCamera!=null||pendingPermission!=null
    if(demo&&holder.isDemo)BrandTextButton(text(StaffStrings.STAFF_USE_SAMPLE_EVIDENCE_PHOTO),{holder.useSamplePhoto()},enabled,Modifier.testTag("staff-demo-photo"))
    else {
        Text(text(StaffStrings.STAFF_THE_PHOTO_DOCUMENTS_THE_EQUIPMENT_CONDITION_AVOID_INCLUDING))
        BrandTextButton(text(StaffStrings.STAFF_TAKE_EVIDENCE_PHOTO),{
            val target=holder.photoTarget()
            if(target!=null){
                if(androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED)launchCamera(target)
                else {pendingPermission=target;try{permission.launch(android.Manifest.permission.CAMERA)}catch(_:Exception){pendingPermission=null;if(holder.acceptsPhotoTarget(target))holder.showError(text(StaffStrings.STAFF_CAMERA_PERMISSION))}}
            }
        },enabled&&!sourceBusy)
        BrandTextButton(text(StaffStrings.STAFF_CHOOSE_EVIDENCE_PHOTO),{
            val target=holder.photoTarget()
            if(target!=null){pendingGallery=target;try{gallery.launch("image/*")}catch(_:Exception){pendingGallery=null;if(holder.acceptsPhotoTarget(target))holder.showError(text(StaffStrings.STAFF_THIS_PHOTO_COULD_NOT_BE_OPENED_CHOOSE_ANOTHER_IMAGE))}}
        },enabled&&!sourceBusy)
    }
    if(preparing!=null)Text(text(StaffStrings.STAFF_PREPARING_PHOTO))
    val bitmap=remember(holder,ready,photoRevision){if(ready)holder.photoPreview()?.let{BitmapFactory.decodeByteArray(it,0,it.size)}else null}
    DisposableEffect(bitmap){onDispose{bitmap?.recycle()}}
    bitmap?.let{Image(it.asImageBitmap(),text(StaffStrings.STAFF_EVIDENCE_PHOTO_PREVIEW),Modifier.fillMaxWidth().heightIn(max=300.dp))}
}

/** Sanitized, bounded JPEG; decoding strips source metadata and honors source orientation. */
private object StaffPhotoPreparation {
    fun jpeg(context:Context,uri:Uri):ByteArray {
        val bitmap=if(Build.VERSION.SDK_INT>=28){
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,uri)){decoder,info,_->
                val scale=minOf(1.0,1600.0/maxOf(info.size.width,info.size.height));decoder.setTargetSize(maxOf(1,(info.size.width*scale).toInt()),maxOf(1,(info.size.height*scale).toInt()));decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }else{
            val options=BitmapFactory.Options().apply{inJustDecodeBounds=true};context.contentResolver.openInputStream(uri).use{BitmapFactory.decodeStream(it,null,options)}
            require(options.outWidth>0&&options.outHeight>0);var sample=1;while(maxOf(options.outWidth,options.outHeight)/sample>1600)sample*=2
            val source=context.contentResolver.openInputStream(uri).use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample})}?:error("Invalid image")
            val orientation=context.contentResolver.openInputStream(uri).use{input->if(input==null)ExifInterface.ORIENTATION_NORMAL else ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}
            val matrix=Matrix().apply{when(orientation){ExifInterface.ORIENTATION_ROTATE_90->postRotate(90f);ExifInterface.ORIENTATION_ROTATE_180->postRotate(180f);ExifInterface.ORIENTATION_ROTATE_270->postRotate(270f);ExifInterface.ORIENTATION_FLIP_HORIZONTAL->postScale(-1f,1f);ExifInterface.ORIENTATION_FLIP_VERTICAL->postScale(1f,-1f);ExifInterface.ORIENTATION_TRANSPOSE->{postRotate(90f);postScale(-1f,1f)};ExifInterface.ORIENTATION_TRANSVERSE->{postRotate(270f);postScale(-1f,1f)}}}
            if(matrix.isIdentity)source else Bitmap.createBitmap(source,0,0,source.width,source.height,matrix,true).also{if(it!==source)source.recycle()}
        }
        return try{ByteArrayOutputStream().use{out->require(bitmap.compress(Bitmap.CompressFormat.JPEG,85,out));out.toByteArray().also{require(it.size<=5*1024*1024)}}}finally{bitmap.recycle()}
    }
}

/** Stable workflow keys remain untranslated; only their visible labels use resources. */
@Composable private fun staffStep(key:String):String {val text=rememberMobileText();return text(when(key){
    "Container"->StaffStrings.STAFF_CONTAINER;"Borrower"->StaffStrings.STAFF_BORROWER
    "Equipment"->StaffStrings.STAFF_EQUIPMENT;"Returned items"->StaffStrings.STAFF_RETURNED_ITEMS
    "Photo and review"->StaffStrings.STAFF_PHOTO_REVIEW;else->StaffStrings.STAFF_RECEIPT
})}
@Composable private fun staffOutcome(value:StaffAssetOutcome):String {val text=rememberMobileText();return text(when(value){
    StaffAssetOutcome.PENDING->StaffStrings.STAFF_PENDING;StaffAssetOutcome.RETURNED->StaffStrings.STAFF_RETURNED
    StaffAssetOutcome.DAMAGED->StaffStrings.STAFF_DAMAGED;StaffAssetOutcome.MISSING->StaffStrings.STAFF_MISSING
})}
