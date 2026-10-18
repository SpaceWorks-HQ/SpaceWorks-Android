package org.spaceworks.app.payments

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.platform.*

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentsStateTest {
    @Test fun razorpayIntentIsValidatedBeforePresentation()=runTest {
        val api=FakeGateway().apply{intentBlock={_,_->PaymentIntent.Razorpay("501","rzp_test_key","order_1",25025,"INR","Fab Lab","Laser")}}
        val holder=holder(api)
        val prepared=holder.prepareCheckout("501")!!
        assertTrue(prepared.intent is PaymentIntent.Razorpay);assertEquals(25025L,(prepared.intent as PaymentIntent.Razorpay).amountMinor)
        val invalid=FakeGateway().apply{intentBlock={_,_->PaymentIntent.Razorpay("501","pk_wrong","order_1",0,"INR","Fab Lab","")}}
        val other=holder(invalid)
        assertNull(other.prepareCheckout("501"));assertEquals(PaymentCheckoutPhase.STOPPED,other.state.value.checkout.phase)
    }
    @Test fun supersededPaymentRouteCannotTreatCachedRowsAsItsOwnFreshRead()=runTest {
        val api=FakeGateway();val holder=holder(api)
        val firstRead=CompletableDeferred<List<PaymentRecord>>();val secondRead=CompletableDeferred<List<PaymentRecord>>()
        var requests=0;api.paymentBlock={if(requests++==0)firstRead.await() else secondRead.await()}
        val route=async {holder.openFromRoute("501")};runCurrent()
        val latest=async {holder.refresh()};runCurrent()
        firstRead.complete(listOf(row()))
        assertFalse(route.await());assertNull(holder.state.value.selectedId);assertTrue(holder.state.value.records.loading)
        secondRead.complete(emptyList());assertTrue(latest.await());assertTrue(holder.state.value.records.value!!.isEmpty())
        assertEquals(0,api.intentCalls)
    }
    @Test fun paymentRouteRequiresFreshListAndNeverOpensRefusedCachedOrMissingCharge()=runTest {
        val api=FakeGateway();val holder=holder(api)
        assertTrue(holder.openFromRoute("501"));assertEquals("501",holder.state.value.selectedId)
        holder.closeDetail();api.paymentBlock={throw ApiFailure(503,"Server unavailable.",null,null)}
        assertFalse(holder.openFromRoute("501"));assertNull(holder.state.value.selectedId)
        api.paymentBlock={emptyList()}
        assertFalse(holder.openFromRoute("501"));assertNull(holder.state.value.selectedId)
        assertEquals(0,api.intentCalls)
    }
    @Test fun delayedPaymentRouteCannotOpenAfterMakerspaceReplacement()=runTest {
        val api=FakeGateway();val holder=holder(api);val gate=CompletableDeferred<List<PaymentRecord>>()
        api.paymentBlock={gate.await()};val pending=async {holder.openFromRoute("501")};runCurrent()
        holder.activate(second);gate.complete(listOf(row()))
        assertFalse(pending.await());assertNull(holder.state.value.selectedId);assertNull(holder.state.value.records.value)
        assertEquals(second,holder.state.value.scope);assertEquals(0,api.intentCalls)
    }
    @get:Rule val temporary=TemporaryFolder()
    private val first=MemberScope(7,"first");private val second=MemberScope(8,"second")
    private suspend fun holder(api:PaymentsGateway,now:()->Long={0L},files:PaymentFileStore=CachedPaymentFiles(temporary.newFolder()),unauthorized:suspend()->Unit={},forbidden:suspend()->Unit={}):PaymentsStateHolder =
        PaymentsStateHolder(api,files,now,pause={},onUnauthorized=unauthorized,onForbidden=forbidden).apply{activate(first);refresh()}

    @Test fun submittedSDKOnlyAwaitsServerAndLaterRefreshConfirmsPaymentAndDocuments()=runTest {
        val api=FakeGateway();val holder=holder(api)
        val prepared=holder.prepareCheckout("501")!!
        assertEquals(PaymentCheckoutPhase.PRESENTING,holder.state.value.checkout.phase)
        assertTrue(holder.sdkClosed(prepared.reference,SheetCloseReason.SUBMITTED))
        assertEquals(PaymentCheckoutPhase.AWAITING,holder.state.value.checkout.phase)
        assertEquals("pending",holder.record("501")!!.status);assertEquals(6,api.paymentCalls)
        assertFalse(holder.canPay(holder.record("501")!!));assertEquals(0,api.documentCalls)
        api.rows=listOf(row(status="paid_online"));holder.refresh()
        assertEquals(PaymentCheckoutPhase.COMPLETED,holder.state.value.checkout.phase)
        assertEquals("paid_online",holder.record("501")!!.status);assertEquals(1,api.documentCalls)
    }
    @Test fun latestServerPaidStatusPreventsEvenCreatingAnIntent()=runTest {
        val api=FakeGateway();val holder=holder(api);api.rows=listOf(row(status="paid_offline"))
        assertNull(holder.prepareCheckout("501"));assertEquals(0,api.intentCalls)
        assertEquals("paid_offline",holder.record("501")!!.status);assertFalse(holder.state.value.busy)
    }
    @Test fun unavailableOrUnknownStatusCannotOfferAnOnlinePayment()=runTest {
        val api=FakeGateway();val holder=holder(api)
        listOf(row(status=null),row(status="waived"),row().copy(onlineAvailable=false)).forEach{assertFalse(holder.canPay(it))}
        assertFalse(holder.isDemo)
    }
    @Test fun invalidIntentCannotReachTheSDKAndSecretsDoNotEnterDisplayState()=runTest {
        val api=FakeGateway().apply{intentBlock={_,_->PaymentIntent("502","sensitive-client-secret","pk_test_owner",null,null)}}
        val holder=holder(api);assertNull(holder.prepareCheckout("501"))
        assertEquals(PaymentCheckoutPhase.STOPPED,holder.state.value.checkout.phase)
        assertFalse(holder.state.value.toString().contains("sensitive-client-secret"))
        assertEquals("PaymentIntent(redacted)",api.intentBlock(first,"501").toString())
    }
    @Test fun checkoutCancellationAndFailureRefetchWithoutClaimingPaid()=runTest {
        for(reason in listOf(SheetCloseReason.CANCELLED,SheetCloseReason.FAILED,SheetCloseReason.INTERRUPTED)){
            val api=FakeGateway();val holder=holder(api);val prepared=holder.prepareCheckout("501")!!
            holder.sdkClosed(prepared.reference,reason)
            assertEquals(3,api.paymentCalls);assertEquals(PaymentCheckoutPhase.STOPPED,holder.state.value.checkout.phase)
            assertEquals("pending",holder.record("501")!!.status);assertFalse(holder.state.value.busy)
        }
    }
    @Test fun lateProviderCallbackCannotAffectAnotherMakerspaceOrAccount()=runTest {
        val api=FakeGateway();val holder=holder(api);val old=holder.prepareCheckout("501")!!
        holder.clear();holder.activate(second);holder.refresh()
        val calls=api.paymentCalls
        assertFalse(holder.sdkClosed(old.reference,SheetCloseReason.SUBMITTED));assertEquals(calls,api.paymentCalls)
        assertEquals(second,holder.state.value.scope);assertEquals(PaymentCheckoutPhase.IDLE,holder.state.value.checkout.phase)
    }
    @Test fun delayedOldIntentCannotLaunchInTheNewScope()=runTest {
        val gate=CompletableDeferred<PaymentIntent>();val api=FakeGateway().apply{intentBlock={_,_->gate.await()}}
        val holder=holder(api);val preparing=async{holder.prepareCheckout("501")};runCurrent()
        holder.activate(second);holder.refresh();gate.complete(intent());assertNull(preparing.await())
        assertEquals(second,holder.state.value.scope);assertEquals(PaymentCheckoutPhase.IDLE,holder.state.value.checkout.phase)
    }
    @Test fun cancellingServerPollClearsBusyAndLeavesAnHonestAwaitingState()=runTest {
        val gate=CompletableDeferred<List<PaymentRecord>>();val api=FakeGateway();val holder=holder(api);val prepared=holder.prepareCheckout("501")!!
        api.paymentBlock={_->gate.await()};val polling=launch{holder.sdkClosed(prepared.reference,SheetCloseReason.SUBMITTED)};runCurrent()
        polling.cancelAndJoin();assertFalse(holder.state.value.busy);assertEquals(PaymentCheckoutPhase.AWAITING,holder.state.value.checkout.phase)
        assertEquals("pending",holder.record("501")!!.status)
    }
    @Test fun retryAfterBlocksReadWriteAndDocumentWorkUntilTheActualDeadline()=runTest {
        var clock=1000L;val api=FakeGateway();val holder=holder(api,now={clock})
        api.paymentBlock={throw ApiFailure(429,"Please wait.","throttled",10)};holder.refresh()
        assertEquals(11000L,holder.state.value.retryAtMillis);val count=api.paymentCalls
        holder.refresh();holder.loadDocuments();assertNull(holder.prepareCheckout("501"))
        assertEquals(count,api.paymentCalls);assertEquals(0,api.documentCalls)
        holder.activate(second);assertEquals(11000L,holder.state.value.retryAtMillis)
        clock=11000L;api.paymentBlock={api.rows};holder.refresh();assertEquals(count+1,api.paymentCalls)
    }
    @Test fun sdkClosureCannotBypassAnExistingServerCooldown()=runTest {
        var clock=1000L;val api=FakeGateway();val holder=holder(api,now={clock});val prepared=holder.prepareCheckout("501")!!
        api.documentBlock={_,_->throw ApiFailure(429,"Please wait.","throttled",10)};holder.loadDocuments()
        val calls=api.paymentCalls;holder.sdkClosed(prepared.reference,SheetCloseReason.SUBMITTED)
        assertEquals(calls,api.paymentCalls);assertEquals(PaymentCheckoutPhase.AWAITING,holder.state.value.checkout.phase)
        assertFalse(holder.state.value.busy);clock=11000L;holder.checkStatus("501");assertEquals(calls+4,api.paymentCalls)
    }
    @Test fun unauthorizedAndForbiddenClearAllPrivateRecordsAndPreview()=runTest {
        for(status in listOf(401,403)){
            var unauthorized=0;var forbidden=0;val api=FakeGateway();val holder=holder(api,unauthorized={unauthorized++},forbidden={forbidden++})
            holder.loadDocuments();holder.openDocument("901");val file=holder.state.value.preview!!.file;assertTrue(file.exists())
            api.paymentBlock={throw ApiFailure(status,"Access changed.",null,null)};holder.refresh()
            assertFalse(file.exists());assertNull(holder.state.value.preview);assertNull(holder.state.value.records.value);assertNull(holder.state.value.documents.value)
            assertEquals(if(status==401)1 else 0,unauthorized);assertEquals(if(status==403)1 else 0,forbidden)
        }
    }
    @Test fun actualNextPageIsUsedAndUpdatedDocumentsMergeByRealID()=runTest {
        val api=FakeGateway().apply{documentBlock={_,page->if(page==1)PaymentDocumentPage(listOf(document()),3,2) else PaymentDocumentPage(listOf(document().copy(number="UPDATED"),document("902")),null,2)}}
        val holder=holder(api);holder.loadDocuments();holder.loadDocuments(true)
        assertEquals(listOf(1,3),api.documentPages);assertEquals(2,holder.state.value.documents.value!!.size)
        assertEquals("UPDATED",holder.state.value.documents.value!!.first().number);assertNull(holder.state.value.nextDocumentPage)
    }
    @Test fun latePaginationCannotRestoreDocumentsAfterANewerRefresh()=runTest {
        val gate=CompletableDeferred<PaymentDocumentPage>();val api=FakeGateway().apply{documentBlock={_,page->if(page==1)PaymentDocumentPage(listOf(document()),2,2) else gate.await()}}
        val holder=holder(api);holder.loadDocuments();val old=async{holder.loadDocuments(true)};runCurrent()
        api.documentBlock={_,_->PaymentDocumentPage(listOf(document("903")),null,1)};holder.loadDocuments()
        gate.complete(PaymentDocumentPage(listOf(document("902")),null,2));old.await()
        assertEquals(listOf("903"),holder.state.value.documents.value!!.map{it.id})
    }
    @Test fun onlyKnownDocumentCanBeOpenedAndCloseDeletesItsPrivateFile()=runTest {
        val api=FakeGateway();val holder=holder(api);holder.loadDocuments()
        holder.openDocument("999");assertEquals(0,api.pdfCalls)
        holder.openDocument("901");assertEquals(1,api.pdfCalls);val file=holder.state.value.preview!!.file
        assertTrue(file.name.endsWith(".pdf"));assertTrue(file.exists());holder.closePreview();assertFalse(file.exists());assertNull(holder.state.value.preview)
    }
    @Test fun invalidPDFBytesCannotBecomeAnActionablePreview()=runTest {
        val api=FakeGateway().apply{pdfBlock={_,_->"<html>sign in</html>".toByteArray()}};val holder=holder(api);holder.loadDocuments();holder.openDocument("901")
        assertNull(holder.state.value.preview);assertNotNull(holder.state.value.documentError);assertFalse(holder.state.value.documentBusy)
    }
    @Test fun cancellationAfterFileCreationDeletesTheUndeliveredDocument()=runTest {
        val created=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val file=AtomicReference<PaymentFile>()
        val directory=temporary.newFolder();val api=FakeGateway()
        val files=PaymentFileStore{bytes,title->NativeDocumentFiles(directory).create(bytes,NativeDocumentKind.PDF,title).also{file.set(it);created.complete(Unit);release.await()}}
        val holder=holder(api,files=files);holder.loadDocuments();val loading=launch{holder.openDocument("901")};created.await()
        loading.cancel();release.complete(Unit);loading.join()
        assertFalse(file.get().file.exists());assertNull(holder.state.value.preview);assertFalse(holder.state.value.documentBusy)
    }
    @Test fun demoNeverCreatesLiveIntentAndDelayedConfirmationRemainsServerDriven()=runTest {
        val fake=FakeGateway();var reset=0
        val preview=object:PaymentsGateway by fake,PaymentPreviewControl {
            override suspend fun scenario(scope:MemberScope,paymentId:String,value:DemoPaymentScenario){if(value==DemoPaymentScenario.PAID)fake.rows=listOf(row(status="paid_online"))}
            override suspend fun confirmDelayed(scope:MemberScope,paymentId:String){fake.rows=listOf(row(status="paid_online"))}
            override fun resetAccount(){reset++}
        }
        val holder=holder(preview);assertNull(holder.prepareCheckout("501"));assertEquals(0,fake.intentCalls)
        holder.simulate("501",DemoPaymentScenario.DELAYED);assertEquals(PaymentCheckoutPhase.AWAITING,holder.state.value.checkout.phase)
        holder.confirmDemoDelayed("501");assertEquals(PaymentCheckoutPhase.COMPLETED,holder.state.value.checkout.phase)
        assertEquals("paid_online",holder.record("501")!!.status);assertEquals(0,fake.intentCalls);holder.clear();assertEquals(1,reset)
    }
    @Test fun failedNativeStatusReadKeepsAdapterBlockedUntilExplicitFreshReadAndNeverRetriesTheCharge()=runTest {
        val api=FakeGateway();val holder=holder(api);val prepared=holder.prepareCheckout("501")!!
        val callbacks=PaymentCallbackState()
        callbacks.begin(prepared.reference,NativePaymentProvider.STRIPE,false)
        callbacks.finish(NativePaymentProvider.STRIPE,SheetCloseReason.SUBMITTED)
        val event=callbacks.state.value as NativePaymentState.AwaitingServer
        api.paymentBlock={throw IOException("offline")}
        assertFalse(holder.reconcileNativeCallback(event.paymentReference,event.reason,current={callbacks.state.value==event},acknowledge=callbacks::acknowledgeServerRefresh))
        assertEquals(3,api.paymentCalls);assertEquals(1,api.intentCalls)
        assertEquals(event,callbacks.state.value);assertTrue(holder.state.value.callbackPending)
        assertFalse(holder.state.value.callbackLoading);assertFalse(holder.canPay(holder.record("501")!!))
        assertEquals(PaymentCheckoutPhase.AWAITING,holder.state.value.checkout.phase)
        assertEquals(PaymentLaunchResult.AWAITING_SERVER_REFRESH,callbacks.begin("another-attempt",NativePaymentProvider.STRIPE,false))
        api.paymentBlock={api.rows}
        assertTrue(holder.reconcileNativeCallback(event.paymentReference,event.reason,current={callbacks.state.value==event},acknowledge=callbacks::acknowledgeServerRefresh))
        assertEquals(4,api.paymentCalls);assertEquals(NativePaymentState.Idle,callbacks.state.value)
        assertFalse(holder.state.value.callbackPending);assertNull(holder.prepareCheckout("501"))
        assertEquals(1,api.intentCalls);assertEquals(PaymentCheckoutPhase.AWAITING,holder.state.value.checkout.phase)
        api.rows=listOf(row(status="paid_online"));holder.refresh()
        assertEquals(PaymentCheckoutPhase.COMPLETED,holder.state.value.checkout.phase)
    }
    @Test fun orphanedReferenceOnlyRefreshesCurrentAuthorizedRowsWithoutAttributingAnOldOutcome()=runTest {
        val api=FakeGateway().apply{rows=listOf(row().copy(id="502",label="Current account charge"))}
        val holder=holder(api);var acknowledgements=0
        assertTrue(holder.reconcileNativeCallback("restored-opaque-reference",SheetCloseReason.SUBMITTED,"Old provider message",acknowledge={acknowledgements++}))
        assertEquals(2,api.paymentCalls);assertEquals(0,api.intentCalls);assertEquals(0,api.documentCalls)
        assertEquals(listOf("502"),holder.state.value.records.value!!.map{it.id})
        assertEquals(PaymentCheckoutPhase.IDLE,holder.state.value.checkout.phase)
        assertNull(holder.state.value.checkout.paymentId);assertNull(holder.state.value.checkout.message)
        assertFalse(holder.reconcileNativeCallback("restored-opaque-reference",SheetCloseReason.SUBMITTED,acknowledge={acknowledgements++}))
        assertEquals(1,acknowledgements);assertEquals(2,api.paymentCalls)
    }
    @Test fun nativeCallback429RequiresExplicitRetryAfterDeadlineAndThenAcknowledgesOnlyFreshData()=runTest {
        var clock=1000L;val api=FakeGateway();val holder=holder(api,now={clock});val prepared=holder.prepareCheckout("501")!!
        var acknowledgements=0
        api.paymentBlock={throw ApiFailure(429,"Please wait.","throttled",10)}
        assertFalse(holder.reconcileNativeCallback(prepared.reference,SheetCloseReason.SUBMITTED,acknowledge={acknowledgements++}))
        val calls=api.paymentCalls;assertEquals(11000L,holder.state.value.retryAtMillis)
        assertFalse(holder.reconcileNativeCallback(prepared.reference,SheetCloseReason.SUBMITTED,acknowledge={acknowledgements++}))
        assertEquals(calls,api.paymentCalls);assertEquals(0,acknowledgements)
        clock=11000L;api.paymentBlock={listOf(row(status="paid_online"))}
        assertTrue(holder.reconcileNativeCallback(prepared.reference,SheetCloseReason.SUBMITTED,acknowledge={acknowledgements++}))
        assertEquals(calls+1,api.paymentCalls);assertEquals(1,acknowledgements)
        assertEquals(PaymentCheckoutPhase.COMPLETED,holder.state.value.checkout.phase)
        assertEquals(1,api.intentCalls);assertFalse(holder.state.value.callbackPending)
    }
    @Test fun oldCallbackReadCannotAcknowledgeOrPublishIntoAnotherAccountAtTheSameMakerspace()=runTest {
        val api=FakeGateway();val holder=holder(api);val prepared=holder.prepareCheckout("501")!!
        val gate=CompletableDeferred<List<PaymentRecord>>();api.paymentBlock={gate.await()};var acknowledgements=0
        val pending=async{holder.reconcileNativeCallback(prepared.reference,SheetCloseReason.SUBMITTED,acknowledge={acknowledgements++})};runCurrent()
        holder.clear();holder.activate(first)
        api.rows=listOf(row().copy(id="502",label="New account charge"));api.paymentBlock={api.rows};holder.refresh()
        gate.complete(listOf(row(status="paid_online")));assertFalse(pending.await())
        assertEquals(0,acknowledgements);assertEquals(listOf("502"),holder.state.value.records.value!!.map{it.id})
        assertEquals(PaymentCheckoutPhase.IDLE,holder.state.value.checkout.phase);assertNull(holder.state.value.checkout.paymentId)
        assertFalse(holder.state.value.callbackLoading);assertFalse(holder.state.value.callbackPending)
    }
    @Test fun replacedNativeCallbackCannotPublishItsDeferredReadOrLeaveCheckoutBusy()=runTest {
        val api=FakeGateway();val holder=holder(api);val prepared=holder.prepareCheckout("501")!!
        val gate=CompletableDeferred<List<PaymentRecord>>();api.paymentBlock={gate.await()};var current=true;var acknowledgements=0
        val pending=async{holder.reconcileNativeCallback(prepared.reference,SheetCloseReason.SUBMITTED,current={current},acknowledge={acknowledgements++})};runCurrent()
        current=false;gate.complete(listOf(row(status="paid_online")));assertFalse(pending.await())
        assertEquals(0,acknowledgements);assertEquals("pending",holder.record("501")!!.status)
        assertEquals(0,api.documentCalls);assertFalse(holder.state.value.busy)
        assertEquals(PaymentCheckoutPhase.AWAITING,holder.state.value.checkout.phase)
    }
    @Test fun concurrentAndQueuedDuplicateCallbacksAcknowledgeExactlyOnceWithoutExtraReads()=runTest {
        val api=FakeGateway();val holder=holder(api);val prepared=holder.prepareCheckout("501")!!
        val gate=CompletableDeferred<List<PaymentRecord>>();api.paymentBlock={gate.await()};var acknowledgements=0
        val firstCall=async{holder.reconcileNativeCallback(prepared.reference,SheetCloseReason.SUBMITTED,acknowledge={acknowledgements++})};runCurrent()
        val calls=api.paymentCalls
        assertFalse(holder.reconcileNativeCallback(prepared.reference,SheetCloseReason.SUBMITTED,acknowledge={acknowledgements++}))
        assertEquals(calls,api.paymentCalls)
        gate.complete(listOf(row(status="paid_online")));assertTrue(firstCall.await())
        assertFalse(holder.reconcileNativeCallback(prepared.reference,SheetCloseReason.SUBMITTED,acknowledge={acknowledgements++}))
        assertEquals(1,acknowledgements);assertEquals(calls,api.paymentCalls)
        assertFalse(holder.state.value.callbackPending);assertFalse(holder.state.value.busy)
    }
    @Test fun cancelledNativeReconciliationKeepsItsRetryGateAndNeverAcknowledges()=runTest {
        val api=FakeGateway();val holder=holder(api);val prepared=holder.prepareCheckout("501")!!
        val gate=CompletableDeferred<List<PaymentRecord>>();api.paymentBlock={gate.await()};var acknowledgements=0
        val pending=launch{holder.reconcileNativeCallback(prepared.reference,SheetCloseReason.SUBMITTED,acknowledge={acknowledgements++})};runCurrent()
        pending.cancelAndJoin()
        assertEquals(0,acknowledgements);assertTrue(holder.state.value.callbackPending)
        assertFalse(holder.state.value.callbackLoading);assertFalse(holder.state.value.busy)
        assertEquals(PaymentCheckoutPhase.AWAITING,holder.state.value.checkout.phase)
        assertNull(holder.prepareCheckout("501"));assertEquals(1,api.intentCalls)
    }
    @Test fun injectedCopyResolvesLocalFailuresWhileServerDetailRemainsVerbatim()=runTest {
        val api=FakeGateway()
        val text=MobileText { key,args->"resolved:${key.english(*args)}" }
        val holder=PaymentsStateHolder(api,CachedPaymentFiles(temporary.newFolder()),now={0L},text=text)
        holder.activate(first)
        api.paymentBlock={throw IOException("internal socket failure")};holder.refresh()
        assertEquals("resolved:Couldn't connect. Try again.",holder.state.value.records.error)
        api.paymentBlock={throw ApiFailure(409,"Makerspace-specific refusal.","custom",null)};holder.refresh()
        assertEquals("Makerspace-specific refusal.",holder.state.value.records.error)
    }
    @Test fun staticDocumentValidationRetainsItsResourceIdentityUntilPresentation()=runTest {
        val api=FakeGateway()
        val text=MobileText { key,args->"resolved:${key.english(*args)}" }
        val holder=PaymentsStateHolder(api,CachedPaymentFiles(temporary.newFolder()),now={0L},text=text)
        holder.activate(first);holder.refresh();holder.showDocuments();holder.loadDocuments()
        api.pdfBlock={_,_->throw MobileCopyException(DocumentStrings.DOCUMENT_UNAVAILABLE)}
        holder.openDocument("901")
        assertEquals("resolved:This document cannot be opened.",holder.state.value.documentError)
        assertNull(holder.state.value.preview);assertFalse(holder.state.value.documentBusy)
    }
    private class FakeGateway:PaymentsGateway {
        var rows=listOf(row());var paymentCalls=0;var intentCalls=0;var documentCalls=0;var pdfCalls=0
        val documentPages=mutableListOf<Int>()
        var paymentBlock:suspend(MemberScope)->List<PaymentRecord> = {rows}
        var intentBlock:suspend(MemberScope,String)->PaymentIntent={_,_->PaymentsStateTest.intent()}
        var documentBlock:suspend(MemberScope,Int)->PaymentDocumentPage={_,_->PaymentDocumentPage(listOf(document()),null,1)}
        var pdfBlock:suspend(MemberScope,String)->ByteArray={_,_->"%PDF-1.7\nsample".toByteArray()}
        override suspend fun payments(scope:MemberScope):List<PaymentRecord>{paymentCalls++;return paymentBlock(scope)}
        override suspend fun intent(scope:MemberScope,paymentId:String):PaymentIntent{intentCalls++;return intentBlock(scope,paymentId)}
        override suspend fun documents(scope:MemberScope,page:Int):PaymentDocumentPage{documentCalls++;documentPages+=page;return documentBlock(scope,page)}
        override suspend fun documentPDF(scope:MemberScope,documentId:String):ByteArray{pdfCalls++;return pdfBlock(scope,documentId)}
    }
    companion object {
        private fun row(status:String?="pending")=PaymentRecord("501","Equipment deposit",status,"250.00","inr","2026-10-20","upcoming",true,null,emptyList())
        private fun document(id:String="901")=PaymentDocument(id,"501","RECEIPT-$id","receipt","250.00","inr","2026-10-16T09:00:00Z")
        private fun intent()=PaymentIntent("501","pi_test_secret","pk_test_owner",null,null)
    }
}
