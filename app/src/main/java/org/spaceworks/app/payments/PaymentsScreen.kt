package org.spaceworks.app.payments

import org.spaceworks.app.network.retrySecondsRemaining

import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.rememberMobileText

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.spaceworks.app.platform.*
import org.spaceworks.app.ui.*

/** The Activity registers adapters before STARTED and passes their current callback state here. */
@Composable fun PaymentsScreen(holder:PaymentsStateHolder,demo:Boolean,onBack:()->Unit,
    nativeState:NativePaymentState=NativePaymentState.Idle,
    launchCheckout:(NativeSheetInput)->PaymentLaunchResult={PaymentLaunchResult.HOST_NOT_READY},
    acknowledgeNative:()->Unit={},recoverInterrupted:()->Unit={},refreshOnEnter:Boolean=true,hostAvailable:Boolean=true) {
    val text=rememberMobileText()
    val s by holder.state.collectAsStateWithLifecycle();val work=rememberCoroutineScope();val context=LocalContext.current
    val latestNativeState by rememberUpdatedState(nativeState)
    val latestHolder by rememberUpdatedState(holder)
    val latestAcknowledge by rememberUpdatedState(acknowledgeNative)
    suspend fun reconcileCallback(value:NativePaymentState.AwaitingServer) {
        holder.reconcileNativeCallback(value.paymentReference,value.reason,value.message,
            current={latestHolder===holder && latestNativeState==value},acknowledge={latestAcknowledge()})
    }
    var dueFilter by remember(s.scope){mutableStateOf<String?>(null)}
    var demoCheckout by remember(s.scope){mutableStateOf<String?>(null)}
    var clock by remember{mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(s.retryAtMillis){do{clock=System.currentTimeMillis();if(clock<s.retryAtMillis)kotlinx.coroutines.delay(250)}while(clock<s.retryAtMillis)}
    LaunchedEffect(holder,s.scope){if(refreshOnEnter && s.scope!=null && s.records.value==null && (demo || nativeState !is NativePaymentState.AwaitingServer))holder.refresh()}
    LaunchedEffect(s.showingDocuments,s.scope){if(s.showingDocuments && s.documents.value==null)holder.loadDocuments()}
    LaunchedEffect(holder,nativeState,s.scope){if(!demo && nativeState is NativePaymentState.AwaitingServer)reconcileCallback(nativeState)}
    fun back(){if(s.preview!=null)holder.closePreview() else if(s.showingDocuments||s.selectedId!=null)holder.closeDetail() else if(!s.busy)onBack()}
    BackHandler{back()}
    ProtectSecrets(!demo && s.preview!=null)
    s.preview?.let{NativeDocumentPanel(it){holder.closePreview()}}
    val row=s.selectedId?.let(holder::record)
    val enabled=hostAvailable && !s.busy && clock>=s.retryAtMillis
    val providerBusy=!demo && nativeState !is NativePaymentState.Idle
    if(demoCheckout!=null && demo)Dialog(onDismissRequest={demoCheckout=null},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.widthIn(max=560.dp).fillMaxWidth(.94f).fillMaxHeight(.94f),shape=MaterialTheme.shapes.large) {
            Column(Modifier.testTag("demo-payment-outcome-scroll").verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(text(PaymentStrings.PAYMENT_PREVIEW_CHECKOUT),style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics{heading()})
                Text(text(PaymentStrings.PAYMENT_SAMPLE_PAYMENTS_ONLY_NO_PAYMENT_PROVIDER_OPENS_AND_NO_MONEY),style=MaterialTheme.typography.bodyMedium)
                DemoPaymentScenario.entries.forEach{scenario->BrandTextButton(demoPaymentLabel(scenario,text),{val id=demoCheckout;demoCheckout=null;if(id!=null)work.launch{holder.simulate(id,scenario)}},modifier=Modifier.fillMaxWidth())}
                BrandTextButton(text(PaymentStrings.PAYMENT_KEEP_REVIEWING),{demoCheckout=null},modifier=Modifier.fillMaxWidth())
            }
        }
    }
    Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.TopCenter){
        Column(Modifier.widthIn(max=800.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            BrandTextButton(text(PaymentStrings.PAYMENT_BACK),{back()},!s.busy)
            Text(if(s.showingDocuments)text(PaymentStrings.PAYMENT_RECEIPTS_AND_CREDIT_NOTES) else if(row!=null)row.label else text(PaymentStrings.PAYMENT_PAYMENTS),style=MaterialTheme.typography.headlineLarge,modifier=Modifier.semantics{heading()})
            if(demo)Text(text(PaymentStrings.PAYMENT_DEMO_PAYMENTS_SAMPLE_DATA),color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(clock<s.retryAtMillis)Text(text(PaymentStrings.PAYMENT_TRY_AGAIN_IN_1_SS, retrySecondsRemaining(clock,s.retryAtMillis)),Modifier.semantics{liveRegion=LiveRegionMode.Polite})
            BrandError(s.error)
            if(!demo && nativeState is NativePaymentState.AwaitingServer)PaymentCard {
                Text(text(PaymentStrings.PAYMENT_CALLBACK_PENDING),Modifier.semantics{liveRegion=LiveRegionMode.Polite})
                BrandError(s.callbackError)
                val loadingLabel=text(PaymentStrings.PAYMENT_CALLBACK_LOADING)
                if(s.callbackLoading)LinearProgressIndicator(Modifier.fillMaxWidth().semantics{contentDescription=loadingLabel})
                BrandTextButton(text(PaymentStrings.PAYMENT_CALLBACK_REFRESH),{work.launch{reconcileCallback(nativeState)}},enabled&&!s.callbackLoading)
            }
            if(s.showingDocuments){
                Text(text(PaymentStrings.PAYMENT_OPEN_OR_SHARE_DOCUMENTS_ISSUED_BY_YOUR_MAKERSPACE))
                if(s.documents.loading)LinearProgressIndicator(Modifier.fillMaxWidth().semantics{contentDescription=text(PaymentStrings.PAYMENT_LOADING_DOCUMENTS)})
                BrandError(s.documents.error);BrandError(s.documentError)
                if(s.documentBusy)LinearProgressIndicator(Modifier.fillMaxWidth().semantics{contentDescription=text(PaymentStrings.PAYMENT_PREPARING_DOCUMENT)})
                val documents=s.documents.value.orEmpty().filter{s.documentPaymentId==null||it.paymentId==s.documentPaymentId}
                if(s.documents.value!=null&&documents.isEmpty())Text(if(s.nextDocumentPage!=null&&s.documentPaymentId!=null)text(PaymentStrings.PAYMENT_NO_MATCHING_DOCUMENTS_ON_THE_LOADED_PAGES_LOAD_MORE_TO_CONTI) else text(PaymentStrings.PAYMENT_NO_ISSUED_DOCUMENTS_YET))
                documents.forEach{document->PaymentCard{
                    Text(if(document.kind=="credit_note")text(PaymentStrings.PAYMENT_CREDIT_NOTE_1_S, document.number) else text(PaymentStrings.PAYMENT_RECEIPT_1_S, document.number),style=MaterialTheme.typography.titleLarge)
                    Money(document.amount,document.currency);Text(memberDate(document.issuedAt))
                    BrandTextButton(text(PaymentStrings.PAYMENT_OPEN_PDF),{work.launch{holder.openDocument(document.id)}},enabled&&!s.documentBusy,Modifier.semantics{contentDescription=text(PaymentStrings.PAYMENT_OPEN_PDF_1_S, document.number)})
                }}
                if(s.nextDocumentPage!=null)BrandTextButton(text(PaymentStrings.PAYMENT_LOAD_MORE_DOCUMENTS),{work.launch{holder.loadDocuments(true)}},enabled&&!s.documents.loading)
                BrandTextButton(text(PaymentStrings.PAYMENT_REFRESH_DOCUMENTS),{work.launch{holder.loadDocuments()}},enabled&&!s.documents.loading)
            } else if(row!=null){
                PaymentCard{
                    Money(row.amount,row.currency);Text(paymentStatus(row.status),style=MaterialTheme.typography.titleMedium)
                    row.dueDate?.takeIf{it.isNotBlank()}?.let{Text(text(PaymentStrings.PAYMENT_DUE_1_S, it))};Text(text(PaymentStrings.PAYMENT_CHARGE_1_S, row.id),style=MaterialTheme.typography.bodySmall)
                }
                row.settlement?.let{settlement->PaymentCard{Text(text(PaymentStrings.PAYMENT_RECEIVED_AT_THE_DESK),style=MaterialTheme.typography.titleLarge);Text(memberDate(settlement.receivedAt));Text(text(PaymentStrings.PAYMENT_METHOD_1_S, settlement.method));if(settlement.reference.isNotBlank())Text(text(PaymentStrings.PAYMENT_REFERENCE_1_S, settlement.reference))}}
                if(s.checkout.paymentId==row.id){
                    val checkoutWorkingLabel=when(s.checkout.phase){PaymentCheckoutPhase.PREPARING->text(PaymentStrings.PAYMENT_PREPARING_CHECKOUT);PaymentCheckoutPhase.PRESENTING->text(PaymentStrings.PAYMENT_CHECKOUT_OPEN);else->text(PaymentStrings.PAYMENT_CHECKING_PAYMENT_STATUS)}
                    if(s.busy)LinearProgressIndicator(Modifier.fillMaxWidth().semantics{contentDescription=checkoutWorkingLabel})
                    s.checkout.message?.let{Text(it,Modifier.semantics{liveRegion=LiveRegionMode.Polite})}
                    if(s.checkout.phase==PaymentCheckoutPhase.COMPLETED)Text(paymentStatus(row.status),Modifier.semantics{liveRegion=LiveRegionMode.Polite})
                    if(demo&&s.checkout.phase==PaymentCheckoutPhase.AWAITING)BrandTextButton(text(PaymentStrings.PAYMENT_SIMULATE_LATER_SERVER_CONFIRMATION),{work.launch{holder.confirmDemoDelayed(row.id)}},enabled)
                }
                if(!demo && nativeState is NativePaymentState.Presenting && s.checkout.reference!=nativeState.paymentReference){
                    Text(text(PaymentStrings.PAYMENT_CHECKOUT_WAS_INTERRUPTED_CHECK_THE_PAYMENT_STATUS_BEFORE_STA))
                    BrandTextButton(text(PaymentStrings.PAYMENT_RECOVER_INTERRUPTED_CHECKOUT),recoverInterrupted,enabled)
                }
                if(holder.canPay(row)){
                    Text(text(PaymentStrings.PAYMENT_REVIEW_THE_AMOUNT_ABOVE_YOUR_PAYMENT_PROVIDER_WILL_OPEN_TO_C))
                    BrandButton(text(PaymentStrings.PAYMENT_CONTINUE_TO_PAYMENT),{
                        if(demo)demoCheckout=row.id else work.launch{
                            val prepared=holder.prepareCheckout(row.id)?:return@launch
                            val appName=context.applicationInfo.loadLabel(context.packageManager).toString()
                            val result=launchCheckout(when(val input=prepared.intent){
                                is PaymentIntent.Stripe->StripeSheetInput(prepared.reference,appName,input.publishableKey,input.clientSecret,input.customerId,input.ephemeralKey)
                                is PaymentIntent.Razorpay->RazorpaySheetInput(prepared.reference,input.name.ifBlank {appName},input.keyId,input.orderId,input.amountMinor,input.currency,input.description)
                            })
                            if(result!=PaymentLaunchResult.STARTED)holder.sdkClosed(prepared.reference,SheetCloseReason.FAILED,when(result){PaymentLaunchResult.HOST_NOT_READY->text(PaymentStrings.PAYMENT_CHECKOUT_IS_NOT_READY_TRY_AGAIN_FROM_PAYMENT_DETAILS);PaymentLaunchResult.ALREADY_PRESENTING,PaymentLaunchResult.AWAITING_SERVER_REFRESH->text(PaymentStrings.PAYMENT_FINISH_THE_CURRENT_CHECKOUT_AND_CHECK_ITS_PAYMENT_STATUS_BEF);else->text(PaymentStrings.PAYMENT_PAYMENT_SETUP_IS_UNAVAILABLE_CONTACT_YOUR_MAKERSPACE)})
                        }
                    },enabled&&!providerBusy)
                } else if(row.status=="pending"&&!row.onlineAvailable)Text(text(PaymentStrings.PAYMENT_ONLINE_PAYMENT_IS_UNAVAILABLE_ASK_YOUR_MAKERSPACE_ABOUT_PAYI))
                BrandTextButton(text(PaymentStrings.PAYMENT_CHECK_PAYMENT_STATUS),{work.launch{holder.checkStatus(row.id)}},enabled)
                if(row.refunds.isNotEmpty())Text(text(PaymentStrings.PAYMENT_REFUNDS),style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics{heading()})
                row.refunds.forEach{refund->PaymentCard{Money(refund.amount,refund.currency);Text(refund.status.replace('_',' '));if(refund.reason.isNotBlank())Text(refund.reason);if(refund.reference.isNotBlank())Text(text(PaymentStrings.PAYMENT_REFERENCE_1_S, refund.reference))}}
                BrandTextButton(text(PaymentStrings.PAYMENT_RECEIPTS_AND_CREDIT_NOTES),{holder.showDocuments(row.id)},enabled)
            } else {
                Text(text(PaymentStrings.PAYMENT_SEE_YOUR_CHARGES_PAYMENT_STATUS_AND_ISSUED_DOCUMENTS))
                if(s.records.loading)LinearProgressIndicator(Modifier.fillMaxWidth().semantics{contentDescription=text(PaymentStrings.PAYMENT_LOADING_PAYMENTS)})
                BrandError(s.records.error)
                Text(text(PaymentStrings.PAYMENT_DUE_DATE),style=MaterialTheme.typography.titleMedium)
                listOf(null to text(PaymentStrings.PAYMENT_ALL_CHARGES),"overdue" to text(PaymentStrings.PAYMENT_OVERDUE),"due_today" to text(PaymentStrings.PAYMENT_DUE_TODAY),"upcoming" to text(PaymentStrings.PAYMENT_UPCOMING)).forEach{(filter,label)->
                    FilterChip(selected=dueFilter==filter,onClick={dueFilter=filter},label={Text(label)},modifier=Modifier.heightIn(min=48.dp))
                }
                val rows=s.records.value.orEmpty().filter{dueFilter==null||it.dueState==dueFilter}
                if(s.records.value!=null&&rows.isEmpty())PaymentCard{Text(if(dueFilter==null)text(PaymentStrings.PAYMENT_NO_CHARGES_HERE) else text(PaymentStrings.PAYMENT_NO_CHARGES_MATCH_THIS_FILTER),style=MaterialTheme.typography.titleLarge);Text(if(dueFilter==null)text(PaymentStrings.PAYMENT_YOUR_PAYMENTS_WILL_APPEAR_HERE_WHEN_YOUR_MAKERSPACE_ISSUES_A) else text(PaymentStrings.PAYMENT_CHOOSE_ANOTHER_FILTER_TO_SEE_THE_OTHER_CHARGES))}
                rows.forEach{payment->PaymentCard{
                    Text(payment.label,style=MaterialTheme.typography.titleLarge);Money(payment.amount,payment.currency);Text(paymentStatus(payment.status));payment.dueDate?.takeIf{it.isNotBlank()}?.let{Text(text(PaymentStrings.PAYMENT_DUE_1_S, it))}
                    BrandTextButton(text(PaymentStrings.PAYMENT_REVIEW_PAYMENT),{holder.open(payment.id)},enabled,Modifier.semantics{contentDescription=text(PaymentStrings.PAYMENT_REVIEW_PAYMENT_1_S, payment.label)})
                }}
                BrandTextButton(text(PaymentStrings.PAYMENT_REFRESH_PAYMENTS),{work.launch{holder.refresh()}},enabled&&!s.records.loading)
                BrandTextButton(text(PaymentStrings.PAYMENT_RECEIPTS_AND_CREDIT_NOTES),{holder.showDocuments()},enabled)
            }
        }
    }
}
@Composable private fun PaymentCard(content:@Composable ColumnScope.()->Unit){OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)}}
private fun demoPaymentLabel(scenario:DemoPaymentScenario,text:MobileText)=text(when(scenario){
    DemoPaymentScenario.PAID->PaymentStrings.PAYMENT_DEMO_SERVER_CONFIRMS
    DemoPaymentScenario.CANCELLED->PaymentStrings.PAYMENT_DEMO_CANCELLED
    DemoPaymentScenario.FAILED->PaymentStrings.PAYMENT_DEMO_FAILED
    DemoPaymentScenario.DELAYED->PaymentStrings.PAYMENT_DEMO_DELAYED
    DemoPaymentScenario.OFFLINE->PaymentStrings.PAYMENT_DEMO_OFFLINE
    DemoPaymentScenario.REFUSED->PaymentStrings.PAYMENT_DEMO_UNAVAILABLE
})
@Composable private fun Money(amount:String,currency:String){Text("${currency.uppercase()} $amount",style=MaterialTheme.typography.titleLarge)}
@Composable fun paymentStatus(value:String?):String {val text=rememberMobileText();return when(value){"pending"->text(PaymentStrings.PAYMENT_PAYMENT_PENDING);"paid_online"->text(PaymentStrings.PAYMENT_PAID_ONLINE);"paid_offline"->text(PaymentStrings.PAYMENT_PAID_AT_THE_DESK);"waived"->text(PaymentStrings.PAYMENT_CHARGE_WAIVED);"canceled"->text(PaymentStrings.PAYMENT_CANCELED);else->text(PaymentStrings.PAYMENT_STATUS_UNAVAILABLE)}}
