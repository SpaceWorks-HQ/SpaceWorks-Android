package org.spaceworks.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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

@Composable private fun ActionCard(content:@Composable ColumnScope.()->Unit) {
    OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)}
}
@Composable private fun ActionChoice(label:String,selected:Boolean,onClick:()->Unit,enabled:Boolean=true) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=selected,enabled=enabled,role=Role.RadioButton,onValueChange={onClick()}).padding(vertical=8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        RadioButton(selected=selected,onClick=null,enabled=enabled);Spacer(Modifier.width(12.dp));Text(label,Modifier.weight(1f))
    }
}
@Composable fun MemberActionsScreen(holder:MemberActionsStateHolder,demo:Boolean,onClose:()->Unit) {
    val s by holder.state.collectAsStateWithLifecycle();val work=rememberCoroutineScope()
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    val scroll=rememberScrollState()
    LaunchedEffect(s.error){if(s.error!=null){focus.clearFocus();keyboard?.hide();scroll.scrollTo(0)}}
    var confirm by remember(s.route,s.scope){mutableStateOf<String?>(null)}
    fun review(value:String){focus.clearFocus();keyboard?.hide();confirm=value}
    var discard by remember {mutableStateOf(false)}
    var clock by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(s.retryAtMillis){do {clock=System.currentTimeMillis();if(clock<s.retryAtMillis)kotlinx.coroutines.delay(250)}while(clock<s.retryAtMillis)}
    val enabled=!s.busy&&!s.loading&&clock>=s.retryAtMillis
    fun close(){if(s.busy)return;if(s.route==ActionRoute.PROFILE&&holder.profileDirty)discard=true else if(holder.close())onClose()}
    BackHandler {close()}
    ProtectSecrets(!demo && s.route==ActionRoute.REGISTRATION)
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("Discard changes?")},text={Text("Your unsaved profile edits will be lost.")},confirmButton={BrandTextButton("Discard",{discard=false;holder.close();onClose()})},dismissButton={BrandTextButton("Keep editing",{discard=false})})
    confirm?.let {action->AlertDialog(onDismissRequest={if(!s.busy)confirm=null},title={Text(when(action){"renew"->"Renew this loan?";"cancelBooking"->"Cancel this booking?";"reschedule"->"Change this booking?";"cancelRegistration"->"Cancel this registration?";else->"Submit your request?"})},text={Text(when(action){"renew"->"Your makerspace will decide the new due date and whether renewal is allowed.";"cancelBooking"->"Your makerspace's cancellation rules apply. This cannot be undone here.";"reschedule"->"The server will check the new time before changing your booking.";"cancelRegistration"->"Your place may be offered to someone else. Cancellation rules apply.";else->"Check your items and quantities. Your makerspace will review the request before handover."})},confirmButton={BrandTextButton("Confirm",{confirm=null;work.launch {when(action){"renew"->holder.renew();"cancelBooking"->holder.cancelBooking();"reschedule"->holder.reschedule();"cancelRegistration"->holder.cancelRegistration();else->holder.submitRequest()}}},enabled)},dismissButton={BrandTextButton(when(action){"cancelBooking"->"Keep booking";"cancelRegistration"->"Keep registration";else->"Keep reviewing"},{confirm=null},!s.busy)})}
    Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.TopCenter) {
        Column(Modifier.widthIn(max=640.dp).fillMaxWidth().imePadding().verticalScroll(scroll).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            BrandTextButton("Back",{close()},!s.busy)
            Text(when(s.route){ActionRoute.CART->"Review your request";ActionRoute.REQUEST_RECEIPT->"Your request";ActionRoute.RENEW->"Renew loan";ActionRoute.SPACE->"Book a space";ActionRoute.BOOKING->"Manage booking";ActionRoute.EVENT->"Register for an event";ActionRoute.REGISTRATION->"Your registration";ActionRoute.PROFILE->"Edit profile";ActionRoute.PREFERENCES->"Communication preferences";null->"Your makerspace"},style=MaterialTheme.typography.headlineLarge,modifier=Modifier.semantics {heading()})
            Text(makerspaceLabel(s.scope?.slug.orEmpty()),color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(s.busy||s.loading)LinearProgressIndicator(Modifier.fillMaxWidth().semantics {contentDescription=if(s.busy)"Saving your changes" else "Loading details"})
            if(clock<s.retryAtMillis)Text("Try again in ${((s.retryAtMillis-clock+999)/1000).coerceAtLeast(0)}s.",Modifier.semantics {liveRegion=LiveRegionMode.Polite})
            BrandError(s.error)
            s.receipt?.let {receipt->ActionCard {
                Text(receipt.title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                Text(memberStatus(receipt.status),style=MaterialTheme.typography.titleMedium);Text(receipt.message);receipt.dueAt?.let {Text("Due ${memberDate(it)}",style=MaterialTheme.typography.titleMedium)}
                if(s.route==ActionRoute.REQUEST_RECEIPT){receipt.publicToken?.let {Text("Reference: $it",style=MaterialTheme.typography.bodySmall)};BrandTextButton("Refresh request status",{work.launch {holder.refreshRequest()}},enabled);s.requestStatus?.items?.forEach {Text("${it.quantity} × ${it.name}")}}
                BrandButton("Done",{if(holder.close())onClose()},!s.busy)
            }}
            if(s.receipt==null)when(s.route) {
                ActionRoute.CART->{
                    Text("Choose what you need and tell the team about your project. Availability will be checked by your makerspace.")
                    if(s.cart.isEmpty())Text("Your request is empty. Browse inventory to add an item.")
                    s.cart.forEach {line->ActionCard {Text(line.item.name,style=MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){BrandTextButton("−",{holder.quantity(line.item.id,line.quantity-1)},enabled,Modifier.semantics {contentDescription="Remove one ${line.item.name}"});Text("${line.quantity}",Modifier.padding(12.dp).semantics {contentDescription="Quantity ${line.quantity}"});BrandTextButton("+",{holder.quantity(line.item.id,line.quantity+1)},enabled&&line.quantity<99,Modifier.semantics {contentDescription="Add one ${line.item.name}"})}
                        BrandTextButton("Remove item",{holder.quantity(line.item.id,0)},enabled)
                    }}
                    OutlinedTextField(value=s.purpose,onValueChange=holder::purpose,label={Text("What are you making? (optional)")},enabled=enabled,minLines=3,maxLines=8,modifier=Modifier.fillMaxWidth(),supportingText={Text("${s.purpose.length}/500")},colors=OutlinedTextFieldDefaults.colors(focusedLabelColor=MaterialTheme.colorScheme.onPrimaryContainer,focusedBorderColor=MaterialTheme.colorScheme.onPrimaryContainer))
                    BrandButton("Submit request",{review("submit")},enabled&&s.cart.isNotEmpty())
                }
                ActionRoute.RENEW->s.loan?.let {loan->ActionCard {Text(loan.label,style=MaterialTheme.typography.titleLarge);Text(loan.dueAt?.let {"Due ${memberDate(it)}"}?:"Due date not provided");Text("Renewed ${loan.renewalCount} times");if(!loan.canRenew)BrandError(loan.renewalRefusal?:"This loan cannot be renewed.");BrandButton("Confirm renewal",{review("renew")},enabled&&loan.canRenew)}}
                ActionRoute.SPACE->s.space?.let {space->
                    Text(space.space.name,style=MaterialTheme.typography.titleLarge);Text(space.space.description);Text("${space.space.location} · Capacity ${space.space.capacity}")
                    ActionTimes(s.startsAt,s.endsAt,enabled,holder::times)
                    BrandButton("Check availability",{work.launch {holder.checkAvailability()}},enabled)
                    s.availability?.let {availability->ActionCard {
                        Text("Availability",style=MaterialTheme.typography.titleLarge);Text("Times shown in ${availability.timezone}.")
                        if(availability.opening.isEmpty())Text("No opening windows were returned for this period.") else availability.opening.forEach {Text("Open ${memberDate(it.startsAt,availability.timezone)} – ${memberDate(it.endsAt,availability.timezone)}")}
                        val booked=availability.booked
                        if(booked==null)Text("Other bookings are not shared. Your makerspace will confirm this time.") else if(booked.isEmpty())Text("No overlapping bookings were returned. Final availability is checked when you book.") else booked.forEach {Text("Booked ${memberDate(it.startsAt,availability.timezone)} – ${memberDate(it.endsAt,availability.timezone)}")}
                    }}
                    ActionQuestions(space.form,s.answers,s.fieldErrors,enabled,holder::answer)
                    Text(if(space.space.approvalMode=="instant")"The server will confirm your booking status." else "Your booking may need makerspace approval.")
                    BrandButton("Submit booking request",{focus.clearFocus();keyboard?.hide();work.launch {holder.book()}},enabled&&s.availability!=null&&space.form.supported)
                }
                ActionRoute.BOOKING->s.booking?.let {booking->
                    Text(booking.spaceName,style=MaterialTheme.typography.titleLarge);Text(memberStatus(booking.status));Text("${memberDate(booking.startsAt,booking.timezone)} – ${memberDate(booking.endsAt,booking.timezone)}")
                    Text("Timezone: ${booking.timezone}")
                    booking.cancellationCutoffAt?.let {Text("Changes close ${memberDate(it,booking.timezone)}.")}
                    if(booking.canCancel){ActionTimes(s.startsAt,s.endsAt,enabled,holder::times);BrandButton("Reschedule booking",{review("reschedule")},enabled);BrandTextButton("Cancel booking",{review("cancelBooking")},enabled)}else Text("This booking cannot be changed here. Ask your makerspace for help.")
                }
                ActionRoute.EVENT->s.event?.let {event->
                    Text(event.event.title,style=MaterialTheme.typography.titleLarge);Text(event.event.description);Text("${memberDate(event.event.startsAt)} – ${memberDate(event.event.endsAt)}");Text(event.event.location);Text(event.event.availability)
                    Text(if(event.event.paymentAmount.toBigDecimalOrNull()?.signum()==0)"Free event" else "${event.event.currency} ${event.event.paymentAmount}")
                    if(event.tickets.isNotEmpty())ActionCard {Text("Ticket type",style=MaterialTheme.typography.titleLarge);event.tickets.forEach {ticket->ActionChoice("${ticket.name} · ${event.event.currency} ${ticket.amount}"+(ticket.spotsLeft?.let {" · $it left"}?:""),s.ticketId==ticket.id,{holder.ticket(ticket.id)},enabled&&(ticket.spotsLeft==null||ticket.spotsLeft>0))}}
                    BrandField(s.phone,{holder.registrationFields(phone=it)},"Phone (optional)",enabled=enabled,role=FieldRole.PHONE)
                    BrandField(s.email,{holder.registrationFields(email=it)},"Email (optional)",enabled=enabled,role=FieldRole.EMAIL)
                    if(event.audience=="invite")BrandField(s.inviteCode,{holder.registrationFields(invite=it)},"Invitation code",enabled=enabled)
                    if(event.promoEnabled)BrandField(s.promoCode,{holder.registrationFields(promo=it)},"Promo code (optional)",enabled=enabled)
                    ActionQuestions(event.form,s.answers,s.fieldErrors,enabled,holder::answer)
                    Text(if(event.event.requiresApproval)"Registration may need approval. A QR ticket appears only when confirmed." else "Your registration status will be confirmed by the server.")
                    BrandButton("Confirm registration",{focus.clearFocus();keyboard?.hide();work.launch {holder.register()}},enabled&&event.event.registrationOpen&&event.form.supported)
                }
                ActionRoute.REGISTRATION->s.registration?.let {registration->
                    Text(registration.title,style=MaterialTheme.typography.titleLarge);Text(memberStatus(registration.status));Text(memberDate(registration.startsAt));registration.waitlistPosition?.let {Text("Waitlist position $it")}
                    if(registration.status=="registered"){BrandButton("Show QR ticket",{work.launch {holder.ticketQR()}},enabled);s.qr?.let {bytes->val bitmap=remember(bytes){runCatching {org.spaceworks.app.actions.SafeTicketSvg.render(bytes)}.getOrNull()};if(bitmap==null)BrandError("This ticket couldn't be displayed. Refresh or ask the event team for help.")else Image(bitmap.asImageBitmap(),"Your event registration QR ticket",Modifier.fillMaxWidth().heightIn(max=360.dp))}}else Text("A QR ticket is available only after registration is confirmed.")
                    registration.cancellationCutoffAt?.let {Text("Cancellation closes ${memberDate(it)}.")}
                    if(registration.canCancel)BrandTextButton("Cancel registration",{review("cancelRegistration")},enabled)
                }
                ActionRoute.PROFILE->s.profile?.let {profile->
                    Text("Your account contact details. Updating a phone here does not verify it as a sign-in method.")
                    BrandField(profile.displayName.orEmpty(),{holder.editProfile(profile.copy(displayName=it))},"Display name",enabled=enabled,role=FieldRole.NAME,errorMessage=s.fieldErrors["displayName"])
                    BrandField(profile.firstName.orEmpty(),{holder.editProfile(profile.copy(firstName=it))},"First name",enabled=enabled,role=FieldRole.NAME,errorMessage=s.fieldErrors["firstName"])
                    BrandField(profile.lastName.orEmpty(),{holder.editProfile(profile.copy(lastName=it))},"Last name",enabled=enabled,role=FieldRole.NAME,errorMessage=s.fieldErrors["lastName"])
                    BrandField(profile.phone.orEmpty(),{holder.editProfile(profile.copy(phone=it))},"Phone",enabled=enabled,role=FieldRole.PHONE,errorMessage=s.fieldErrors["phone"])
                    BrandButton("Save changes",{focus.clearFocus();keyboard?.hide();work.launch {holder.saveProfile()}},enabled&&holder.profileDirty)
                }
                ActionRoute.PREFERENCES->s.preferences?.let {cells->
                    Text("Choose optional messages from this makerspace. Device notification permission is managed separately in your phone settings.")
                    if(cells.isEmpty())Text("No communication preferences are available.")
                    cells.forEach {cell->ActionCard {
                        Text(memberStatus(cell.feature),style=MaterialTheme.typography.titleMedium);Text(memberStatus(cell.channel))
                        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text(if(cell.available)"Optional messages" else "Unavailable for this makerspace",Modifier.weight(1f));Switch(checked=cell.enabled,onCheckedChange={value->work.launch {holder.preference(cell,value)}},enabled=enabled&&(cell.available||cell.enabled),modifier=Modifier.semantics {contentDescription="${memberStatus(cell.feature)} ${memberStatus(cell.channel)} optional messages"})}
                        if(cell.mandatoryEvents.isNotEmpty()){Text("Always-on safety messages",style=MaterialTheme.typography.labelLarge);cell.mandatoryEvents.forEach {Text(memberStatus(it))};Text("These messages continue even when optional messages are off.")}
                        if(s.capabilities.value?.dailyDigest==true&&cell.channel=="email"&&cell.dailyDigest!=null)Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text("Daily email digest",Modifier.weight(1f));Switch(checked=cell.dailyDigest,onCheckedChange={digest->work.launch {holder.preference(cell,cell.enabled,digest)}},enabled=enabled&&cell.enabled&&cell.available,modifier=Modifier.semantics {contentDescription="${memberStatus(cell.feature)} daily email digest"})}
                    }}
                }
                else->Unit
            }
            if(s.error!=null&&!s.busy&&!s.loading)when(s.route){ActionRoute.SPACE->BrandTextButton("Try again",{s.referenceId?.let {work.launch {holder.openSpace(it)}}},enabled);ActionRoute.EVENT->BrandTextButton("Try again",{s.referenceId?.let {work.launch {holder.openEvent(it)}}},enabled);ActionRoute.PROFILE->if(s.profile==null)BrandTextButton("Try again",{work.launch {holder.openProfile()}},enabled);ActionRoute.PREFERENCES->if(s.preferences==null)BrandTextButton("Try again",{work.launch {holder.openPreferences()}},enabled);else->Unit}
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable private fun ActionTimes(start:String,end:String,enabled:Boolean,onChange:(String,String)->Unit) {
    val context=LocalContext.current
    fun parsed(value:String,fallbackHour:Int)=runCatching {OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault())}.getOrElse {ZonedDateTime.now().plusDays(1).withHour(fallbackHour).withMinute(0).withSecond(0).withNano(0)}
    fun choose(isStart:Boolean){val current=parsed(if(isStart)start else end,if(isStart)10 else 12)
        DatePickerDialog(context,{_,year,month,day->TimePickerDialog(context,{_,hour,minute->val value=LocalDateTime.of(year,month+1,day,hour,minute).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString();if(isStart)onChange(value,end)else onChange(start,value)},current.hour,current.minute,android.text.format.DateFormat.is24HourFormat(context)).show()},current.year,current.monthValue-1,current.dayOfMonth).show()}
    LaunchedEffect(Unit){if(start.isEmpty()&&end.isEmpty())onChange(parsed(start,10).toOffsetDateTime().toString(),parsed(end,12).toOffsetDateTime().toString())}
    ActionCard {Text("Choose your time",style=MaterialTheme.typography.titleLarge);Text("Times shown in ${ZoneId.systemDefault().id}.")
        BrandTextButton("Start: ${if(start.isBlank())"Choose start"else memberDate(start)}",{choose(true)},enabled)
        BrandTextButton("End: ${if(end.isBlank())"Choose end"else memberDate(end)}",{choose(false)},enabled)}
}
@Composable private fun ActionQuestions(form:ActionForm,answers:Map<String,JsonElement>,errors:Map<String,String>,enabled:Boolean,onAnswer:(String,JsonElement)->Unit) {
    if(!form.supported){BrandError("This form isn't supported by this app version. Ask your makerspace for help.");return}
    if(form.questions.isNotEmpty())Text("A few more details",style=MaterialTheme.typography.titleLarge)
    form.questions.forEach {question->val raw=answers[question.id];val primitive=raw as? JsonPrimitive;val label=question.label+if(question.required)" (required)"else" (optional)"
        when(question.type){
            "single_choice","dropdown"->ActionCard {Text(label);question.options.forEach {option->ActionChoice(option,primitive?.contentOrNull==option,{onAnswer(question.id,JsonPrimitive(option))},enabled)}}
            "multi_choice"->ActionCard {Text(label);val choices=(raw as? JsonArray)?.mapNotNull {(it as? JsonPrimitive)?.contentOrNull}.orEmpty();question.options.forEach {option->Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(option in choices,enabled,Role.Checkbox){checked->onAnswer(question.id,JsonArray((if(checked)choices+option else choices-option).map(::JsonPrimitive)))},verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(option in choices,null,enabled=enabled);Spacer(Modifier.width(12.dp));Text(option)}}}
            "yes_no"->ActionCard {Text(label);ActionChoice("Yes",primitive?.booleanOrNull==true,{onAnswer(question.id,JsonPrimitive(true))},enabled);ActionChoice("No",primitive?.booleanOrNull==false,{onAnswer(question.id,JsonPrimitive(false))},enabled)}
            "paragraph"->OutlinedTextField(value=primitive?.contentOrNull.orEmpty(),onValueChange={onAnswer(question.id,JsonPrimitive(it.take(5000)))},label={Text(label)},minLines=3,maxLines=8,enabled=enabled,isError=errors[question.id]!=null,modifier=Modifier.fillMaxWidth(),colors=OutlinedTextFieldDefaults.colors(focusedLabelColor=MaterialTheme.colorScheme.onPrimaryContainer,focusedBorderColor=MaterialTheme.colorScheme.onPrimaryContainer))
            else->BrandField(primitive?.contentOrNull.orEmpty(),{onAnswer(question.id,JsonPrimitive(it.take(500)))},if(question.type=="date")"$label (YYYY-MM-DD)"else label,enabled=enabled,role=if(question.type=="number")FieldRole.NUMBER else FieldRole.TEXT,errorMessage=errors[question.id])
        }
        if(question.type in listOf("paragraph","single_choice","dropdown","multi_choice","yes_no"))BrandError(errors[question.id])
    }
}
