package org.spaceworks.app.staff

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.generated.model.*
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.spaceworks.app.notifications.LinkStateHolder

/** Debug-only fixture tests: no Android bitmap, API, external upload or SDK is invoked. */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoStaffGatewayTest {
    private val scope=MemberScope(7,"sample")
    private val other=MemberScope(8,"other")
    private val jpeg=byteArrayOf(-1,-40,-1,0,0)
    private fun demo()=DemoStaffGateway({name->javaClass.classLoader!!.getResourceAsStream("staff/$name.json")!!.bufferedReader().use{it.readText()}},{jpeg.copyOf()})
    private fun holder(api:DemoStaffGateway)=StaffStateHolder(api,api.authority,api.uploads).also{it.activate(scope)}

    @Test fun incomingMakerSelectionCannotReplaceStaffReviewAndRemainsPendingUntilClosed()=runTest {
        val api=demo();val h=holder(api);h.loadQueue();h.openRequest(601)
        var selected=7
        val links=LinkStateHolder("https://demo.invalid/".toHttpUrl(),{true},{id->
            if(h.protectsNavigation)throw CancellationException()
            selected=id
        })
        links.offer("/makerspaces/8/inbox")
        assertTrue(runCatching{links.resolve()}.exceptionOrNull() is CancellationException)
        assertEquals(7,selected);assertNotNull(links.state.value.pending);assertNull(links.state.value.delivered)
        assertEquals(601,h.state.value.selectedRequest!!.id);assertEquals("pending",h.state.value.selectedRequest!!.status)
        assertTrue(h.close());links.resolve();assertEquals(8,selected);assertEquals(8,links.state.value.delivered!!.destination.makerspaceId)
    }

    @Test fun scenarioOptionsHaveStableIdsAndResourceLabelsAndUnknownSelectionDoesNotReset()=runTest {
        val api=demo();val h=holder(api);h.loadQueue();h.openRequest(601)
        assertEquals(setOf("NORMAL","EMPTY","REFUSED","OFFLINE","RATE_LIMITED","ISSUE_AFTER_ASSIGNMENT_FAILS","KIT_CHECKLIST"),h.previewOptions.map{it.id}.toSet())
        assertTrue(h.previewOptions.all{it.label.english.isNotBlank()})
        val before=h.state.value
        assertFalse(h.previewScenario("not-a-scenario",scope));assertEquals(before,h.state.value)
        assertFalse(h.previewScenario("EMPTY",other));assertEquals(before,h.state.value)
        assertEquals("NORMAL",h.previewScenarioId)
    }
    @Test fun emptyScenarioReturnsEmptyGeneratedListEnvelopesAcrossWorkspace()=runTest {
        val api=demo();assertTrue(api.selectPreviewScenario("EMPTY",scope))
        for(queue in StaffQueue.entries)assertTrue(api.requests(scope,queue,1).results.isEmpty())
        assertTrue(api.directLoans(scope,1).results.isEmpty());assertTrue(api.directMembers(scope,1).results.isEmpty())
        assertTrue(api.inventory(scope,1,"").results.isEmpty());assertTrue(api.containers(scope,1).results.isEmpty())
        assertTrue(api.events(scope,1).results.isEmpty());assertTrue(api.spaces(scope,1).results.isEmpty())
        assertTrue(api.bookings(scope,1,1).results.isEmpty())
    }
    @Test fun refusedPreviewCanRecoverUsingActualCapturedScopeWithoutAuthOrSdk()=runTest {
        val api=demo();var forbidden=0
        val h=StaffStateHolder(api,api.authority,api.uploads,onForbidden={forbidden++});h.activate(scope)
        assertTrue(h.previewScenario("REFUSED",scope));assertNull(h.state.value.scope);assertEquals(1,forbidden)
        assertTrue(h.previewScenario("NORMAL",scope));assertEquals(scope,h.state.value.scope)
        assertTrue(h.state.value.available);assertEquals(601,h.state.value.requests.single().id)
        assertEquals("NORMAL",h.previewScenarioId)
    }
    @Test fun scenarioChangeResetsOnlyLocalPreviewThrottleAndOfflineState()=runTest {
        val api=demo();val h=StaffStateHolder(api,api.authority,api.uploads,now={1000L});h.activate(scope)
        h.previewScenario("RATE_LIMITED",scope);assertEquals(31000L,h.state.value.retryAtMillis)
        assertTrue(h.previewScenario("OFFLINE",scope));assertNotNull(h.state.value.error)
        assertEquals(0L,h.state.value.retryAtMillis)
        assertTrue(h.previewScenario("NORMAL",scope));assertNull(h.state.value.error);assertEquals(1,h.state.value.requests.size)
    }
    @Test fun kitScenarioUsesGeneratedFixtureFlagAndRealUuidWithExplicitCounts()=runTest {
        val api=demo();val h=holder(api);h.previewScenario("KIT_CHECKLIST",scope)
        h.changeQueue(StaffQueue.ACCEPTED);assertTrue(h.state.value.requests.single().hasKitChecklist)
        h.beginHandover(StaffHandoverMode.ISSUE,602);h.prepareKits()
        val draft=h.state.value.handover!!
        assertTrue(draft.kitsReady);assertEquals(UUID.fromString("72000000-0000-4000-8000-000000000001"),draft.kits.single().key)
        assertTrue(draft.kitCounts.isEmpty());assertFalse(h.ready())
    }
    @Test fun switchingScenarioDropsPhotoAndQrTicketsAndRejectsDeferredOldQueue()=runTest {
        val api=demo();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var delay=false
        val wrapped=object:StaffGateway by api,StaffPreviewControl by api {
            override suspend fun requests(scope:MemberScope,queue:StaffQueue,page:Int):PaginatedAdminRequestList {
                val result=api.requests(scope,queue,page)
                if(delay){delay=false;entered.complete(Unit);release.await()}
                return result
            }
        }
        val h=StaffStateHolder(wrapped,api.authority,api.uploads);h.activate(scope)
        h.changeQueue(StaffQueue.ACCEPTED);h.beginHandover(StaffHandoverMode.ISSUE,602);h.setPhoto(jpeg)
        val photo=h.photoTarget()!!;val qr=h.scanTarget(StaffScanKind.CONTAINER)!!
        delay=true;val old=launch{h.loadQueue()};entered.await()
        assertTrue(h.previewScenario("EMPTY",scope));release.complete(Unit);old.join()
        assertNull(h.state.value.handover);assertNull(h.photoPreview());assertFalse(h.acceptsPhotoTarget(photo));assertFalse(h.acceptsScanTarget(qr))
        assertTrue(h.state.value.requests.isEmpty());assertTrue(h.state.value.loading.isEmpty());assertEquals(StaffQueue.PENDING,h.state.value.queue)
    }
    @Test fun busyUploadRefusesQueuedScenarioCallbackAndRetainsPhotoEvidenceAssociation()=runTest {
        val api=demo();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val h=StaffStateHolder(api,api.authority,StaffPhotoUpload{_,_->entered.complete(Unit);release.await()});h.activate(scope)
        h.changeQueue(StaffQueue.ACCEPTED);h.beginHandover(StaffHandoverMode.ISSUE,602);h.setPhoto(jpeg)
        val uploading=launch{h.uploadPhoto()};entered.await();assertTrue(h.state.value.busy)
        assertFalse(h.previewScenario("EMPTY",scope));assertEquals("NORMAL",h.previewScenarioId)
        release.complete(Unit);uploading.join();assertFalse(h.state.value.busy)
        assertEquals(1401,h.state.value.handover!!.evidenceId);assertArrayEquals(jpeg,h.photoPreview())
    }
    @Test fun partialAssignmentFailureIsRealFixtureMutationAndScenarioResetRestoresOnlySelectedWorkspace()=runTest {
        val api=demo();api.selectPreviewScenario("ISSUE_AFTER_ASSIGNMENT_FAILS",scope)
        api.assignBox(scope,602,AssignBox("DEMO-BOX"))
        try{api.issue(scope,602,IssueRequest(evidenceId=1401));fail("Expected sample conflict")}
        catch(f:ApiFailure){assertEquals(409,f.status)}
        assertEquals("accepted",api.requests(scope,StaffQueue.ACCEPTED,1).results.single().status)
        api.accept(other,601,AcceptRequest(acceptedQuantities=emptyList()))
        api.selectPreviewScenario("NORMAL",scope)
        assertEquals("pending",api.requests(scope,StaffQueue.PENDING,1).results.single().status)
        assertTrue(api.requests(other,StaffQueue.PENDING,1).results.isEmpty())
    }
    @Test fun reviewedAcceptedQuantityBecomesIssuedAndPartialReturnStaysInActiveQueueUntilResolved()=runTest {
        val api=demo();val pending=api.requests(scope,StaffQueue.PENDING,1).results.single()
        assertEquals(0,pending.items.single().acceptedQuantity);assertEquals(0,pending.items.single().issuedQuantity)
        val accepted=api.requests(scope,StaffQueue.ACCEPTED,1).results.single()
        assertEquals(2,accepted.items.single().acceptedQuantity);assertEquals(0,accepted.items.single().issuedQuantity)
        api.accept(scope,pending.id,AcceptRequest(listOf(AcceptQuantity(pending.items.single().id,1))))
        val issued=api.issue(scope,pending.id,IssueRequest(evidenceId=1402))
        assertEquals(1,issued.items.single().issuedQuantity);assertEquals(1402,issued.issueEvidenceId)
        val completed=api.returnRequest(scope,issued.id,ReturnRequest(1403,"DEMO-BOX","Checked",listOf(ReturnItemResolution(issued.items.single().id,returned=1))))
        assertEquals("returned",completed.status);assertEquals(1,completed.items.single().returnedQuantity)
        val original=api.requests(scope,StaffQueue.ACTIVE,1).results.single{it.id==603}
        val partial=api.returnRequest(scope,603,ReturnRequest(1404,"DEMO-BOX","One returned",listOf(ReturnItemResolution(original.items.single().id,returned=1))))
        assertEquals("partially_returned",partial.status);assertEquals(2,partial.items.single().issuedQuantity)
        val refreshed=api.requests(scope,StaffQueue.ACTIVE,1).results.single{it.id==603}
        assertEquals(1,refreshed.items.single().returnedQuantity);assertEquals(0,refreshed.items.single().damagedQuantity)
        val final=api.returnRequest(scope,603,ReturnRequest(1405,"DEMO-BOX","Other damaged",listOf(ReturnItemResolution(original.items.single().id,damaged=1))))
        assertEquals("returned",final.status);assertEquals(1,final.items.single().returnedQuantity);assertEquals(1,final.items.single().damagedQuantity)
        assertTrue(api.requests(scope,StaffQueue.ACTIVE,1).results.none{it.id==603})
    }
    @Test fun directReviewedOneAndTwoQuantitiesKeepRealReturnIdsAndPartialOutstandingAfterRefetch()=runTest {
        val api=demo()
        val one=api.createDirect(scope,DirectLoanIssue(51,1401,items=listOf(DirectLoanItem(901,1))))
        val two=api.createDirect(scope,DirectLoanIssue(51,1402,items=listOf(DirectLoanItem(901,2))))
        assertEquals(1,one.items.single().quantity);assertEquals(1,one.returnItems.single().remainingQuantity)
        assertEquals(2,two.items.single().quantity);assertEquals(2,two.returnItems.single().remainingQuantity)
        assertNotEquals(one.publicToken,two.publicToken);assertNotEquals(one.returnItems.single().itemId,two.returnItems.single().itemId)
        val partial=api.returnDirect(scope,two.id,DirectLoanReturn(1403,"First returned",resolutions=listOf(ReturnItemResolution(two.returnItems.single().itemId,returned=1))))
        assertEquals("active",partial.status);assertEquals(1,partial.returnItems.single().remainingQuantity)
        val refreshed=api.directLoans(scope,1).results.single{it.id==two.id}
        assertEquals(1,refreshed.returnItems.single().remainingQuantity);assertEquals(2,refreshed.items.single().quantity)
        val final=api.returnDirect(scope,two.id,DirectLoanReturn(1404,"Other resolved",resolutions=listOf(ReturnItemResolution(refreshed.returnItems.single().itemId,missing=1))))
        assertEquals("returned",final.status);assertEquals(0,final.returnItems.single().remainingQuantity)
        val oneFinal=api.returnDirect(scope,one.id,DirectLoanReturn(1405,"Returned",resolutions=listOf(ReturnItemResolution(one.returnItems.single().itemId,returned=1))))
        assertEquals("returned",oneFinal.status)
    }
    @Test fun directScansProjectKnownFixtureAssetAndProductAndRejectUnknownOrDuplicateSelection()=runTest {
        val api=demo()
        val asset=api.createDirect(scope,DirectLoanIssue(51,1401,qrPayloads=listOf("DEMO-ASSET")))
        assertEquals("asset",asset.targetType);assertTrue(asset.returnScanRequired)
        assertEquals(1301,asset.returnItems.single().assets.single().assetId)
        val returned=api.returnDirect(scope,asset.id,DirectLoanReturn(1402,"Checked",qrPayload="DEMO-ASSET",resolutions=listOf(
            ReturnItemResolution(asset.returnItems.single().itemId,returned=1,assets=listOf(ReturnAssetResolution(1301,ReturnAssetResolutionOutcomeEnum.entries.first{it.value=="returned"}))))))
        assertEquals("returned",returned.status);assertEquals(0,returned.returnItems.single().remainingQuantity);assertTrue(returned.returnItems.single().assets.isEmpty())
        val product=api.createDirect(scope,DirectLoanIssue(51,1403,qrPayloads=listOf("DEMO-PRODUCT")))
        assertEquals("product",product.targetType);assertEquals(1,product.items.single().quantity)
        assertEquals(901,api.resolveQR(scope,QrResolve("DEMO-PRODUCT")).qr.targetId)
        val count=api.directLoans(scope,1).results.size
        for(body in listOf(DirectLoanIssue(51,1404,items=listOf(DirectLoanItem(999,1))),
            DirectLoanIssue(51,1404,items=listOf(DirectLoanItem(901,1)),qrPayloads=listOf("DEMO-PRODUCT")))){
            try{api.createDirect(scope,body);fail("Invalid sample selection must be refused")}
            catch(f:ApiFailure){assertEquals(400,f.status)}
        }
        assertEquals(count,api.directLoans(scope,1).results.size)
    }
}
