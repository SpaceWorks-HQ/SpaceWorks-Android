package org.spaceworks.app.payments

import org.spaceworks.app.network.retryDeadlineMillis

import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.rememberMobileText

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.spaceworks.app.copy.apiFailure
import org.spaceworks.app.member.MemberLoad
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.platform.SheetCloseReason
import org.spaceworks.app.platform.MobileCopyException
import org.spaceworks.app.platform.failure

enum class PaymentCheckoutPhase { IDLE, PREPARING, PRESENTING, CHECKING, AWAITING, COMPLETED, STOPPED }
data class PaymentCheckout(val phase:PaymentCheckoutPhase=PaymentCheckoutPhase.IDLE,val paymentId:String?=null,val reference:String?=null,val message:String?=null)
data class PaymentsState(val scope:MemberScope?=null,val records:MemberLoad<List<PaymentRecord>> = MemberLoad(),
    val documents:MemberLoad<List<PaymentDocument>> = MemberLoad(),val nextDocumentPage:Int?=null,
    val selectedId:String?=null,val showingDocuments:Boolean=false,val documentPaymentId:String?=null,
    val checkout:PaymentCheckout=PaymentCheckout(),val preview:PaymentFile?=null,val documentBusy:Boolean=false,
    val error:String?=null,val documentError:String?=null,val retryAtMillis:Long=0,
    val callbackPending:Boolean=false,val callbackLoading:Boolean=false,val callbackError:String?=null) {
    val busy get()=callbackLoading || checkout.phase in setOf(PaymentCheckoutPhase.PREPARING,PaymentCheckoutPhase.PRESENTING,PaymentCheckoutPhase.CHECKING)
}

/** Server payment status alone completes checkout. SDK credentials never enter this StateFlow. */
class PaymentsStateHolder(private val api:PaymentsGateway,private val files:PaymentFileStore,
    private val now:()->Long=System::currentTimeMillis,private val pause:suspend(Long)->Unit={delay(it)},
    private val onUnauthorized:suspend()->Unit={},private val onForbidden:suspend()->Unit={},
    private val resetSDK:()->Unit={},private val text:MobileText=MobileText.ENGLISH) {
    private val mutable=MutableStateFlow(PaymentsState());val state=mutable.asStateFlow()
    private val epoch=AtomicLong();private val listVersion=AtomicLong();private val documentVersion=AtomicLong();private val previewVersion=AtomicLong()
    private val callbackLock=Mutex();private val acknowledgedCallbacks=mutableSetOf<String>()
    val isDemo get()=api is PaymentPreviewControl
    fun activate(scope:MemberScope){if(state.value.scope!=scope){val retry=state.value.retryAtMillis;discard(resetProvider=state.value.scope!=null);mutable.value=PaymentsState(scope=scope,retryAtMillis=retry)}}
    fun clear(){(api as? PaymentPreviewControl)?.resetAccount();discard();mutable.value=PaymentsState()}
    private fun discard(resetProvider:Boolean=true){epoch.incrementAndGet();acknowledgedCallbacks.clear();listVersion.incrementAndGet();documentVersion.incrementAndGet();previewVersion.incrementAndGet();runCatching{state.value.preview?.close()};if(resetProvider)resetSDK()}
    fun record(id:String)=state.value.records.value?.firstOrNull {it.id==id}
    fun canRequest()=state.value.scope!=null && now()>=state.value.retryAtMillis
    fun canPay(row:PaymentRecord)=canRequest() && !state.value.busy && !state.value.callbackPending && row.status=="pending" && row.onlineAvailable && !(state.value.checkout.phase==PaymentCheckoutPhase.AWAITING && state.value.checkout.paymentId==row.id)
    fun open(id:String){if(!state.value.busy && record(id)!=null)mutable.update{it.copy(selectedId=id,showingDocuments=false,error=null)}}
    /** Links never open a cached charge after a refused or superseded authoritative list read. */
    suspend fun openFromRoute(id:String?):Boolean {
        val scope=state.value.scope?:return false
        val expected=epoch.get()
        if(!canRequest() || state.value.busy)return false
        val fresh=refresh()
        if(!fresh || expected!=epoch.get() || state.value.scope!=scope || state.value.busy || state.value.records.loading || state.value.records.error!=null || state.value.records.value==null)return false
        if(id!=null && record(id)==null)return false
        if(id==null)return closeDetail()
        open(id)
        return state.value.selectedId==id
    }
    fun closeDetail():Boolean {if(state.value.busy)return false;closePreview();mutable.update {it.copy(selectedId=if(it.showingDocuments)it.selectedId else null,showingDocuments=false,documentPaymentId=null,error=null)};return true}
    fun showDocuments(paymentId:String?=null){if(!state.value.busy)mutable.update {it.copy(showingDocuments=true,documentPaymentId=paymentId)}}
    fun closePreview(){previewVersion.incrementAndGet();val file=state.value.preview;mutable.update {it.copy(preview=null,documentBusy=false)};runCatching{file?.close()}}
    private suspend fun failure(f:Exception,expected:Long):String {
        val message=when(f){is ApiFailure->text.apiFailure(f);is MobileCopyException->text.failure(f);is IllegalArgumentException->f.message?.takeIf{it.length<250}?:text(PaymentStrings.PAYMENT_THIS_DOCUMENT_IS_UNAVAILABLE_TRY_AGAIN);else->text(PaymentStrings.PAYMENT_COULDN_T_CONNECT_TRY_AGAIN)}
        if(expected==epoch.get() && f is ApiFailure){
            if(f.status==429)mutable.update {it.copy(retryAtMillis=maxOf(it.retryAtMillis,retryDeadlineMillis(now(),f.retryAfterSeconds?:60)))}
            if(f.status==401){clear();onUnauthorized()}
            else if(f.status==403){discard();mutable.value=PaymentsState(scope=state.value.scope,retryAtMillis=state.value.retryAtMillis,error=message);onForbidden()}
        }
        return message
    }
    suspend fun refresh():Boolean {val scope=state.value.scope?:return false;if(!canRequest()||state.value.busy)return false
        val expected=epoch.get();val version=listVersion.incrementAndGet();mutable.update {it.copy(records=it.records.copy(loading=true,error=null),error=null)}
        var committed=false
        try {val rows=api.payments(scope);if(expected==epoch.get()&&version==listVersion.get()){mutable.update {it.copy(records=MemberLoad(rows))};committed=true;if(reconcileVisible())loadDocuments()}}
        catch(c:CancellationException){throw c}catch(f:Exception){val message=failure(f,expected);if(expected==epoch.get()&&version==listVersion.get())mutable.update {it.copy(records=it.records.copy(loading=false,error=message))}}
        finally{if(expected==epoch.get()&&version==listVersion.get())mutable.update {it.copy(records=it.records.copy(loading=false))}}
        return committed && expected==epoch.get() && version==listVersion.get() && !state.value.records.loading && state.value.records.error==null
    }
    suspend fun prepareCheckout(id:String):PreparedPayment? {
        val row=record(id)?:return null;val scope=state.value.scope?:return null;if(isDemo||!canPay(row))return null
        val expected=epoch.get();val reference=UUID.randomUUID().toString();listVersion.incrementAndGet()
        mutable.update{it.copy(checkout=PaymentCheckout(PaymentCheckoutPhase.PREPARING,id,reference),error=null)}
        try {
            val rows=api.payments(scope);if(!matches(expected,reference))return null
            mutable.update {it.copy(records=MemberLoad(rows))}
            val current=record(id)
            if(current?.status!="pending" || !current.onlineAvailable){stop(id,text(PaymentStrings.PAYMENT_THIS_PAYMENT_IS_NO_LONGER_AVAILABLE_TO_PAY_ONLINE_ITS_LATEST));return null}
            val input=api.intent(scope,id);if(!matches(expected,reference))return null
            require(input.paymentId==id && input.valid()) {text(PaymentStrings.PAYMENT_PAYMENT_SETUP_IS_UNAVAILABLE_CONTACT_YOUR_MAKERSPACE)}
            mutable.update {it.copy(checkout=PaymentCheckout(PaymentCheckoutPhase.PRESENTING,id,reference))}
            return PreparedPayment(reference,input)
        }catch(c:CancellationException){if(matches(expected,reference))stop(id,text(PaymentStrings.PAYMENT_CHECKOUT_WAS_INTERRUPTED_CHECK_THE_PAYMENT_STATUS_BEFORE_TRY));throw c}
        catch(f:Exception){val message=failure(f,expected);if(matches(expected,reference))stop(id,message);return null}
    }
    suspend fun sdkClosed(reference:String,reason:SheetCloseReason,message:String?=null):Boolean = closePresentation(reference,reason,message)!=null
    /** Null means no matching attempt; false means accepted but no authoritative final read. */
    private suspend fun closePresentation(reference:String,reason:SheetCloseReason,message:String?,current:()->Boolean={true}):Boolean? {
        val checkout=state.value.checkout;val scope=state.value.scope?:return null
        if(!current() || checkout.phase!=PaymentCheckoutPhase.PRESENTING || checkout.reference!=reference)return null
        val id=checkout.paymentId?:return null;val expected=epoch.get()
        mutable.update {it.copy(checkout=checkout.copy(phase=PaymentCheckoutPhase.CHECKING))}
        return reconcile(scope,id,reference,expected,reason,message,current)
    }
    /** A restored opaque reference never identifies a charge. Only a fresh scoped API read permits acknowledgement. */
    suspend fun reconcileNativeCallback(reference:String,reason:SheetCloseReason,message:String?=null,
        current:()->Boolean={true},acknowledge:()->Unit={}):Boolean {
        val scope=state.value.scope?:return false;val expected=epoch.get()
        fun active()=expected==epoch.get() && state.value.scope==scope && current()
        if(isDemo || !active() || reference.isBlank() || reference in acknowledgedCallbacks)return false
        mutable.update {it.copy(callbackPending=true,callbackError=null)}
        if(!canRequest()){mutable.update {it.copy(callbackError=text(PaymentStrings.PAYMENT_CALLBACK_RETRY_AFTER_TIMER))};return false}
        val checkout=state.value.checkout
        if((state.value.busy && !(checkout.phase==PaymentCheckoutPhase.PRESENTING && checkout.reference==reference)) || !callbackLock.tryLock()) {
            mutable.update {it.copy(callbackError=text(PaymentStrings.PAYMENT_CALLBACK_RETRY))};return false
        }
        mutable.update {it.copy(callbackLoading=true)}
        try {
            val matched=closePresentation(reference,reason,message,current)
            if(!active())return false
            val refreshed=if(matched!=null)matched else {
                // Normal authorized list refresh only; no old outcome/message is assigned to any charge.
                val version=listVersion.incrementAndGet()
                val rows=api.payments(scope)
                if(!active() || version!=listVersion.get())return false
                mutable.update {it.copy(records=MemberLoad(rows))}
                if(reconcileVisible())loadDocuments()
                true
            }
            if(!active())return false
            if(!refreshed){mutable.update {it.copy(callbackError=text(PaymentStrings.PAYMENT_CALLBACK_RETRY),
                checkout=if(matched!=null && reason==SheetCloseReason.SUBMITTED && checkout.paymentId!=null)
                    PaymentCheckout(PaymentCheckoutPhase.AWAITING,checkout.paymentId,message=text(PaymentStrings.PAYMENT_CALLBACK_RETRY)) else it.checkout)};return false}
            acknowledge()
            if(expected==epoch.get() && state.value.scope==scope) {
                acknowledgedCallbacks+=reference
                mutable.update {it.copy(callbackPending=false,callbackError=null)}
            }
            return true
        }catch(cancelled:CancellationException){throw cancelled}
        catch(f:Exception){val detail=failure(f,expected);if(active())mutable.update {it.copy(callbackError=detail)};return false}
        finally {if(expected==epoch.get() && state.value.scope==scope)mutable.update {it.copy(callbackLoading=false,
            checkout=if(it.checkout.phase==PaymentCheckoutPhase.CHECKING && it.checkout.reference==reference)
                PaymentCheckout(PaymentCheckoutPhase.AWAITING,it.checkout.paymentId,message=text(PaymentStrings.PAYMENT_CALLBACK_RETRY)) else it.checkout)};callbackLock.unlock()}
    }
    suspend fun checkStatus(id:String){val scope=state.value.scope?:return;if(!canRequest()||state.value.busy||record(id)==null)return
        val reference=UUID.randomUUID().toString();val expected=epoch.get();listVersion.incrementAndGet()
        mutable.update {it.copy(checkout=PaymentCheckout(PaymentCheckoutPhase.CHECKING,id,reference))};reconcile(scope,id,reference,expected,SheetCloseReason.SUBMITTED,null)
    }
    private suspend fun reconcile(scope:MemberScope,id:String,reference:String,expected:Long,reason:SheetCloseReason,message:String?,current:()->Boolean={true}):Boolean {
        fun valid()=current() && matches(expected,reference)
        try {
            repeat(if(reason==SheetCloseReason.SUBMITTED)4 else 1){index->
                if(index>0)pause(index*1000L);if(!valid())return false
                if(!canRequest()){mutable.update {it.copy(checkout=PaymentCheckout(PaymentCheckoutPhase.AWAITING,id,message=text(PaymentStrings.PAYMENT_CHECK_THE_PAYMENT_STATUS_AFTER_THE_TIMER_FINISHES)))};return false}
                val rows=api.payments(scope);if(!valid())return false
                mutable.update {it.copy(records=MemberLoad(rows))}
                if(terminal(id)){loadDocuments();return expected==epoch.get() && state.value.scope==scope}
            }
            if(valid()) {
                if(reason==SheetCloseReason.SUBMITTED)mutable.update {it.copy(checkout=PaymentCheckout(PaymentCheckoutPhase.AWAITING,id,message=text(PaymentStrings.PAYMENT_YOUR_PAYMENT_IS_AWAITING_SERVER_CONFIRMATION_CHECK_ITS_STATU)))}
                else stop(id,message?:if(reason==SheetCloseReason.CANCELLED)text(PaymentStrings.PAYMENT_CHECKOUT_CLOSED_CHECK_THE_CURRENT_PAYMENT_STATUS_BEFORE_TRYI) else text(PaymentStrings.PAYMENT_CHECKOUT_COULD_NOT_FINISH_CHECK_THE_PAYMENT_STATUS_AND_TRY_A))
            }
            return expected==epoch.get() && state.value.scope==scope
        }catch(c:CancellationException){if(valid())mutable.update {it.copy(checkout=PaymentCheckout(PaymentCheckoutPhase.AWAITING,id,message=text(PaymentStrings.PAYMENT_CHECK_THE_PAYMENT_STATUS_BEFORE_TRYING_AGAIN)))};throw c}
        catch(f:Exception){if(current())failure(f,expected);if(valid())stop(id,text(PaymentStrings.PAYMENT_COULDN_T_CHECK_THIS_PAYMENT_REFRESH_ITS_STATUS_BEFORE_TRYING));return false}
    }
    private fun matches(expected:Long,reference:String)=expected==epoch.get() && state.value.checkout.reference==reference
    private fun stop(id:String,message:String){mutable.update {it.copy(checkout=PaymentCheckout(PaymentCheckoutPhase.STOPPED,id,message=message))}}
    private fun terminal(id:String):Boolean = when(record(id)?.status){
        "paid_online","paid_offline","waived"->{mutable.update {it.copy(checkout=PaymentCheckout(PaymentCheckoutPhase.COMPLETED,id))};true}
        "canceled"->{stop(id,text(PaymentStrings.PAYMENT_THIS_PAYMENT_WAS_CANCELED));true}
        else->false
    }
    private fun reconcileVisible():Boolean {val current=state.value.checkout;if(current.phase !in setOf(PaymentCheckoutPhase.AWAITING,PaymentCheckoutPhase.STOPPED,PaymentCheckoutPhase.COMPLETED))return false;return current.paymentId?.let(::terminal)==true}
    suspend fun loadDocuments(more:Boolean=false){val scope=state.value.scope?:return;val before=state.value
        if(!canRequest() || (more && (before.documents.loading||before.nextDocumentPage==null)))return
        val expected=epoch.get();val version=documentVersion.incrementAndGet();val page=if(more)before.nextDocumentPage!! else 1
        mutable.update {it.copy(documents=it.documents.copy(loading=true,error=null))}
        try {val result=api.documents(scope,page);require(result.nextPage==null||result.nextPage>page)
            if(expected==epoch.get()&&version==documentVersion.get()){
                val rows=(if(more)state.value.documents.value.orEmpty() else emptyList()).associateByTo(linkedMapOf()){it.id}
                result.rows.forEach {rows[it.id]=it};mutable.update {it.copy(documents=MemberLoad(rows.values.toList()),nextDocumentPage=result.nextPage)}
            }
        }catch(c:CancellationException){throw c}catch(f:Exception){val message=failure(f,expected);if(expected==epoch.get()&&version==documentVersion.get())mutable.update {it.copy(documents=it.documents.copy(loading=false,error=message))}}
        finally {if(expected==epoch.get()&&version==documentVersion.get())mutable.update {it.copy(documents=it.documents.copy(loading=false))}}
    }
    suspend fun openDocument(id:String){val scope=state.value.scope?:return;val document=state.value.documents.value?.firstOrNull{it.id==id}?:return
        if(!canRequest()||state.value.documentBusy)return;closePreview();val expected=epoch.get();val version=previewVersion.incrementAndGet()
        mutable.update {it.copy(documentBusy=true,documentError=null)};var prepared:PaymentFile?=null
        try {val bytes=api.documentPDF(scope,id);currentCoroutineContext().ensureActive();if(expected!=epoch.get()||version!=previewVersion.get())return
            withContext(NonCancellable+Dispatchers.IO){prepared=files.create(bytes,document.number)}
            currentCoroutineContext().ensureActive()
            if(expected==epoch.get()&&version==previewVersion.get()){mutable.update {it.copy(preview=prepared)};prepared=null}
        }catch(c:CancellationException){throw c}catch(f:Exception){val message=failure(f,expected);if(expected==epoch.get()&&version==previewVersion.get())mutable.update {it.copy(documentError=message)}}
        finally{runCatching{prepared?.close()};if(expected==epoch.get()&&version==previewVersion.get())mutable.update {it.copy(documentBusy=false)}}
    }
    suspend fun simulate(id:String,scenario:DemoPaymentScenario){val demo=api as? PaymentPreviewControl?:return;val row=record(id)?:return;val scope=state.value.scope?:return;if(!canPay(row))return
        val expected=epoch.get();val reference=UUID.randomUUID().toString();mutable.update {it.copy(checkout=PaymentCheckout(PaymentCheckoutPhase.PRESENTING,id,reference))}
        try {demo.scenario(scope,id,scenario);if(!matches(expected,reference))return;sdkClosed(reference,when(scenario){DemoPaymentScenario.CANCELLED->SheetCloseReason.CANCELLED;DemoPaymentScenario.FAILED->SheetCloseReason.FAILED;else->SheetCloseReason.SUBMITTED})}
        catch(c:CancellationException){if(matches(expected,reference))stop(id,text(PaymentStrings.PAYMENT_DEMO_CHECKOUT_CANCELLED));throw c}catch(f:Exception){val message=failure(f,expected);if(matches(expected,reference))stop(id,message)}
    }
    suspend fun confirmDemoDelayed(id:String){val demo=api as? PaymentPreviewControl?:return;val scope=state.value.scope?:return;if(state.value.checkout.phase!=PaymentCheckoutPhase.AWAITING)return;demo.confirmDelayed(scope,id);checkStatus(id)}
}
