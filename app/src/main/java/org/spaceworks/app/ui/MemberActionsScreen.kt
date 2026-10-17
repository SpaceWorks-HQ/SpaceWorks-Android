package org.spaceworks.app.ui

import org.spaceworks.app.network.retrySecondsRemaining

import org.spaceworks.app.platform.*

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.spaceworks.app.actions.*
import java.time.*
import java.time.format.DateTimeFormatter

val LocalMemberActions=staticCompositionLocalOf<MemberActionsStateHolder?> {null}
// Opening a route removes its source screen; reads belong to the surviving member shell.
val LocalMemberActionScope=staticCompositionLocalOf<kotlinx.coroutines.CoroutineScope?> {null}

@Composable private fun ActionCard(content:@Composable ColumnScope.()->Unit) {
    OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)}
}
@Composable private fun ActionChoice(label:String,selected:Boolean,onClick:()->Unit,enabled:Boolean=true) {
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(selected=selected,enabled=enabled,role=Role.RadioButton,onClick={focus.clearFocus();keyboard?.hide();onClick()}).padding(vertical=8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        RadioButton(selected=selected,onClick=null,enabled=enabled);Spacer(Modifier.width(12.dp));Text(label,Modifier.weight(1f))
    }
}
@Composable fun MemberActionsScreen(holder:MemberActionsStateHolder,demo:Boolean,onClose:()->Unit) {
    val copyText=rememberMobileText()

    val s by holder.state.collectAsStateWithLifecycle();val work=rememberCoroutineScope()
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    val scroll=rememberScrollState()
    LaunchedEffect(s.error){if(s.error!=null){focus.clearFocus();keyboard?.hide();scroll.scrollTo(0)}}
    var confirm by remember(s.route,s.scope){mutableStateOf<String?>(null)}
    fun review(value:String){focus.clearFocus();keyboard?.hide();confirm=value}
    var discard by remember {mutableStateOf(false)}
    var clock by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(s.retryAtMillis){do {clock=System.currentTimeMillis();if(clock<s.retryAtMillis)kotlinx.coroutines.delay(250)}while(clock<s.retryAtMillis)}
    val asyncLabel=if(s.busy)copyText(UiStrings.MOBILE_SAVING_YOUR_CHANGES) else copyText(UiStrings.MOBILE_LOADING_DETAILS)
    val enabled=!s.busy&&!s.loading&&clock>=s.retryAtMillis
    fun close(){if(s.busy)return;focus.clearFocus();keyboard?.hide();if(s.route==ActionRoute.PROFILE&&holder.profileDirty)discard=true else if(holder.close())onClose()}
    BackHandler {close()}
    ProtectSecrets(!demo && s.route==ActionRoute.REGISTRATION)
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text(copyText(UiStrings.MOBILE_DISCARD_CHANGES))},text={Text(copyText(UiStrings.MOBILE_YOUR_UNSAVED_PROFILE_EDITS_WILL_BE_LOST))},confirmButton={BrandTextButton(copyText(UiStrings.MOBILE_DISCARD),{discard=false;holder.close();onClose()})},dismissButton={BrandTextButton(copyText(UiStrings.MOBILE_KEEP_EDITING),{discard=false})})
    confirm?.let {action->AlertDialog(onDismissRequest={if(!s.busy)confirm=null},title={Text(when(action){"renew"->copyText(UiStrings.MOBILE_RENEW_THIS_LOAN);"cancelBooking"->copyText(UiStrings.MOBILE_CANCEL_THIS_BOOKING);"reschedule"->copyText(UiStrings.MOBILE_CHANGE_THIS_BOOKING);"cancelRegistration"->copyText(UiStrings.MOBILE_CANCEL_THIS_REGISTRATION);else->copyText(UiStrings.MOBILE_SUBMIT_YOUR_REQUEST)})},text={Text(when(action){"renew"->copyText(UiStrings.MOBILE_YOUR_MAKERSPACE_WILL_DECIDE_THE_NEW_DUE_DATE_AND_WHETHER_RENEWAL);"cancelBooking"->copyText(UiStrings.MOBILE_YOUR_MAKERSPACE_S_CANCELLATION_RULES_APPLY_THIS_CANNOT_BE_UNDONE);"reschedule"->copyText(UiStrings.MOBILE_THE_SERVER_WILL_CHECK_THE_NEW_TIME_BEFORE_CHANGING_YOUR_BOOKING);"cancelRegistration"->copyText(UiStrings.MOBILE_YOUR_PLACE_MAY_BE_OFFERED_TO_SOMEONE_ELSE_CANCELLATION_RULES_APP);else->copyText(UiStrings.MOBILE_CHECK_YOUR_ITEMS_AND_QUANTITIES_YOUR_MAKERSPACE_WILL_REVIEW_THE)})},confirmButton={BrandTextButton(copyText(UiStrings.MOBILE_CONFIRM),{confirm=null;work.launch {when(action){"renew"->holder.renew();"cancelBooking"->holder.cancelBooking();"reschedule"->holder.reschedule();"cancelRegistration"->holder.cancelRegistration();else->holder.submitRequest()}}},enabled)},dismissButton={BrandTextButton(when(action){"cancelBooking"->copyText(UiStrings.MOBILE_KEEP_BOOKING);"cancelRegistration"->copyText(UiStrings.MOBILE_KEEP_REGISTRATION);else->copyText(UiStrings.MOBILE_KEEP_REVIEWING)},{confirm=null},!s.busy)})}
    Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.TopCenter) {
        Column(Modifier.widthIn(max=640.dp).fillMaxWidth().imePadding().verticalScroll(scroll).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            BrandTextButton(copyText(UiStrings.MOBILE_BACK),{close()},!s.busy)
            Text(when(s.route){ActionRoute.CART->copyText(UiStrings.MOBILE_REVIEW_YOUR_REQUEST);ActionRoute.REQUEST_RECEIPT->copyText(UiStrings.MOBILE_YOUR_REQUEST);ActionRoute.RENEW->copyText(UiStrings.MOBILE_RENEW_LOAN);ActionRoute.SPACE->copyText(UiStrings.MOBILE_BOOK_A_SPACE);ActionRoute.BOOKING->copyText(UiStrings.MOBILE_MANAGE_BOOKING);ActionRoute.EVENT->copyText(UiStrings.MOBILE_REGISTER_FOR_AN_EVENT);ActionRoute.REGISTRATION->copyText(UiStrings.MOBILE_YOUR_REGISTRATION);ActionRoute.PROFILE->copyText(UiStrings.MOBILE_EDIT_PROFILE);ActionRoute.PREFERENCES->copyText(UiStrings.MOBILE_COMMUNICATION_PREFERENCES);null->copyText(UiStrings.MOBILE_YOUR_MAKERSPACE)},style=MaterialTheme.typography.headlineLarge,modifier=Modifier.semantics {heading()})
            Text(makerspaceLabel(s.scope?.slug.orEmpty()),color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(s.busy||s.loading)LinearProgressIndicator(Modifier.fillMaxWidth().semantics {contentDescription=asyncLabel})
            if(clock<s.retryAtMillis)Text(copyText(UiStrings.MOBILE_TRY_AGAIN_IN_VALUES,retrySecondsRemaining(clock,s.retryAtMillis)),Modifier.semantics {liveRegion=LiveRegionMode.Polite})
            BrandError(s.error)
            s.receipt?.let {receipt->ActionCard {
                Text(receipt.title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                Text(memberStatus(receipt.status,copyText),style=MaterialTheme.typography.titleMedium);Text(receipt.message);receipt.dueAt?.let {Text(copyText(UiStrings.MOBILE_DUE_VALUE,memberDate(it)),style=MaterialTheme.typography.titleMedium)}
                if(s.route==ActionRoute.REQUEST_RECEIPT){receipt.publicToken?.let {Text(copyText(UiStrings.MOBILE_REFERENCE_VALUE,it),style=MaterialTheme.typography.bodySmall)};BrandTextButton(copyText(UiStrings.MOBILE_REFRESH_REQUEST_STATUS),{work.launch {holder.refreshRequest()}},enabled);s.requestStatus?.items?.forEach {Text(copyText(UiStrings.MOBILE_VALUE_VALUE,it.quantity,it.name))}}
                BrandButton(copyText(UiStrings.MOBILE_DONE),{if(holder.close())onClose()},!s.busy)
            }}
            if(s.receipt==null)when(s.route) {
                ActionRoute.CART->{
                    Text(copyText(UiStrings.MOBILE_CHOOSE_WHAT_YOU_NEED_AND_TELL_THE_TEAM_ABOUT_YOUR_PROJECT_AVAILA))
                    if(s.cart.isEmpty())Text(copyText(UiStrings.MOBILE_YOUR_REQUEST_IS_EMPTY_BROWSE_INVENTORY_TO_ADD_AN_ITEM))
                    s.cart.forEach {line->ActionCard {Text(line.item.name,style=MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){BrandTextButton("−",{holder.quantity(line.item.id,line.quantity-1)},enabled,Modifier.semantics {contentDescription=copyText(UiStrings.MOBILE_REMOVE_ONE_VALUE,line.item.name)});Text("${line.quantity}",Modifier.padding(12.dp).semantics {contentDescription=copyText(UiStrings.MOBILE_QUANTITY_VALUE,line.quantity)});BrandTextButton("+",{holder.quantity(line.item.id,line.quantity+1)},enabled&&line.quantity<99,Modifier.semantics {contentDescription=copyText(UiStrings.MOBILE_ADD_ONE_VALUE,line.item.name)})}
                        BrandTextButton(copyText(UiStrings.MOBILE_REMOVE_ITEM),{holder.quantity(line.item.id,0)},enabled)
                    }}
                    OutlinedTextField(value=s.purpose,onValueChange=holder::purpose,label={Text(copyText(UiStrings.MOBILE_WHAT_ARE_YOU_MAKING_OPTIONAL))},enabled=enabled,minLines=3,maxLines=8,modifier=Modifier.fillMaxWidth(),supportingText={Text("${s.purpose.length}/500")},colors=OutlinedTextFieldDefaults.colors(focusedLabelColor=MaterialTheme.colorScheme.onPrimaryContainer,focusedBorderColor=MaterialTheme.colorScheme.onPrimaryContainer))
                    BrandButton(copyText(UiStrings.MOBILE_SUBMIT_REQUEST),{review("submit")},enabled&&s.cart.isNotEmpty())
                }
                ActionRoute.RENEW->s.loan?.let {loan->ActionCard {Text(loan.label,style=MaterialTheme.typography.titleLarge);Text(loan.dueAt?.let {copyText(UiStrings.MOBILE_DUE_VALUE,memberDate(it))}?:copyText(UiStrings.MOBILE_DUE_DATE_NOT_PROVIDED));Text(copyText(UiStrings.MOBILE_RENEWED_VALUE_TIMES,loan.renewalCount));if(!loan.canRenew)BrandError(loan.renewalRefusal?:copyText(UiStrings.MOBILE_THIS_LOAN_CANNOT_BE_RENEWED));BrandButton(copyText(UiStrings.MOBILE_CONFIRM_RENEWAL),{review("renew")},enabled&&loan.canRenew)}}
                ActionRoute.SPACE->s.space?.let {space->
                    Text(space.space.name,style=MaterialTheme.typography.titleLarge);Text(space.space.description);Text(copyText(UiStrings.MOBILE_VALUE_CAPACITY_VALUE,space.space.location,space.space.capacity))
                    ActionTimes(s.startsAt,s.endsAt,enabled,holder::times)
                    BrandButton(copyText(UiStrings.MOBILE_CHECK_AVAILABILITY),{work.launch {holder.checkAvailability()}},enabled)
                    s.availability?.let {availability->ActionCard {
                        Text(copyText(UiStrings.MOBILE_AVAILABILITY),style=MaterialTheme.typography.titleLarge);Text(copyText(UiStrings.MOBILE_TIMES_SHOWN_IN_VALUE,availability.timezone))
                        if(availability.opening.isEmpty())Text(copyText(UiStrings.MOBILE_NO_OPENING_WINDOWS_WERE_RETURNED_FOR_THIS_PERIOD)) else availability.opening.forEach {Text(copyText(UiStrings.MOBILE_OPEN_VALUE_VALUE,memberDate(it.startsAt,availability.timezone),memberDate(it.endsAt,availability.timezone)))}
                        val booked=availability.booked
                        if(booked==null)Text(copyText(UiStrings.MOBILE_OTHER_BOOKINGS_ARE_NOT_SHARED_YOUR_MAKERSPACE_WILL_CONFIRM_THIS)) else if(booked.isEmpty())Text(copyText(UiStrings.MOBILE_NO_OVERLAPPING_BOOKINGS_WERE_RETURNED_FINAL_AVAILABILITY_IS_CHEC)) else booked.forEach {Text(copyText(UiStrings.MOBILE_BOOKED_VALUE_VALUE,memberDate(it.startsAt,availability.timezone),memberDate(it.endsAt,availability.timezone)))}
                    }}
                    ActionQuestions(space.form,s.answers,s.fieldErrors,enabled,holder::answer)
                    Text(if(space.space.approvalMode=="instant")copyText(UiStrings.MOBILE_THE_SERVER_WILL_CONFIRM_YOUR_BOOKING_STATUS) else copyText(UiStrings.MOBILE_YOUR_BOOKING_MAY_NEED_MAKERSPACE_APPROVAL))
                    BrandButton(copyText(UiStrings.MOBILE_SUBMIT_BOOKING_REQUEST),{focus.clearFocus();keyboard?.hide();work.launch {holder.book()}},enabled&&s.availability!=null&&space.form.supported)
                }
                ActionRoute.BOOKING->s.booking?.let {booking->
                    Text(booking.spaceName,style=MaterialTheme.typography.titleLarge);Text(memberStatus(booking.status,copyText));Text(copyText(UiStrings.MOBILE_VALUE_VALUE_7BC5F0,memberDate(booking.startsAt,booking.timezone),memberDate(booking.endsAt,booking.timezone)))
                    Text(copyText(UiStrings.MOBILE_TIMEZONE_VALUE,booking.timezone))
                    booking.cancellationCutoffAt?.let {Text(copyText(UiStrings.MOBILE_CHANGES_CLOSE_VALUE,memberDate(it,booking.timezone)))}
                    if(booking.canCancel){ActionTimes(s.startsAt,s.endsAt,enabled,holder::times);BrandButton(copyText(UiStrings.MOBILE_RESCHEDULE_BOOKING),{review("reschedule")},enabled);BrandTextButton(copyText(UiStrings.MOBILE_CANCEL_BOOKING),{review("cancelBooking")},enabled)}else Text(copyText(UiStrings.MOBILE_THIS_BOOKING_CANNOT_BE_CHANGED_HERE_ASK_YOUR_MAKERSPACE_FOR_HELP))
                }
                ActionRoute.EVENT->s.event?.let {event->
                    Text(event.event.title,style=MaterialTheme.typography.titleLarge);Text(event.event.description);Text(copyText(UiStrings.MOBILE_VALUE_VALUE_7BC5F0,memberDate(event.event.startsAt),memberDate(event.event.endsAt)));Text(event.event.location);Text(event.event.availability)
                    Text(if(event.event.paymentAmount.toBigDecimalOrNull()?.signum()==0)copyText(UiStrings.MOBILE_FREE_EVENT) else copyText(UiStrings.MOBILE_VALUE_VALUE_78C505,event.event.currency,event.event.paymentAmount))
                    if(event.tickets.isNotEmpty())ActionCard {Text(copyText(UiStrings.MOBILE_TICKET_TYPE),style=MaterialTheme.typography.titleLarge);event.tickets.forEach {ticket->ActionChoice(copyText(UiStrings.MOBILE_VALUE_VALUE_VALUE,ticket.name,event.event.currency,ticket.amount)+(ticket.spotsLeft?.let {copyText(UiStrings.MOBILE_VALUE_LEFT,it)}?:""),s.ticketId==ticket.id,{holder.ticket(ticket.id)},enabled&&(ticket.spotsLeft?.let {it>0}?:true))}}
                    BrandField(s.phone,{holder.registrationFields(phone=it)},copyText(UiStrings.MOBILE_PHONE_OPTIONAL),enabled=enabled,role=FieldRole.PHONE)
                    BrandField(s.email,{holder.registrationFields(email=it)},copyText(UiStrings.MOBILE_EMAIL_OPTIONAL),enabled=enabled,role=FieldRole.EMAIL)
                    if(event.audience=="invite")BrandField(s.inviteCode,{holder.registrationFields(invite=it)},copyText(UiStrings.MOBILE_INVITATION_CODE),enabled=enabled)
                    if(event.promoEnabled)BrandField(s.promoCode,{holder.registrationFields(promo=it)},copyText(UiStrings.MOBILE_PROMO_CODE_OPTIONAL),enabled=enabled)
                    ActionQuestions(event.form,s.answers,s.fieldErrors,enabled,holder::answer)
                    Text(if(event.event.requiresApproval)copyText(UiStrings.MOBILE_REGISTRATION_MAY_NEED_APPROVAL_A_QR_TICKET_APPEARS_ONLY_WHEN_CON) else copyText(UiStrings.MOBILE_YOUR_REGISTRATION_STATUS_WILL_BE_CONFIRMED_BY_THE_SERVER))
                    BrandButton(copyText(UiStrings.MOBILE_CONFIRM_REGISTRATION),{focus.clearFocus();keyboard?.hide();work.launch {holder.register()}},enabled&&event.event.registrationOpen&&event.form.supported)
                }
                ActionRoute.REGISTRATION->s.registration?.let {registration->
                    Text(registration.title,style=MaterialTheme.typography.titleLarge);Text(memberStatus(registration.status,copyText));Text(memberDate(registration.startsAt));registration.waitlistPosition?.let {Text(copyText(UiStrings.MOBILE_WAITLIST_POSITION_VALUE,it))}
                    if(registration.status=="registered"){BrandButton(copyText(UiStrings.MOBILE_SHOW_QR_TICKET),{work.launch {holder.ticketQR()}},enabled);s.qr?.let {bytes->val bitmap=remember(bytes){runCatching {org.spaceworks.app.actions.SafeTicketSvg.render(bytes)}.getOrNull()};if(bitmap==null)BrandError(copyText(UiStrings.MOBILE_THIS_TICKET_COULDN_T_BE_DISPLAYED_REFRESH_OR_ASK_THE_EVENT_TEAM))else Image(bitmap.asImageBitmap(),copyText(UiStrings.MOBILE_YOUR_EVENT_REGISTRATION_QR_TICKET),Modifier.fillMaxWidth().heightIn(max=360.dp))}}else Text(copyText(UiStrings.MOBILE_A_QR_TICKET_IS_AVAILABLE_ONLY_AFTER_REGISTRATION_IS_CONFIRMED))
                    registration.cancellationCutoffAt?.let {Text(copyText(UiStrings.MOBILE_CANCELLATION_CLOSES_VALUE,memberDate(it)))}
                    if(registration.canCancel)BrandTextButton(copyText(UiStrings.MOBILE_CANCEL_REGISTRATION),{review("cancelRegistration")},enabled)
                }
                ActionRoute.PROFILE->s.profile?.let {profile->
                    Text(copyText(UiStrings.MOBILE_YOUR_ACCOUNT_CONTACT_DETAILS_UPDATING_A_PHONE_HERE_DOES_NOT_VERI))
                    BrandField(profile.displayName.orEmpty(),{holder.editProfileField(ContactField.DISPLAY_NAME,it)},copyText(UiStrings.MOBILE_DISPLAY_NAME),enabled=enabled,role=FieldRole.NAME,errorMessage=s.fieldErrors["displayName"])
                    BrandField(profile.firstName.orEmpty(),{holder.editProfileField(ContactField.FIRST_NAME,it)},copyText(UiStrings.MOBILE_FIRST_NAME),enabled=enabled,role=FieldRole.NAME,errorMessage=s.fieldErrors["firstName"])
                    BrandField(profile.lastName.orEmpty(),{holder.editProfileField(ContactField.LAST_NAME,it)},copyText(UiStrings.MOBILE_LAST_NAME),enabled=enabled,role=FieldRole.NAME,errorMessage=s.fieldErrors["lastName"])
                    BrandField(profile.phone.orEmpty(),{holder.editProfileField(ContactField.PHONE,it)},copyText(UiStrings.MOBILE_PHONE),enabled=enabled,role=FieldRole.PHONE,errorMessage=s.fieldErrors["phone"])
                    BrandButton(copyText(UiStrings.MOBILE_SAVE_CHANGES),{focus.clearFocus();keyboard?.hide();work.launch {holder.saveProfile()}},enabled&&holder.profileDirty)
                }
                ActionRoute.PREFERENCES->s.preferences?.let {cells->
                    Text(copyText(UiStrings.MOBILE_CHOOSE_OPTIONAL_MESSAGES_FROM_THIS_MAKERSPACE_DEVICE_NOTIFICATIO))
                    if(cells.isEmpty())Text(copyText(UiStrings.MOBILE_NO_COMMUNICATION_PREFERENCES_ARE_AVAILABLE))
                    cells.forEach {cell->ActionCard {
                        Text(memberStatus(cell.feature,copyText),style=MaterialTheme.typography.titleMedium);Text(memberStatus(cell.channel,copyText))
                        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text(if(cell.available)copyText(UiStrings.MOBILE_OPTIONAL_MESSAGES) else copyText(UiStrings.MOBILE_UNAVAILABLE_FOR_THIS_MAKERSPACE),Modifier.weight(1f));Switch(checked=cell.enabled,onCheckedChange={value->work.launch {holder.preference(cell,value)}},enabled=enabled&&(cell.available||cell.enabled),modifier=Modifier.semantics {contentDescription=copyText(UiStrings.MOBILE_VALUE_VALUE_OPTIONAL_MESSAGES,memberStatus(cell.feature,copyText),memberStatus(cell.channel,copyText))})}
                        if(cell.mandatoryEvents.isNotEmpty()){Text(copyText(UiStrings.MOBILE_ALWAYS_ON_SAFETY_MESSAGES),style=MaterialTheme.typography.labelLarge);cell.mandatoryEvents.forEach {Text(memberStatus(it,copyText))};Text(copyText(UiStrings.MOBILE_THESE_MESSAGES_CONTINUE_EVEN_WHEN_OPTIONAL_MESSAGES_ARE_OFF))}
                        if(s.capabilities.value?.dailyDigest==true&&cell.channel=="email"&&cell.dailyDigest!=null)Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text(copyText(UiStrings.MOBILE_DAILY_EMAIL_DIGEST),Modifier.weight(1f));Switch(checked=requireNotNull(cell.dailyDigest),onCheckedChange={digest->work.launch {holder.preference(cell,cell.enabled,digest)}},enabled=enabled&&cell.enabled&&cell.available,modifier=Modifier.semantics {contentDescription=copyText(UiStrings.MOBILE_VALUE_DAILY_EMAIL_DIGEST,memberStatus(cell.feature,copyText))})}
                    }}
                }
                else->Unit
            }
            if(s.error!=null&&!s.busy&&!s.loading)when(s.route){ActionRoute.SPACE->BrandTextButton(copyText(UiStrings.MOBILE_TRY_AGAIN),{s.referenceId?.let {work.launch {holder.openSpace(it)}}},enabled);ActionRoute.EVENT->BrandTextButton(copyText(UiStrings.MOBILE_TRY_AGAIN),{s.referenceId?.let {work.launch {holder.openEvent(it)}}},enabled);ActionRoute.PROFILE->if(s.profile==null)BrandTextButton(copyText(UiStrings.MOBILE_TRY_AGAIN),{work.launch {holder.openProfile()}},enabled);ActionRoute.PREFERENCES->if(s.preferences==null)BrandTextButton(copyText(UiStrings.MOBILE_TRY_AGAIN),{work.launch {holder.openPreferences()}},enabled);else->Unit}
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable private fun ActionTimes(start:String,end:String,enabled:Boolean,onChange:(String,String)->Unit) {
    val copyText=rememberMobileText()

    val context=LocalContext.current
    fun parsed(value:String,fallbackHour:Int)=runCatching {OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault())}.getOrElse {ZonedDateTime.now().plusDays(1).withHour(fallbackHour).withMinute(0).withSecond(0).withNano(0)}
    fun choose(isStart:Boolean){val current=parsed(if(isStart)start else end,if(isStart)10 else 12)
        DatePickerDialog(context,{_,year,month,day->TimePickerDialog(context,{_,hour,minute->val value=LocalDateTime.of(year,month+1,day,hour,minute).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();if(isStart)onChange(value,end)else onChange(start,value)},current.hour,current.minute,android.text.format.DateFormat.is24HourFormat(context)).show()},current.year,current.monthValue-1,current.dayOfMonth).show()}
    LaunchedEffect(Unit){if(start.isEmpty()&&end.isEmpty())onChange(parsed(start,10).toOffsetDateTime().toString(),parsed(end,12).toOffsetDateTime().toString())}
    ActionCard {Text(copyText(UiStrings.MOBILE_CHOOSE_YOUR_TIME),style=MaterialTheme.typography.titleLarge);Text(copyText(UiStrings.MOBILE_TIMES_SHOWN_IN_VALUE,ZoneId.systemDefault().id))
        BrandTextButton(copyText(UiStrings.MOBILE_START_VALUE,if(start.isBlank())copyText(UiStrings.MOBILE_CHOOSE_START) else memberDate(start)),{choose(true)},enabled)
        BrandTextButton(copyText(UiStrings.MOBILE_END_VALUE,if(end.isBlank())copyText(UiStrings.MOBILE_CHOOSE_END) else memberDate(end)),{choose(false)},enabled)}
}
@Composable private fun ActionQuestions(form:ActionForm,answers:Map<String,JsonElement>,errors:Map<String,String>,enabled:Boolean,onAnswer:(String,JsonElement)->Unit) {
    val copyText=rememberMobileText()

    if(!form.supported){BrandError(copyText(UiStrings.MOBILE_THIS_FORM_ISN_T_SUPPORTED_BY_THIS_APP_VERSION_ASK_YOUR_MAKERSPAC));return}
    if(form.questions.isNotEmpty())Text(copyText(UiStrings.MOBILE_A_FEW_MORE_DETAILS),style=MaterialTheme.typography.titleLarge)
    form.questions.forEach {question->val raw=answers[question.id];val primitive=raw as? JsonPrimitive;val label=question.label+if(question.required)copyText(UiStrings.MOBILE_REQUIRED) else copyText(UiStrings.MOBILE_OPTIONAL)
        when(question.type){
            "single_choice","dropdown"->ActionCard {Text(label);question.options.forEach {option->ActionChoice(option,primitive?.contentOrNull==option,{onAnswer(question.id,JsonPrimitive(option))},enabled)}}
            "multi_choice"->ActionCard {Text(label);val choices=(raw as? JsonArray)?.mapNotNull {(it as? JsonPrimitive)?.contentOrNull}.orEmpty();question.options.forEach {option->Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(option in choices,enabled,Role.Checkbox){checked->onAnswer(question.id,JsonArray((if(checked)choices+option else choices-option).map(::JsonPrimitive)))},verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(option in choices,null,enabled=enabled);Spacer(Modifier.width(12.dp));Text(option)}}}
            "yes_no"->ActionCard {Text(label);ActionChoice(copyText(UiStrings.MOBILE_YES),primitive?.booleanOrNull==true,{onAnswer(question.id,JsonPrimitive(true))},enabled);ActionChoice(copyText(UiStrings.MOBILE_NO),primitive?.booleanOrNull==false,{onAnswer(question.id,JsonPrimitive(false))},enabled)}
            "paragraph"->OutlinedTextField(value=primitive?.contentOrNull.orEmpty(),onValueChange={onAnswer(question.id,JsonPrimitive(it.take(5000)))},label={Text(label)},minLines=3,maxLines=8,enabled=enabled,isError=errors[question.id]!=null,modifier=Modifier.fillMaxWidth(),colors=OutlinedTextFieldDefaults.colors(focusedLabelColor=MaterialTheme.colorScheme.onPrimaryContainer,focusedBorderColor=MaterialTheme.colorScheme.onPrimaryContainer))
            else->BrandField(primitive?.contentOrNull.orEmpty(),{onAnswer(question.id,JsonPrimitive(it.take(500)))},if(question.type=="date")copyText(UiStrings.MOBILE_VALUE_YYYY_MM_DD,label) else label,enabled=enabled,role=if(question.type=="number")FieldRole.NUMBER else FieldRole.TEXT,errorMessage=errors[question.id])
        }
        if(question.type in listOf("paragraph","single_choice","dropdown","multi_choice","yes_no"))BrandError(errors[question.id])
    }
}
