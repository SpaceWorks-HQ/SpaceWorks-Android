package org.spaceworks.app.actions

import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.MobileCopyException
import org.spaceworks.app.platform.failure
import org.spaceworks.app.copy.CoreStrings
import org.spaceworks.app.copy.apiFailure

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.spaceworks.app.member.*
import org.spaceworks.app.network.ApiFailure
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Presentation drafts and server results, never network DTOs. */
data class BasketLine(val item:InventoryItem,val quantity:Int)
enum class ContactField { DISPLAY_NAME, FIRST_NAME, LAST_NAME, PHONE }
enum class ActionRoute { CART, REQUEST_RECEIPT, RENEW, SPACE, BOOKING, EVENT, REGISTRATION, PROFILE, PREFERENCES }
data class ActionReceipt(val title:String,val status:String,val message:String,val publicToken:String?=null,val dueAt:String?=null)
data class MemberActionsState(
    val capabilities:MemberLoad<MakerCapabilities> = MemberLoad(),
    val scope:MemberScope?=null,val route:ActionRoute?=null,val referenceId:String?=null,val busy:Boolean=false,val loading:Boolean=false,
    val error:String?=null,val fieldErrors:Map<String,String> = emptyMap(),val retryAtMillis:Long=0,
    val cart:List<BasketLine> = emptyList(),val purpose:String="",val receipt:ActionReceipt?=null,
    val requestStatus:RequestStatus?=null,val recentRequest:SubmittedRequest?=null,val loan:MemberLoan?=null,
    val space:ActionSpace?=null,val availability:ActionAvailability?=null,val startsAt:String="",val endsAt:String="",
    val booking:MemberBooking?=null,val event:ActionEvent?=null,val answers:Map<String,JsonElement> = emptyMap(),
    val ticketId:String?=null,val phone:String="",val email:String="",val inviteCode:String="",val promoCode:String="",
    val registration:MemberRegistration?=null,val qr:ByteArray?=null,
    val profile:ActionProfile?=null,val savedProfile:ActionProfile?=null,val preferences:List<ActionPreference>?=null,
)

interface ActionAccountReset { fun resetAccount() }

/** Each account owns one holder; scope changes discard all drafts, receipts and tickets. */
class MemberActionsStateHolder(private val api:MemberActionsGateway,private val reads:MemberStateHolder,
    private val now:()->Long=System::currentTimeMillis,private val onUnauthorized:suspend()->Unit={},
    private val onProfileSaved:suspend(ActionProfile)->Unit={},
    private val capabilities:MakerCapabilitiesGateway=MakerCapabilitiesGateway {MakerCapabilities.NONE},private val text:MobileText=MobileText.ENGLISH) {
    constructor(api:MemberActionsGateway,reads:MemberStateHolder,now:()->Long=System::currentTimeMillis,onUnauthorized:suspend()->Unit={},onProfileSaved:suspend(ActionProfile)->Unit={},capabilities:MakerCapabilitiesGateway)
        :this(api,reads,now,onUnauthorized,onProfileSaved,capabilities,MobileText.ENGLISH)

    private val mutable=MutableStateFlow(MemberActionsState())
    val state=mutable.asStateFlow()
    private val epoch=AtomicLong()
    private val version=AtomicLong()
    private val capabilityVersion=AtomicLong()
    private val mutation=Mutex()
    private var attemptKey:String?=null
    private var attemptFingerprint:Pair<String,List<RequestLine>>?=null
    fun clear(){(api as? ActionAccountReset)?.resetAccount();epoch.incrementAndGet();version.incrementAndGet();attemptKey=null;attemptFingerprint=null;mutable.value=MemberActionsState()}
    fun activate(scope:MemberScope){if(state.value.scope!=scope){val retry=state.value.retryAtMillis;epoch.incrementAndGet();version.incrementAndGet();attemptKey=null;attemptFingerprint=null;mutable.value=MemberActionsState(scope=scope,retryAtMillis=retry)}}
    fun close():Boolean {if(state.value.busy)return false;version.incrementAndGet();mutable.update {it.copy(route=null,error=null,fieldErrors=emptyMap(),qr=null,loading=false)};return true}
    private suspend fun loadCapabilities(scope:MemberScope):MakerCapabilities {
        val expected=epoch.get();val request=capabilityVersion.incrementAndGet()
        fun current()=expected==epoch.get()&&request==capabilityVersion.get()
        mutable.update {it.copy(capabilities=MemberLoad(loading=true))}
        try {
            val result=capabilities.load(scope)
            if(!current())throw CancellationException("Makerspace context changed")
            mutable.update {it.copy(capabilities=MemberLoad(result))}
            return result
        }catch(cancelled:CancellationException){if(current())mutable.update {it.copy(capabilities=MemberLoad())};throw cancelled}
        catch(f:Exception){if(current())mutable.update {it.copy(capabilities=MemberLoad(error=if(f is MobileCopyException)text.failure(f)else if(f is ApiFailure)text.apiFailure(f) else text(CoreStrings.CORE_COULDN_T_CONNECT_TRY_AGAIN)))};throw f}
    }
    suspend fun refreshCapabilities(){val scope=state.value.scope?:return;val expected=epoch.get();try {loadCapabilities(scope)}catch(c:CancellationException){throw c}catch(f:Exception){error(f,expected)}}
    private fun reset(route:ActionRoute){version.incrementAndGet();mutable.update {it.copy(route=route,referenceId=null,error=null,fieldErrors=emptyMap(),receipt=null,requestStatus=null,loan=null,space=null,availability=null,booking=null,event=null,answers=emptyMap(),ticketId=null,phone="",email="",inviteCode="",promoCode="",registration=null,qr=null,loading=false,profile=null,savedProfile=null,preferences=null)}}
    fun add(item:InventoryItem){
        if(state.value.busy)return
        val current=state.value.cart;val old=current.firstOrNull {it.item.id==item.id}
        if(old==null && current.size>=20){mutable.update {it.copy(error=text(CoreStrings.CORE_YOU_CAN_REQUEST_UP_TO_20_DIFFERENT_ITEMS))};return}
        val qty=((old?.quantity?:0)+1).coerceAtMost(99)
        val changed=if(old==null)current+BasketLine(item,qty) else current.map {row->if(row.item.id==item.id)row.copy(quantity=qty)else row}
        mutable.update {it.copy(cart=changed,error=null)}
        if(changed!=current)invalidateAttempt()
    }
    fun quantity(id:String,value:Int){if(state.value.busy)return;val before=state.value.cart;val next=before.mapNotNull {row->if(row.item.id!=id)row else if(value<=0)null else row.copy(quantity=value.coerceAtMost(99))};if(next!=before){mutable.update {it.copy(cart=next)};invalidateAttempt()}}
    fun purpose(value:String){if(!state.value.busy && value.take(500)!=state.value.purpose){mutable.update {it.copy(purpose=value.take(500))};invalidateAttempt()}}
    private fun invalidateAttempt(){attemptFingerprint=null;attemptKey=null}
    fun openCart(){if(!state.value.busy)reset(ActionRoute.CART)}
    fun openRecentRequest(){val recent=state.value.recentRequest?:return;if(state.value.busy)return;reset(ActionRoute.REQUEST_RECEIPT);mutable.update {it.copy(receipt=ActionReceipt(text(CoreStrings.CORE_YOUR_LATEST_REQUEST),recent.status,text(CoreStrings.CORE_REFRESH_TO_CHECK_YOUR_MAKERSPACE_S_LATEST_STATUS),recent.publicToken))}}
    fun openRenew(loan:MemberLoan){if(state.value.busy)return;reset(ActionRoute.RENEW);mutable.update {it.copy(loan=loan)}}
    fun times(start:String,end:String){if(!state.value.busy){version.incrementAndGet();mutable.update {it.copy(startsAt=start,endsAt=end,availability=null,loading=false,fieldErrors=emptyMap())}}}
    fun answer(id:String,value:JsonElement){if(!state.value.busy)mutable.update {it.copy(answers=it.answers+(id to value),fieldErrors=it.fieldErrors-id)}}
    fun ticket(id:String?){if(!state.value.busy)mutable.update {it.copy(ticketId=id)}}
    fun registrationFields(phone:String=state.value.phone,email:String=state.value.email,invite:String=state.value.inviteCode,promo:String=state.value.promoCode){if(!state.value.busy)mutable.update {it.copy(phone=phone,email=email,inviteCode=invite,promoCode=promo)}}
    fun editProfile(value:ActionProfile){if(!state.value.busy)mutable.update {it.copy(profile=value)}}
    fun editProfileField(field:ContactField,value:String){
        if(state.value.busy)return
        mutable.update {s->val current=s.profile?:return@update s
            s.copy(profile=when(field){ContactField.DISPLAY_NAME->current.copy(displayName=value);ContactField.FIRST_NAME->current.copy(firstName=value);ContactField.LAST_NAME->current.copy(lastName=value);ContactField.PHONE->current.copy(phone=value)})}
    }
    val profileDirty get()=state.value.profile!=state.value.savedProfile
    private suspend fun error(f:Exception,expected:Long):String {
        if(expected==epoch.get() && f is ApiFailure){
            if(f.status==429)mutable.update {it.copy(retryAtMillis=now()+(f.retryAfterSeconds?:60)*1000)}
            if(f.status==401)onUnauthorized()
        }
        return if(f is MobileCopyException)text.failure(f) else if(f is ApiFailure)text.apiFailure(f) else text(CoreStrings.CORE_COULDN_T_CONNECT_TRY_AGAIN)
    }
    private suspend fun read(operation:suspend(MemberScope)->Unit){
        val scope=state.value.scope?:return;val expected=epoch.get();val request=version.get()
        if(now()<state.value.retryAtMillis){mutable.update {it.copy(error=text(CoreStrings.CORE_TRY_AGAIN_AFTER_THE_TIMER_FINISHES))};return}
        mutable.update {it.copy(loading=true,error=null)}
        try {operation(scope)}catch(c:CancellationException){throw c}catch(f:Exception){val message=error(f,expected);if(expected==epoch.get()&&request==version.get())mutable.update {it.copy(error=message)}}
        finally {if(expected==epoch.get()&&request==version.get())mutable.update {it.copy(loading=false)}}
    }
    private suspend fun <T> load(route:ActionRoute,referenceId:String?=null,fetch:suspend(MemberScope)->T,publish:(MemberActionsState,T)->MemberActionsState){
        if(state.value.busy)return
        reset(route);mutable.update {it.copy(referenceId=referenceId)};val expected=epoch.get();val request=version.get()
        read {scope->val result=fetch(scope);if(expected==epoch.get()&&request==version.get())mutable.update {publish(it,result)}}
    }
    suspend fun openSpace(id:String)=load(ActionRoute.SPACE,id,{api.space(it,id)}){s,v->s.copy(space=v)}
    suspend fun openEvent(id:String)=load(ActionRoute.EVENT,id,{api.event(it,id)}){s,v->s.copy(event=v)}
    fun openBooking(value:MemberBooking){if(state.value.busy)return;reset(ActionRoute.BOOKING);mutable.update {it.copy(booking=value,startsAt=value.startsAt,endsAt=value.endsAt)}}
    fun openRegistration(value:MemberRegistration){if(state.value.busy)return;reset(ActionRoute.REGISTRATION);mutable.update {it.copy(registration=value)}}
    suspend fun openProfile()=load(ActionRoute.PROFILE,fetch={scope->if(!loadCapabilities(scope).selfService)throw ApiFailure(403,text(CoreStrings.CORE_PROFILE_EDITING_IS_UNAVAILABLE_FOR_THIS_MAKERSPACE),null,null);api.profile(scope)}){s,v->s.copy(profile=v,savedProfile=v)}
    suspend fun openPreferences()=load(ActionRoute.PREFERENCES,fetch={scope->if(!loadCapabilities(scope).memberPreferences)throw ApiFailure(403,text(CoreStrings.CORE_COMMUNICATION_PREFERENCES_ARE_UNAVAILABLE_FOR_THIS_MAKERSPAC),null,null);api.preferences(scope)}){s,v->s.copy(preferences=v)}
    private fun validTimes():Boolean {
        val s=state.value
        val valid=try {val start=OffsetDateTime.parse(s.startsAt);val end=OffsetDateTime.parse(s.endsAt);end.isAfter(start)&&start.toInstant().toEpochMilli()>now()}catch(_:Exception){false}
        if(!valid)mutable.update {it.copy(error=text(CoreStrings.CORE_CHOOSE_A_FUTURE_START_AND_AN_END_AFTER_IT))}
        return valid
    }
    suspend fun checkAvailability(){if(!validTimes())return;val s=state.value;val id=s.space?.space?.id?:return;val expected=epoch.get();val request=version.incrementAndGet()
        read {scope->val value=api.availability(scope,id,s.startsAt,s.endsAt);if(expected==epoch.get()&&request==version.get()&&state.value.startsAt==s.startsAt&&state.value.endsAt==s.endsAt)mutable.update {it.copy(availability=value)}}
    }
    /** A reviewed mutation finishes independently of a screen cancellation, publishes only to its original context. */
    private suspend fun mutate(block:suspend(MemberScope)->Unit):Boolean {
        val scope=state.value.scope?:return false
        if(now()<state.value.retryAtMillis || !mutation.tryLock())return false
        val expected=epoch.get();mutable.update {it.copy(busy=true,error=null,fieldErrors=emptyMap())}
        return withContext(NonCancellable){try {block(scope);expected==epoch.get()}
            catch(f:Exception){val message=error(f,expected);if(expected==epoch.get())mutable.update {it.copy(error=message)};false}
            finally {if(expected==epoch.get())mutable.update {it.copy(busy=false)};mutation.unlock()}}
    }
    private fun receipt(expected:Long,value:ActionReceipt){if(expected==epoch.get())mutable.update {it.copy(receipt=value,qr=null)}}
    suspend fun submitRequest():Boolean {
        val before=state.value
        if(before.cart.isEmpty()){mutable.update {it.copy(error=text(CoreStrings.CORE_ADD_AN_ITEM_BEFORE_REQUESTING_TO_BORROW))};return false}
        val fingerprint=before.purpose to before.cart.sortedBy {it.item.id}.map {RequestLine(it.item.id,it.quantity)}
        if(fingerprint!=attemptFingerprint){attemptFingerprint=fingerprint;attemptKey=UUID.randomUUID().toString()}
        val expected=epoch.get();val key=attemptKey
        return mutate {scope->val result=api.submitRequest(scope,before.purpose,before.cart.map {RequestLine(it.item.id,it.quantity)},key)
            if(expected==epoch.get()){mutable.update {it.copy(route=ActionRoute.REQUEST_RECEIPT,cart=emptyList(),purpose="",recentRequest=result)};invalidateAttempt();receipt(expected,ActionReceipt(text(CoreStrings.CORE_REQUEST_RECEIVED),result.status,text(CoreStrings.CORE_YOUR_MAKERSPACE_WILL_REVIEW_YOUR_REQUEST),result.publicToken));reads.refreshOverview()}}
    }
    suspend fun refreshRequest(){val token=state.value.receipt?.publicToken?:return;val expected=epoch.get();val request=version.incrementAndGet();read {scope->val value=api.requestStatus(scope,token);if(expected==epoch.get()&&request==version.get())mutable.update {it.copy(requestStatus=value,receipt=it.receipt?.copy(status=value.status))}}}
    suspend fun renew():Boolean {val loan=state.value.loan?:return false;if(!loan.canRenew){mutable.update {it.copy(error=loan.renewalRefusal?:text(CoreStrings.CORE_THIS_LOAN_CANNOT_BE_RENEWED))};return false};val expected=epoch.get()
        return mutate {scope->val result=api.renew(scope,loan.requestId);receipt(expected,ActionReceipt(text(CoreStrings.CORE_LOAN_RENEWED),"renewed",text(CoreStrings.CORE_YOUR_MAKERSPACE_CONFIRMED_THE_NEW_DUE_DATE),dueAt=result.dueAt));if(expected==epoch.get())reads.refreshOverview()}}
    private fun validAnswers(form:ActionForm):Boolean {val errors=ActionForms.errors(form,state.value.answers,text);if(errors.isNotEmpty()){mutable.update {it.copy(fieldErrors=errors,error=if(!form.supported)text(CoreStrings.CORE_THIS_FORM_ISN_T_SUPPORTED_BY_THIS_APP_VERSION_ASK_YOUR_MAKER) else text(CoreStrings.CORE_CHECK_THE_HIGHLIGHTED_ANSWERS))};return false};return true}
    private fun formAnswers(form:ActionForm,answers:Map<String,JsonElement>)=answers.mapValues {(key,value)->if(form.questions.firstOrNull {it.id==key}?.type=="number"){(value as?JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()?.let {JsonPrimitive(it)}?:value}else value}
    suspend fun book():Boolean {val before=state.value;val space=before.space?:return false;if(!validTimes()||!validAnswers(space.form))return false
        if(before.availability==null){mutable.update {it.copy(error=text(CoreStrings.CORE_CHECK_AVAILABILITY_BEFORE_BOOKING))};return false};val expected=epoch.get()
        return mutate {scope->val status=api.book(scope,space.space.id,before.startsAt,before.endsAt,formAnswers(space.form,before.answers));receipt(expected,ActionReceipt(if(status=="approved")text(CoreStrings.CORE_SPACE_BOOKED)else text(CoreStrings.CORE_BOOKING_REQUEST_RECEIVED),status,text(CoreStrings.CORE_OPEN_ACTIVITY_FOR_THE_CONFIRMED_BOOKING_DETAILS)));if(expected==epoch.get())reads.refreshOverview()}}
    private fun beforeCutoff(value:String?):Boolean = value==null || try {OffsetDateTime.parse(value).toInstant().toEpochMilli()>now()}catch(_:Exception){false}
    suspend fun cancelBooking():Boolean {val before=state.value.booking?:return false;if(!before.canCancel||!beforeCutoff(before.cancellationCutoffAt)){mutable.update {it.copy(error=text(CoreStrings.CORE_THIS_BOOKING_CAN_NO_LONGER_BE_CANCELLED_HERE))};return false};val expected=epoch.get()
        return mutate {scope->val result=api.cancelBooking(scope,before.id);if(expected==epoch.get())mutable.update {it.copy(booking=result)};receipt(expected,ActionReceipt(text(CoreStrings.CORE_BOOKING_UPDATED),result.status,text(CoreStrings.CORE_YOUR_MAKERSPACE_S_BOOKING_STATUS_IS_SHOWN_ABOVE)));if(expected==epoch.get())reads.refreshOverview()}}
    suspend fun reschedule():Boolean {val before=state.value;val booking=before.booking?:return false;if(!booking.canCancel||!beforeCutoff(booking.cancellationCutoffAt)||!validTimes())return false;val expected=epoch.get()
        return mutate {scope->val result=api.reschedule(scope,booking.id,before.startsAt,before.endsAt);if(expected==epoch.get())mutable.update {it.copy(booking=result)};receipt(expected,ActionReceipt(text(CoreStrings.CORE_BOOKING_UPDATED),result.status,text(CoreStrings.CORE_REVIEW_THE_UPDATED_TIME_IN_ACTIVITY)));if(expected==epoch.get())reads.refreshOverview()}}
    suspend fun register():Boolean {val before=state.value;val event=before.event?:return false;if(!event.event.registrationOpen){mutable.update {it.copy(error=text(CoreStrings.CORE_REGISTRATION_IS_CLOSED))};return false};if(!validAnswers(event.form))return false
        if((event.ticketRequired||event.tickets.isNotEmpty()) && event.tickets.none {it.id==before.ticketId && (it.spotsLeft==null||it.spotsLeft>0)}){mutable.update {it.copy(error=text(CoreStrings.CORE_CHOOSE_AN_AVAILABLE_TICKET_TYPE))};return false};if(event.audience=="invite" && before.inviteCode.isBlank()){mutable.update {it.copy(error=text(CoreStrings.CORE_ENTER_YOUR_INVITATION_CODE))};return false};val expected=epoch.get()
        return mutate {scope->val status=api.register(scope,event.event.id,formAnswers(event.form,before.answers),before.phone.takeIf {it.isNotBlank()},before.email.takeIf {it.isNotBlank()},before.inviteCode.takeIf {it.isNotBlank()},before.ticketId,before.promoCode.takeIf {it.isNotBlank()});receipt(expected,ActionReceipt(when(status){"registered"->text(CoreStrings.CORE_REGISTRATION_CONFIRMED);"pending"->text(CoreStrings.CORE_AWAITING_APPROVAL);"waitlisted"->text(CoreStrings.CORE_YOU_RE_ON_THE_WAITLIST);else->text(CoreStrings.CORE_REGISTRATION_RECEIVED)},status,text(CoreStrings.CORE_OPEN_ACTIVITY_TO_REVIEW_YOUR_REGISTRATION_A_QR_TICKET_IS_AVA)));if(expected==epoch.get())reads.refreshOverview()}}
    suspend fun cancelRegistration():Boolean {val before=state.value.registration?:return false;if(!before.canCancel||!beforeCutoff(before.cancellationCutoffAt)){mutable.update {it.copy(error=text(CoreStrings.CORE_THIS_REGISTRATION_CAN_NO_LONGER_BE_CANCELLED_HERE))};return false};version.incrementAndGet();mutable.update {it.copy(qr=null)};val expected=epoch.get()
        return mutate {scope->val status=api.cancelRegistration(scope,before.id);receipt(expected,ActionReceipt(text(CoreStrings.CORE_REGISTRATION_UPDATED),status,text(CoreStrings.CORE_YOUR_REGISTRATION_STATUS_IS_SHOWN_ABOVE)));if(expected==epoch.get()){mutable.update {it.copy(qr=null,registration=it.registration?.copy(status=status,canCancel=false,checkinToken=null))};reads.refreshOverview()}}}
    suspend fun ticketQR(){if(state.value.busy)return;val before=state.value.registration?:return;if(before.status!="registered"){mutable.update {it.copy(qr=null,error=text(CoreStrings.CORE_A_TICKET_IS_AVAILABLE_ONLY_FOR_A_CONFIRMED_REGISTRATION))};return};mutable.update {it.copy(qr=null)};val expected=epoch.get();val request=version.incrementAndGet()
        read {scope->val value=api.registrationQR(scope,before.id);if(expected==epoch.get()&&request==version.get()&&state.value.registration?.id==before.id&&state.value.registration?.status=="registered")mutable.update {it.copy(qr=value)}}}
    suspend fun saveProfile():Boolean {if(state.value.capabilities.value?.selfService!=true){mutable.update {it.copy(error=text(CoreStrings.CORE_PROFILE_EDITING_IS_UNAVAILABLE_FOR_THIS_MAKERSPACE))};return false};val before=state.value.profile?:return false
        val errors=buildMap {if(before.displayName.orEmpty().isBlank())put("displayName",text(CoreStrings.CORE_ENTER_YOUR_DISPLAY_NAME));if(before.displayName.orEmpty().length>200)put("displayName",text(CoreStrings.CORE_USE_UP_TO_200_CHARACTERS));if(before.firstName.orEmpty().length>150)put("firstName",text(CoreStrings.CORE_USE_UP_TO_150_CHARACTERS));if(before.lastName.orEmpty().length>150)put("lastName",text(CoreStrings.CORE_USE_UP_TO_150_CHARACTERS));if(before.phone.orEmpty().length>32)put("phone",text(CoreStrings.CORE_USE_UP_TO_32_CHARACTERS))}
        if(errors.isNotEmpty()){mutable.update {it.copy(fieldErrors=errors,error=text(CoreStrings.CORE_CHECK_THE_HIGHLIGHTED_DETAILS))};return false};val expected=epoch.get()
        return mutate {scope->val value=api.updateProfile(scope,before);if(expected==epoch.get())mutable.update {it.copy(profile=value,savedProfile=value)};receipt(expected,ActionReceipt(text(CoreStrings.CORE_PROFILE_SAVED),"saved",text(CoreStrings.CORE_YOUR_PROFILE_DETAILS_HAVE_BEEN_UPDATED)));if(expected==epoch.get())try {onProfileSaved(value)}catch(_:Exception){}}}
    suspend fun preference(cell:ActionPreference,enabled:Boolean,digest:Boolean?=cell.dailyDigest):Boolean {val caps=state.value.capabilities.value?:return false;if(!caps.memberPreferences)return false;val current=state.value.preferences?.firstOrNull {it.feature==cell.feature&&it.channel==cell.channel}?:return false
        if(!current.available && !current.enabled && enabled)return false
        if(digest!=null&&current.channel!="email")return false
        if(digest!=current.dailyDigest && !caps.dailyDigest)return false
        val expected=epoch.get();return mutate {scope->val values=api.updatePreference(scope,current.feature,current.channel,enabled,if(caps.dailyDigest)digest else null);if(expected==epoch.get())mutable.update {it.copy(preferences=values)}}}
}
