package org.spaceworks.app.staff

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.Assert.*
import org.junit.Test
import org.openapitools.client.infrastructure.*
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.network.Errors
import org.spaceworks.generated.model.*
import java.time.OffsetDateTime
import java.util.UUID

/** State tests use generated schema fixtures, with no Debug gateway or synthetic transport DTO. */
@OptIn(ExperimentalCoroutinesApi::class)
class StaffStateTest {
    @get:org.junit.Rule val temporary=org.junit.rules.TemporaryFolder()
    private val scope=MemberScope(7,"sample")
    private val other=MemberScope(8,"another")
    private val json=Json{ignoreUnknownKeys=true;explicitNulls=false;serializersModule=SerializersModule{
        contextual(UUID::class,UUIDAdapter);contextual(OffsetDateTime::class,OffsetDateTimeAdapter)
        contextual(java.net.URI::class,URIAdapter);contextual(java.math.BigDecimal::class,BigDecimalAdapter)
    }}
    private fun fixture(name:String)=javaClass.classLoader!!.getResourceAsStream("staff/$name.json")!!.bufferedReader().use{json.parseToJsonElement(it.readText())}
    private fun patch(o:JsonObject,vararg entries:Pair<String,JsonElement>)=JsonObject(o+entries.toMap())
    private inline fun <reified T> decode(value:JsonElement):T=json.decodeFromJsonElement(value)
    private fun request(status:String="accepted",maker:Int=7,id:Int=601)=decode<AdminRequest>(patch(fixture("request").jsonObject,"status" to JsonPrimitive(status),"makerspace_id" to JsonPrimitive(maker),"id" to JsonPrimitive(id)))
    private fun page(vararg rows:AdminRequest,next:String?=null):PaginatedAdminRequestList=decode(buildJsonObject{put("count",rows.size);put("next",next?.let(::JsonPrimitive)?:JsonNull);put("previous",JsonNull);put("results",JsonArray(rows.map{json.encodeToJsonElement(it)}))})
    private fun qr(maker:Int=7,payload:String="CANONICAL-BOX",status:String="active")=decode<QrResolveResult>(patch(fixture("qr").jsonObject,"qr" to patch(fixture("qr").jsonObject.getValue("qr").jsonObject,"makerspace" to JsonPrimitive(maker),"payload" to JsonPrimitive(payload),"status" to JsonPrimitive(status))))
    private fun grant()=decode<EvidenceUrlResponse>(buildJsonObject{put("evidence_id",1401);put("object_key","sample/evidence.jpg");put("upload_url","https://storage.example.test/signed");put("method","POST");put("fields",buildJsonObject{});put("headers",buildJsonObject{})})
    private val jpeg=byteArrayOf(-1,-40,-1,0,0)
    private inner class Fake:StaffGateway {
        var qrReads=0;var checkInReads=0
        var reads=0;var accepts=0;var assigns=0;var issues=0;var returns=0;var marks=0;var bookingsReads=0
        var request=request();var qr=qr();var checkIn=decode<EventCheckInResolveResponse>(fixture("checkin"));var kits=decode<KitPreview>(fixture("kits"))
        var readFailure:Exception?=null;var issueFailure:Exception?=null;var bookingFailure:Exception?=null
        var queueWork:(suspend(MemberScope,StaffQueue,Int)->PaginatedAdminRequestList)?=null
        var issueWait:CompletableDeferred<Unit>?=null;var issueEntered:CompletableDeferred<Unit>?=null
        var accepted:AcceptRequest?=null;var issueBody:IssueRequest?=null;var returned:ReturnRequest?=null
        var pages=mutableListOf<Int>();var next:String?=null
        override suspend fun requests(scope:MemberScope,queue:StaffQueue,page:Int):PaginatedAdminRequestList{reads++;pages+=page;readFailure?.let{throw it};return queueWork?.invoke(scope,queue,page)?:page(request,next=next)}
        override suspend fun accept(scope:MemberScope,id:Int,body:AcceptRequest):AdminRequest{accepts++;accepted=body;return request}
        override suspend fun reject(scope:MemberScope,id:Int,body:RejectRequest)=request
        override suspend fun assignBox(scope:MemberScope,id:Int,body:AssignBox):AdminRequest{assigns++;return request}
        override suspend fun issue(scope:MemberScope,id:Int,body:IssueRequest):AdminRequest{issues++;issueBody=body;issueEntered?.complete(Unit);issueWait?.await();issueFailure?.let{throw it};return request.copy(status="issued")}
        override suspend fun returnRequest(scope:MemberScope,id:Int,body:ReturnRequest):AdminRequest{returns++;returned=body;return request}
        override suspend fun resolveQR(scope:MemberScope,body:QrResolve):QrResolveResult{qrReads++;return qr}
        override suspend fun directLoans(scope:MemberScope,page:Int):PaginatedDirectLoanList=decode(buildJsonObject{put("count",1);put("results",JsonArray(listOf(fixture("loan"))))})
        override suspend fun directMembers(scope:MemberScope,page:Int):PaginatedDirectLoanMemberList=decode(fixture("members"))
        override suspend fun inventory(scope:MemberScope,page:Int,search:String):PaginatedInventoryProductAdminList=decode(fixture("inventory"))
        override suspend fun containers(scope:MemberScope,page:Int):PaginatedBoxList=decode(fixture("containers"))
        override suspend fun createDirect(scope:MemberScope,body:DirectLoanIssue):DirectLoan=decode(fixture("loan"))
        override suspend fun returnDirect(scope:MemberScope,id:Int,body:DirectLoanReturn):DirectLoan=decode(fixture("loan"))
        override suspend fun evidence(scope:MemberScope,body:EvidenceUrlRequest)=grant()
        override suspend fun kitPreview(scope:MemberScope,body:KitPreviewInput)=kits
        override suspend fun events(scope:MemberScope,page:Int):EventListWithDefaults=decode(buildJsonObject{put("count",1);put("default_registration_audience","members");put("results",JsonArray(listOf(fixture("event"))))})
        override suspend fun resolveCheckIn(scope:MemberScope,eventId:Int,body:EventCheckInResolveRequest):EventCheckInResolveResponse{checkInReads++;return checkIn}
        override suspend fun markAttended(scope:MemberScope,registrationId:Int,body:EventAttendanceMark):EventRegistrationAdmin{marks++;return decode(patch(fixture("registration").jsonObject,"status" to JsonPrimitive("attended")))}
        override suspend fun spaces(scope:MemberScope,page:Int):BookableSpaceListResponse=decode(buildJsonObject{put("count",1);put("results",JsonArray(listOf(fixture("space"))))})
        override suspend fun bookings(scope:MemberScope,spaceId:Int,page:Int):BookingListResponse{bookingsReads++;return decode(buildJsonObject{put("count",1);put("results",JsonArray(listOf(fixture("booking"))))})}
        override suspend fun approveBooking(scope:MemberScope,id:Int):BookingAdmin{bookingFailure?.let{throw it};return decode(fixture("booking"))}
        override suspend fun rejectBooking(scope:MemberScope,id:Int):BookingAdmin{bookingFailure?.let{throw it};return decode(fixture("booking"))}
    }
    private fun holder(fake:Fake,authority:StaffAuthorizer=StaffAuthorizer{_,_->true},upload:StaffPhotoUpload=StaffPhotoUpload{_,_->},now:()->Long={1000L})=
        StaffStateHolder(fake,authority,upload,now=now).also{it.activate(scope)}
    private suspend fun issueReady(h:StaffStateHolder,f:Fake,kits:Boolean=false){
        h.loadQueue();h.beginHandover(StaffHandoverMode.ISSUE,f.request.id);h.scan("input-box",true);h.confirmBorrower(true)
        h.setPhoto(jpeg);h.uploadPhoto();if(kits){h.prepareKits();h.state.value.handover!!.kits.forEach{h.setKit(it.key,0)}}
    }
    @Test fun injectedCopyResolvesOwnedValidationWithoutChangingServerRefusals()=runTest {
        val f=Fake();f.request=request("pending")
        val resolver=org.spaceworks.app.platform.MobileText {key,args->"resolved:${key.english(*args)}"}
        val h=StaffStateHolder(f,StaffAuthorizer{selected,_->selected==scope},text=resolver)
        h.activate(scope);h.loadQueue();h.openRequest(601);h.accept(mapOf(61 to -1))
        assertEquals("resolved:Choose a valid quantity for at least one item.",h.state.value.error)
        assertEquals(0,f.accepts)
        f.readFailure=ApiFailure(409,"Makerspace-specific refusal.","custom",null);h.loadQueue()
        assertEquals("Makerspace-specific refusal.",h.state.value.error)
        f.readFailure=java.io.IOException("internal socket failure");h.loadQueue()
        assertEquals("resolved:Couldn't connect. Refresh and try again.",h.state.value.error)
    }
    @Test fun identicalEnglishServerDetailIsPreservedWhileOwnedFallbackUsesInjectedResolver()=runTest {
        val f=Fake()
        val resolver=org.spaceworks.app.platform.MobileText{key,args->"resolved:${key.english(*args)}"}
        val h=StaffStateHolder(f,StaffAuthorizer{_,_->true},text=resolver)
        h.activate(scope)
        val fallback=Errors.map(409,null,null)
        assertNotNull(fallback.localCopy)
        f.readFailure=fallback;h.loadQueue()
        assertEquals("resolved:${fallback.detail}",h.state.value.error)
        val remote=Errors.map(409,JsonObject(mapOf("detail" to JsonPrimitive(fallback.detail))).toString(),null)
        assertEquals(fallback.detail,remote.detail);assertNull(remote.localCopy)
        f.readFailure=remote;h.loadQueue()
        assertEquals(fallback.detail,h.state.value.error)
        assertEquals(2,f.reads)
    }
    @Test fun unknownNativeAuthorityNeverReadsOrOpensStaffTools()=runTest {
        val f=Fake();val h=holder(f,StaffAuthorizer.DENY);h.loadQueue();h.loadEvents();h.beginHandover(StaffHandoverMode.DIRECT_ISSUE)
        assertEquals(0,f.reads);assertFalse(h.state.value.available);assertNull(h.state.value.handover)
    }
    @Test fun delayedPreviousScopeCannotRepopulateClearedPrivateRows()=runTest {
        val f=Fake();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();f.queueWork={_,_,_->entered.complete(Unit);release.await();page(request())}
        val h=holder(f);val task=launch{h.loadQueue()};entered.await();h.clear();h.activate(other);release.complete(Unit);task.join()
        assertEquals(other,h.state.value.scope);assertTrue(h.state.value.requests.isEmpty());assertTrue(h.state.value.loading.isEmpty())
    }
    @Test fun changingQueueDuringReadRejectsOldRowsAndDoesNotLeaveLoadingStuck()=runTest {
        val f=Fake();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();f.queueWork={_,q,_->if(q==StaffQueue.PENDING){entered.complete(Unit);release.await();page(request("pending"))}else page(request("accepted",id=602))}
        val h=holder(f);val old=launch{h.loadQueue()};entered.await();h.changeQueue(StaffQueue.ACCEPTED);release.complete(Unit);old.join()
        assertEquals(listOf(602),h.state.value.requests.map{it.id});assertTrue(h.state.value.loading.isEmpty())
    }
    @Test fun actualPageJumpAndDuplicateUpdatesAreAppliedOnce()=runTest {
        val f=Fake();f.queueWork={_,_,p->if(p==1)page(request("pending"),next="?page=3")else page(request("accepted"),request("pending",id=602))}
        val h=holder(f);h.loadQueue();h.loadQueue(true);h.loadQueue(true)
        assertEquals(listOf(1,3),f.pages);assertEquals(listOf(601,602),h.state.value.requests.map{it.id});assertEquals("accepted",h.state.value.requests.first().status)
    }
    @Test fun acceptUsesActualRequestItemIdAndRejectsInvalidQuantitiesBeforeMutation()=runTest {
        val f=Fake();f.request=request("pending");val h=holder(f);h.loadQueue();h.openRequest(601)
        h.accept(mapOf(61 to -1));h.accept(mapOf(999 to 1));h.accept(mapOf(61 to 3));h.accept(mapOf(61 to 0));assertEquals(0,f.accepts)
        h.accept(mapOf(61 to 1));assertEquals(1,f.accepts);assertEquals(61,f.accepted!!.acceptedQuantities!!.single().itemId)
    }
    @Test fun missingActionCannotOpenPhysicalHandover()=runTest {
        val f=Fake();val h=holder(f,StaffAuthorizer{_,action->action!="upload_evidence"});h.loadQueue();h.beginHandover(StaffHandoverMode.ISSUE,601)
        assertNull(h.state.value.handover);assertEquals(0,f.assigns)
    }
    @Test fun publishedPendingApprovalAndPartialReturnStatesKeepTheirActualActions()=runTest {
        val f=Fake();f.request=request("pending_approval");val h=holder(f);h.loadQueue();h.openRequest(601);h.accept(mapOf(61 to 1));assertEquals(1,f.accepts)
        f.request=request("partially_returned");h.loadQueue();h.beginHandover(StaffHandoverMode.RETURN_REQUEST,601);assertEquals(StaffHandoverMode.RETURN_REQUEST,h.state.value.handover!!.mode)
    }
    @Test fun returnCountAdditionUsesLongAndDoesNotOverflowIntoValidRange(){
        val r=StaffReturnCount(61,"Item",Int.MAX_VALUE,emptyList(),returned=Int.MAX_VALUE,damaged=Int.MAX_VALUE)
        assertFalse(r.valid);assertFalse(r.copy(returned=-1,damaged=0).valid);assertTrue(r.copy(returned=1,damaged=0).valid)
    }
    @Test fun malformedRequiredIndividualAssetMetadataBlocksReturn()=runTest {
        val f=Fake();val source=fixture("request").jsonObject;val item=source.getValue("items").jsonArray.single().jsonObject
        f.request=decode(patch(source,"status" to JsonPrimitive("issued"),"items" to JsonArray(listOf(patch(item,"requires_asset_qr" to JsonPrimitive(true),"issued_assets" to JsonArray(listOf(buildJsonObject{put("asset_id",true)})))))))
        val h=holder(f);h.loadQueue();h.beginHandover(StaffHandoverMode.RETURN_REQUEST,601);assertFalse(h.state.value.handover!!.returns.single().metadataValid)
        h.setReturn(61,1,0,0);assertFalse(h.itemsReady())
    }
    @Test fun individualAssetOutcomesStartPendingAndOnlyExplicitChangesBecomeCounts(){
        val asset=StaffReturnAsset(1301,"Asset A");val row=StaffReturnCount(61,"Tool",1,listOf(asset))
        assertFalse(row.touched);assertNull(row.body().assets);assertEquals(0,row.body().returned)
        val returned=row.copy(assets=listOf(asset.copy(outcome=StaffAssetOutcome.RETURNED)),returned=1)
        assertEquals(1301,returned.body().assets!!.single().assetId);assertTrue(returned.valid)
    }
    @Test fun delayedHandoverFrameCannotLookupReopenedSameIdOrReplacementAccountScopeAndHolder()=runTest {
        for(boundary in listOf("reopen","account","scope","holder")){
            val f=Fake();val h=holder(f);h.loadQueue();h.beginHandover(StaffHandoverMode.ISSUE,601)
            val target=h.scanTarget(StaffScanKind.CONTAINER)!!;var current=h
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val callback=async{entered.complete(Unit);release.await();current.resolveScan(target,"late-opaque-box")};entered.await()
            when(boundary){
                "reopen"->{h.close();h.beginHandover(StaffHandoverMode.ISSUE,601)}
                "account"->{h.clear();h.activate(scope);h.loadQueue();h.beginHandover(StaffHandoverMode.ISSUE,601)}
                "scope"->{f.request=request(maker=8);h.activate(other);h.loadQueue();h.beginHandover(StaffHandoverMode.ISSUE,601)}
                "holder"->{current=holder(f);current.loadQueue();current.beginHandover(StaffHandoverMode.ISSUE,601)}
            }
            release.complete(Unit);assertFalse(boundary,callback.await())
            assertEquals(boundary,0,f.qrReads);assertEquals(boundary,"",current.state.value.handover!!.boxCode)
            assertEquals(0,f.assigns);assertEquals(0,f.issues)
        }
    }
    @Test fun delayedCheckInFrameCannotLookupReopenedSameEventId()=runTest {
        val f=Fake();val h=holder(f);h.loadEvents();h.openCheckIn(801)
        val target=h.scanTarget(StaffScanKind.CHECK_IN)!!
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val callback=async{entered.complete(Unit);release.await();h.resolveScan(target,"late-opaque-check-in")};entered.await()
        h.openCheckIn(801);release.complete(Unit)
        assertFalse(callback.await());assertEquals(0,f.checkInReads);assertNull(h.state.value.person);assertEquals(0,f.marks)
    }
    @Test fun changedReviewedHandoverInvalidatesScannerTicketBeforeAnyRead()=runTest {
        val f=Fake();val h=holder(f);h.loadQueue();h.beginHandover(StaffHandoverMode.ISSUE,601)
        val target=h.scanTarget(StaffScanKind.EQUIPMENT)!!
        h.setRemark("The reviewed equipment context changed.")
        assertFalse(h.acceptsScanTarget(target));assertFalse(h.resolveScan(target,"late-opaque-asset"))
        assertEquals(0,f.qrReads);assertTrue(h.state.value.handover!!.scans.isEmpty())
    }
    @Test fun currentScannerTicketsUseExistingResolversAndNeverAutomaticallyConfirmPhysicalActions()=runTest {
        val f=Fake();val h=holder(f);h.loadQueue();h.beginHandover(StaffHandoverMode.ISSUE,601)
        val target=h.scanTarget(StaffScanKind.CONTAINER)!!
        assertTrue(h.resolveScan(target,"opaque-box"));assertEquals(1,f.qrReads);assertEquals("CANONICAL-BOX",h.state.value.handover!!.boxCode)
        assertFalse(h.resolveScan(target,"same-frame-again"));assertEquals(1,f.qrReads)
        h.select(StaffSection.EVENTS);h.loadEvents();h.openCheckIn(801)
        val event=h.scanTarget(StaffScanKind.CHECK_IN)!!
        assertTrue(h.resolveScan(event,"opaque-check-in"));assertEquals(1,f.checkInReads);assertNotNull(h.state.value.person)
        assertFalse(h.resolveScan(event,"same-frame-again"));assertEquals(1,f.checkInReads)
        assertEquals(0,f.marks);assertEquals(0,f.assigns);assertEquals(0,f.issues);assertEquals(0,f.returns)
    }
    @Test fun canonicalBoxPayloadIsRetainedAndNewInvalidScanClearsPreviousBox()=runTest {
        val f=Fake();val h=holder(f);h.loadQueue();h.beginHandover(StaffHandoverMode.ISSUE,601);h.scan("input-different-from-canonical",true)
        assertEquals("CANONICAL-BOX",h.state.value.handover!!.boxCode);f.qr=qr(maker=8);h.scan("cross-maker",true)
        assertTrue(h.state.value.handover!!.boxCode.isEmpty());assertNotNull(h.state.value.error)
    }
    @Test fun evidenceAndBorrowerReviewAreRequiredBeforeIssue()=runTest {
        val f=Fake();val h=holder(f);h.loadQueue();h.beginHandover(StaffHandoverMode.ISSUE,601);h.scan("box",true);assertFalse(h.ready())
        h.setPhoto(jpeg);h.uploadPhoto();assertFalse(h.ready());h.confirmBorrower(true);assertTrue(h.ready())
    }
    @Test fun kitCountsRequireExplicitZeroAndChangingItemsInvalidatesPreview()=runTest {
        val f=Fake();val h=holder(f);h.activate(scope,setOf("loans.kit_checklist"));issueReady(h,f);h.prepareKits()
        assertFalse(h.ready());val key=h.state.value.handover!!.kits.single().key;h.setKit(key,0);assertTrue(h.ready())
        h.removeScan(999);assertFalse(h.ready());assertTrue(h.state.value.handover!!.kitCounts.isEmpty())
    }
    @Test fun replacingPhotoInvalidatesPreviouslyUploadedEvidence()=runTest {
        val f=Fake();var uploads=0;val h=holder(f,upload=StaffPhotoUpload{_,_->uploads++});issueReady(h,f)
        val revision=h.state.value.handover!!.photoRevision;assertEquals(1401,h.state.value.handover!!.evidenceId)
        h.setPhoto(jpeg+1.toByte());assertNull(h.state.value.handover!!.evidenceId);assertTrue(h.state.value.handover!!.photoRevision>revision);assertFalse(h.ready());assertEquals(1,uploads)
    }
    @Test fun delayedPhotoDecodeCannotReplaceEvidenceOfReopenedSameTargetAndDeletesPrivateCapture()=runTest {
        val f=Fake();val h=holder(f);h.loadQueue();h.beginHandover(StaffHandoverMode.ISSUE,601)
        val target=h.photoTarget()!!;val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val capture=temporary.newFile("old-camera.jpg")
        val pending=async{h.receivePhoto(target,decode={entered.complete(Unit);release.await();jpeg},cleanup={capture.delete();Unit})}
        entered.await();h.close();h.beginHandover(StaffHandoverMode.ISSUE,601)
        val newer=jpeg+2.toByte();h.setPhoto(newer);h.uploadPhoto()
        val revision=h.state.value.handover!!.photoRevision
        release.complete(Unit);assertFalse(pending.await())
        assertFalse(capture.exists());assertArrayEquals(newer,h.photoPreview())
        assertEquals(revision,h.state.value.handover!!.photoRevision);assertEquals(1401,h.state.value.handover!!.evidenceId)
        assertFalse(h.state.value.busy)
    }
    @Test fun pickerOrPermissionTicketRejectsSameMakerNewAccountAndDifferentHolderBeforeDecoding()=runTest {
        val f=Fake();val h=holder(f);h.loadQueue();h.beginHandover(StaffHandoverMode.ISSUE,601)
        val target=h.photoTarget()!!
        h.clear();h.activate(scope);h.loadQueue();h.beginHandover(StaffHandoverMode.ISSUE,601)
        val other=holder(f);other.loadQueue();other.beginHandover(StaffHandoverMode.ISSUE,601)
        for(current in listOf(h,other)) {
            var decoded=false;val capture=temporary.newFile()
            assertFalse(current.acceptsPhotoTarget(target))
            assertFalse(current.receivePhoto(target,decode={decoded=true;jpeg},cleanup={capture.delete();Unit}))
            assertFalse(decoded);assertFalse(capture.exists());assertNull(current.photoPreview());assertFalse(current.state.value.handover!!.photoReady)
        }
    }
    @Test fun photoCallbackDuringEvidenceUploadCannotReplaceBytesOrBreakEvidenceAssociation()=runTest {
        val f=Fake();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val h=holder(f,upload=StaffPhotoUpload{_,bytes->assertArrayEquals(jpeg,bytes);entered.complete(Unit);release.await()})
        h.loadQueue();h.beginHandover(StaffHandoverMode.ISSUE,601);h.setPhoto(jpeg)
        val target=h.photoTarget()!!;val revision=h.state.value.handover!!.photoRevision
        val upload=async{h.uploadPhoto()};entered.await();assertTrue(h.state.value.busy)
        var decoded=false;val capture=temporary.newFile()
        assertFalse(h.receivePhoto(target,decode={decoded=true;jpeg+3.toByte()},cleanup={capture.delete();Unit}))
        assertFalse(decoded);assertFalse(capture.exists());assertArrayEquals(jpeg,h.photoPreview())
        release.complete(Unit);upload.await()
        assertFalse(h.state.value.busy);assertEquals(1401,h.state.value.handover!!.evidenceId)
        assertEquals(revision,h.state.value.handover!!.photoRevision);assertArrayEquals(jpeg,h.photoPreview())
        assertFalse(h.setPhoto(target,jpeg+3.toByte()));assertEquals(1401,h.state.value.handover!!.evidenceId)
    }
    @Test fun changedReviewDuringPhotoDecodeDropsResultAndCancellationAlwaysDeletesCapture()=runTest {
        val f=Fake();val h=holder(f);h.loadQueue();h.beginHandover(StaffHandoverMode.ISSUE,601);h.setPhoto(jpeg);h.uploadPhoto()
        val target=h.photoTarget()!!;val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val capture=temporary.newFile()
        val pending=async{h.receivePhoto(target,decode={entered.complete(Unit);release.await();jpeg+4.toByte()},cleanup={capture.delete();Unit})}
        entered.await();h.setRemark("Equipment review changed while decoding.");release.complete(Unit)
        assertFalse(pending.await());assertFalse(capture.exists());assertEquals(1401,h.state.value.handover!!.evidenceId);assertArrayEquals(jpeg,h.photoPreview())
        val second=temporary.newFile();val waiting=CompletableDeferred<Unit>();val fresh=h.photoTarget()!!
        val cancelled=launch{h.receivePhoto(fresh,decode={waiting.complete(Unit);awaitCancellation()},cleanup={second.delete();Unit})}
        waiting.await();cancelled.cancelAndJoin();assertFalse(second.exists());assertArrayEquals(jpeg,h.photoPreview())
    }
    @Test fun failedStorageUploadNeverAttachesEvidenceId()=runTest {
        val f=Fake();val h=holder(f,upload=StaffPhotoUpload{_,_->throw java.io.IOException()});issueReady(h,f)
        assertNull(h.state.value.handover!!.evidenceId);assertFalse(h.ready());assertNotNull(h.state.value.error)
    }
    @Test fun issueFailureAfterAssignmentPreservesPartialSuccessAndBlocksBlindRetry()=runTest {
        val f=Fake();f.issueFailure=ApiFailure(409,"Changed",null,null);val h=holder(f);issueReady(h,f);h.confirmHandover()
        assertEquals(1,f.assigns);assertEquals(1,f.issues);assertTrue(h.state.value.handover!!.assignmentRecorded);assertTrue(h.state.value.handover!!.needsReload);assertNull(h.state.value.handover!!.receipt)
        h.confirmHandover();assertEquals(1,f.issues);assertTrue(h.state.value.notice!!.contains("assignment"))
    }
    @Test fun logoutDuringHandoverCannotResurrectReceiptOrPhoto()=runTest {
        val f=Fake();f.issueEntered=CompletableDeferred();f.issueWait=CompletableDeferred();val h=holder(f);issueReady(h,f)
        val work=launch{h.confirmHandover()};f.issueEntered!!.await();h.clear();f.issueWait!!.complete(Unit);work.join()
        assertNull(h.state.value.scope);assertNull(h.state.value.handover);assertNull(h.photoPreview())
    }
    @Test fun concurrentConfirmationOnlySendsOnePhysicalMutation()=runTest {
        val f=Fake();f.issueEntered=CompletableDeferred();f.issueWait=CompletableDeferred();val h=holder(f);issueReady(h,f)
        val first=launch{h.confirmHandover()};f.issueEntered!!.await();h.confirmHandover();f.issueWait!!.complete(Unit);first.join()
        assertEquals(1,f.assigns);assertEquals(1,f.issues);assertNotNull(h.state.value.handover!!.receipt)
    }
    @Test fun checkInResolutionDoesNotMarkAttendanceWithoutSeparateConfirmation()=runTest {
        val f=Fake();val h=holder(f);h.loadEvents();h.openCheckIn(801);h.resolveCheckIn("opaque-checkin")
        assertEquals(0,f.marks);assertTrue(h.canConfirmAttendance());h.confirmAttendance();h.confirmAttendance()
        assertEquals(1,f.marks);assertNull(h.state.value.person);assertTrue(h.state.value.attendanceNotice!!.contains("Meera"))
    }
    @Test fun invalidNewCheckInScanClearsPreviousActionablePerson()=runTest {
        val f=Fake();val h=holder(f);h.loadEvents();h.openCheckIn(801);h.resolveCheckIn("valid");assertNotNull(h.state.value.person)
        h.resolveCheckIn("\n");assertNull(h.state.value.person);assertFalse(h.canConfirmAttendance())
    }
    @Test fun nonRegisteredOrUnconfirmablePersonNeverMarksAttendance()=runTest {
        val f=Fake();f.checkIn=decode(patch(fixture("checkin").jsonObject,"status" to JsonPrimitive("pending_approval"),"confirmable" to JsonPrimitive(false)))
        val h=holder(f);h.loadEvents();h.openCheckIn(801);h.resolveCheckIn("pending");h.confirmAttendance();assertEquals(0,f.marks)
    }
    @Test fun bookingConflictRefetchesAuthoritativeListInsteadOfManufacturingApproval()=runTest {
        val f=Fake();f.bookingFailure=ApiFailure(409,"Changed",null,null);val h=holder(f);h.loadSpaces();h.openBookings(h.state.value.spaces.single());h.decideBooking(1001,true)
        assertEquals(2,f.bookingsReads);assertEquals("pending",h.state.value.bookings.single().status.value)
    }
    @Test fun revokedIdentityClearsAllStaffDataAndInvokesCorrectOwnerCallback()=runTest {
        for(status in listOf(401,403)){val f=Fake();var signedOut=0;var forbidden=0
            val h=StaffStateHolder(f,StaffAuthorizer{_,_->true},onUnauthorized={signedOut++},onForbidden={forbidden++});h.activate(scope);h.loadQueue();f.readFailure=ApiFailure(status,"Access changed",null,null);h.loadQueue()
            assertNull(h.state.value.scope);assertTrue(h.state.value.requests.isEmpty());assertEquals(if(status==401)1 else 0,signedOut);assertEquals(if(status==403)1 else 0,forbidden)}
    }
    @Test fun retryAfterBlocksAllReadsAndMutationsUntilDeadline()=runTest {
        val f=Fake();var now=1000L;f.readFailure=ApiFailure(429,"Wait",null,30);val h=holder(f,now={now});h.loadQueue();assertEquals(31000L,h.state.value.retryAtMillis)
        h.loadQueue();h.beginHandover(StaffHandoverMode.DIRECT_ISSUE);assertEquals(1,f.reads);assertNull(h.state.value.handover)
        now=31001;f.readFailure=null;h.loadQueue();assertEquals(2,f.reads)
    }
}
