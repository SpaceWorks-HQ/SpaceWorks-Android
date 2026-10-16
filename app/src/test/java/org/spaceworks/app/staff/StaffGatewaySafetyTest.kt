package org.spaceworks.app.staff

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.generated.model.*

class StaffGatewaySafetyTest {
    private suspend fun withGateway(authorizer:StaffAuthorizer=StaffAuthorizer{scope,_->scope==STAFF_SCOPE},work:suspend(GeneratedStaffGateway,MockWebServer)->Unit){MockWebServer().use{server->server.start();work(GeneratedStaffGateway(staffSession(server),authorizer),server)}}
    private fun json(value:String,code:Int=200)=MockResponse().setResponseCode(code).setHeader("Content-Type","application/json").setBody(value)
    private suspend fun invalid(work:suspend()->Unit){try{work();fail("Invalid staff input reached a mutation")}catch(_:IllegalArgumentException){}}

    @Test fun all24OperationsAreClosedBeforeNetworkWhenNativeStaffAuthorityIsUnknown()=runBlocking {
        withGateway(StaffAuthorizer.DENY){gateway,server->
            for(case in StaffGatewayContractTest.cases())try{case.call(gateway);fail("Unknown staff authority was accepted: ${case.name}")}catch(f:ApiFailure){assertEquals(403,f.status);assertEquals("native_staff_unavailable",f.code)}
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun wrongSelectedScopeAndMissingMutationActionCannotUseStaffReadAuthority()=runBlocking {
        withGateway(StaffAuthorizer{scope,action->scope==STAFF_SCOPE&&action==null}){gateway,server->
            try{gateway.requests(MemberScope(8,"other"),StaffQueue.PENDING,1);fail("Wrong scope authorized")}catch(f:ApiFailure){assertEquals(403,f.status)}
            try{gateway.reject(STAFF_SCOPE,601,RejectRequest("Reason"));fail("Read authority enabled mutation")}catch(f:ApiFailure){assertEquals(403,f.status)}
            assertEquals(0,server.requestCount)
            server.enqueue(json(staffPage("request")));assertEquals(601,gateway.requests(STAFF_SCOPE,StaffQueue.PENDING,1).results.single().id);staffRequest(server)
        }
    }
    @Test fun authorityLostDuringARequestCannotPublishItsResult()=runBlocking {
        val authorized=AtomicBoolean(true);val waiting=CountDownLatch(1);val release=CountDownLatch(1)
        withGateway(StaffAuthorizer{scope,_->authorized.get()&&scope==STAFF_SCOPE}){gateway,server->
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {waiting.countDown();check(release.await(5,TimeUnit.SECONDS));return json(staffPage("request"))}}
            val loading=async(Dispatchers.IO){runCatching{gateway.requests(STAFF_SCOPE,StaffQueue.PENDING,1)}}
            assertTrue(waiting.await(5,TimeUnit.SECONDS));authorized.set(false);release.countDown()
            val failure=loading.await().exceptionOrNull() as ApiFailure;assertEquals(403,failure.status);staffRequest(server)
        }
    }
    @Test fun backendRefusalAndCooldownNeverBecomeMutationSuccessOrAutomaticReplay()=runBlocking {
        withGateway{gateway,server->
            for((status,code) in listOf(403 to "not_allowed",409 to "request_changed",429 to "throttled")){
                server.enqueue(json("""{"detail":"Refused by the makerspace.","code":"$code"}""",status).setHeader("Retry-After","60"))
                try{gateway.reject(STAFF_SCOPE,601,RejectRequest("Reason"));fail("Server refusal became success")}catch(f:ApiFailure){assertEquals(status,f.status);assertEquals(code,f.code);if(status==429)assertEquals(60L,f.retryAfterSeconds)}
                staffRequest(server)
            }
            assertEquals(3,server.requestCount)
        }
    }
    @Test fun scannedCrossMakerCodeAndMismatchedRequestResultAreRefused()=runBlocking {
        withGateway{gateway,server->
            val qr=Json.parseToJsonElement(staffFixture("qr")).jsonObject
            val foreign=JsonObject(qr+("qr" to JsonObject(qr.getValue("qr").jsonObject+("makerspace" to JsonPrimitive(8)))))
            server.enqueue(json(foreign.toString()));try{gateway.resolveQR(STAFF_SCOPE,QrResolve("OPAQUE"));fail("Other maker QR accepted")}catch(f:ApiFailure){assertEquals(403,f.status)};staffRequest(server)
            server.enqueue(json(staffChanged("request","makerspace_id",JsonPrimitive(8))))
            try{gateway.accept(STAFF_SCOPE,601,staffBody("""{"accepted_quantities":[{"item_id":61,"quantity":1}]}"""));fail("Other maker request accepted")}catch(f:ApiFailure){assertEquals(403,f.status)};staffRequest(server)
            server.enqueue(json(staffChanged("request","id",JsonPrimitive(602))))
            try{gateway.assignBox(STAFF_SCOPE,601,AssignBox("CANONICAL"));fail("Different request published")}catch(f:ApiFailure){assertEquals(403,f.status)};staffRequest(server)
        }
    }
    @Test fun generatedOpenAssetMetadataAndCanonicalQrPayloadSurviveProjection()=runBlocking {
        withGateway{gateway,server->
            val request=Json.parseToJsonElement(staffFixture("request")).jsonObject
            val item=request.getValue("items").jsonArray.single().jsonObject
            val assets=Json.parseToJsonElement("""[{"asset_id":1401,"asset_tag":"CLAMP-01"}]""")
            val changed=JsonObject(request+("items" to JsonArray(listOf(JsonObject(item+("issued_assets" to assets))))))
            server.enqueue(json("""{"count":1,"next":null,"previous":null,"results":[$changed]}"""))
            val result=gateway.requests(STAFF_SCOPE,StaffQueue.ACTIVE,1)
            assertEquals(JsonPrimitive(1401),requireNotNull(result.results.single().items.single().issuedAssets.single()).jsonObject["asset_id"]);staffRequest(server)
            server.enqueue(json(staffFixture("qr")));val resolved=gateway.resolveQR(STAFF_SCOPE,QrResolve("NONCANONICAL-SCAN"))
            assertEquals("DEMO-BOX",resolved.qr.payload);assertEquals(JsonPrimitive(91),resolved.target["id"]);assertTrue("view" in resolved.allowedActions);staffRequest(server)
        }
    }
    @Test fun paginationPreservesActualJumpAndSearchWhileRejectingExternalAmbiguousOrOtherRoutes(){
        val origin="https://example.invalid/".toHttpUrl();val path="/api/v1/admin/makerspace/7/inventory"
        assertEquals(3,StaffPagination.validatedNext("?page=3&q=drill",1,origin,path,"drill"))
        assertEquals(3,StaffPagination.validatedNext("https://EXAMPLE.invalid:443$path?page=3&q=drill",1,origin,path,"drill"))
        assertEquals(3,StaffPagination.page("$path?page=3&q=drill",1))
        listOf("https://outside.invalid$path?page=2","http://example.invalid$path?page=2","https://user@example.invalid$path?page=2","/api/v1/admin/makerspace/8/inventory?page=2","/api/v1/admin/makerspace/8/../7/inventory?page=2","/api/v1/admin/makerspace/%37/inventory?page=2","$path?page=1","$path?page=2&page=3","$path?page=2&q=changed","$path?page=2&q=drill&q=drill","$path?page=2&unexpected=true","$path?page=2#private").forEach{value->
            try{StaffPagination.validatedNext(value,1,origin,path,"drill");fail("Unsafe next page accepted")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun malformedPageFromTheAPIIsNotFetchedAndKnownPageUsesGeneratedArguments()=runBlocking {
        withGateway{gateway,server->
            val fixture=Json.parseToJsonElement(staffFixture("inventory")).jsonObject
            server.enqueue(json(JsonObject(fixture+("next" to JsonPrimitive(server.url("/api/v1/admin/makerspace/8/inventory?page=2").toString()))).toString()))
            invalid{gateway.inventory(STAFF_SCOPE,1,"drill")};staffRequest(server);assertEquals(1,server.requestCount)
            server.enqueue(json(staffFixture("inventory")));gateway.inventory(STAFF_SCOPE,3,"drill")
            val request=staffRequest(server);assertEquals("3",request.requestUrl!!.queryParameter("page"));assertEquals("drill",request.requestUrl!!.queryParameter("q"))
        }
    }
    @Test fun presignProjectionKeepsAllScalarFieldsAndDefaultsOnlyMissingMethodToPost(){
        val base=Json.parseToJsonElement(StaffGatewayContractTest.EVIDENCE).jsonObject
        val values=Json.parseToJsonElement("""{"key":"private/evidence.jpg","policy":"signed","count":3,"enabled":true}""")
        val response=staffBody<EvidenceUrlResponse>(JsonObject(base+("fields" to values)).toString())
        val plan=StaffEvidence.upload(response);assertEquals("POST",plan.method);assertEquals(1301,plan.evidenceId)
        assertEquals(mapOf("key" to "private/evidence.jpg","policy" to "signed","count" to "3","enabled" to "true"),plan.fields)
        assertEquals("StaffEvidenceUpload(redacted)",plan.toString());assertFalse(plan.toString().contains("signed"))
        val put=staffBody<EvidenceUrlResponse>(JsonObject(base+("method" to JsonPrimitive("PUT"))+("headers" to Json.parseToJsonElement("""{"Content-Type":"image/jpeg","x-storage-count":3}"""))).toString())
        val putPlan=StaffEvidence.upload(put);assertEquals("PUT",putPlan.method);assertEquals("3",putPlan.headers["x-storage-count"])
    }
    @Test fun unknownUploadMethodAndNestedOrNullSignedFieldsCannotReachStorage(){
        val base=Json.parseToJsonElement(StaffGatewayContractTest.EVIDENCE).jsonObject
        listOf(JsonObject(base+("method" to JsonPrimitive("DELETE"))),JsonObject(base+("method" to JsonPrimitive(""))),JsonObject(base+("fields" to Json.parseToJsonElement("""{"policy":{"nested":true}}"""))),JsonObject(base+("fields" to Json.parseToJsonElement("""{"policy":null}""")))).forEach{value->
            try{StaffEvidence.upload(staffBody(value.toString()));fail("Unsupported storage instructions accepted")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun duplicateOrOutOfRangePhysicalCountsAndPayloadsAreStoppedBeforeNetwork()=runBlocking {
        withGateway{gateway,server->
            invalid{gateway.accept(STAFF_SCOPE,601,staffBody("""{"accepted_quantities":[{"item_id":61,"quantity":-1}]}"""))}
            invalid{gateway.accept(STAFF_SCOPE,601,staffBody("""{"accepted_quantities":[{"item_id":61,"quantity":1},{"item_id":61,"quantity":1}]}"""))}
            invalid{gateway.issue(STAFF_SCOPE,601,staffBody("""{"evidence_id":1301,"kit_checklist":[{"key":"72000000-0000-4000-8000-000000000001","present":-1}]}"""))}
            invalid{gateway.createDirect(STAFF_SCOPE,staffBody("""{"borrower_id":51,"evidence_id":1301,"qr_payloads":["DUPLICATE","DUPLICATE"]}"""))}
            invalid{gateway.createDirect(STAFF_SCOPE,staffBody("""{"borrower_id":51,"evidence_id":1301,"qr_payloads":["${"x".repeat(65)}"]}"""))}
            invalid{gateway.returnDirect(STAFF_SCOPE,701,staffBody("""{"evidence_id":1302,"notes":"Checked","resolutions":[{"item_id":71,"returned":-1}]}"""))}
            invalid{gateway.returnDirect(STAFF_SCOPE,701,staffBody("""{"evidence_id":1302,"notes":"Checked","resolutions":[{"item_id":71,"returned":1},{"item_id":71,"returned":1}]}"""))}
            assertEquals(0,server.requestCount)
        }
    }
}
