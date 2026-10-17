package org.spaceworks.app.staff

import java.math.BigDecimal
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.openapitools.client.infrastructure.*
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.*
import org.spaceworks.generated.model.*

internal val STAFF_SCOPE=MemberScope(7,"maker-space")
internal val staffJSON=Json{ignoreUnknownKeys=true;explicitNulls=false;serializersModule=SerializersModule{
    contextual(UUID::class,UUIDAdapter);contextual(OffsetDateTime::class,OffsetDateTimeAdapter);contextual(URI::class,URIAdapter);contextual(BigDecimal::class,BigDecimalAdapter)
}}
internal inline fun <reified T> staffBody(value:String):T=staffJSON.decodeFromString(value)
internal fun staffFixture(name:String)=requireNotNull(StaffGatewayContractTest::class.java.classLoader!!.getResourceAsStream("staff/$name.json")).bufferedReader().use{it.readText()}
internal fun staffPage(name:String,extra:String="")="""{"count":1,"next":null,"previous":null,"results":[${staffFixture(name)}]$extra}"""
internal fun staffChanged(name:String,key:String,value:JsonElement)=JsonObject(Json.parseToJsonElement(staffFixture(name)).jsonObject+(key to value)).toString()
internal fun staffRequest(server:MockWebServer):RecordedRequest=requireNotNull(server.takeRequest(5,TimeUnit.SECONDS)).also{
    assertEquals("Bearer staff-access",it.getHeader("Authorization"));assertEquals("7",it.getHeader("X-Makerspace-Id"));listOf("Cookie","Origin","Referer","X-Nonce").forEach{name->assertNull(name,it.getHeader(name))}
}
internal fun staffSession(server:MockWebServer)=ApiSession(server.url("/"),MemoryTokenStore().apply{replace(TokenPair("staff-access","staff-refresh"))}){"999"}
internal fun staffWire(request:RecordedRequest)=Json.parseToJsonElement(request.body.readUtf8()).jsonObject
data class StaffApiCase(val name:String,val method:String,val path:String,val response:()->String,val status:Int=200,
    val verify:(RecordedRequest)->Unit={},val call:suspend(StaffGateway)->Unit){override fun toString()=name}

/** One executable case for each of the24 actual generated operations used by A6. */
@RunWith(Parameterized::class)
class StaffGatewayContractTest(private val case:StaffApiCase) {
    @Test fun operationUsesExactGeneratedRouteBodyScopeAndResponse()=runBlocking {
        MockWebServer().use{server->server.start();server.enqueue(MockResponse().setResponseCode(case.status).setHeader("Content-Type","application/json").setBody(case.response()))
            val gateway=GeneratedStaffGateway(staffSession(server),StaffAuthorizer{scope,_->scope==STAFF_SCOPE})
            case.call(gateway);val request=staffRequest(server);assertEquals(case.method,request.method);assertEquals(case.path,request.path);case.verify(request);assertEquals(1,server.requestCount)
        }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}") fun parameters():List<Array<Any>> = cases().map{arrayOf<Any>(it)}
        fun cases():List<StaffApiCase> = listOf(
            StaffApiCase("pending requests","GET","/api/v1/admin/makerspace/7/pending-requests?page=1",{staffPage("request")}){assertEquals(601,it.requests(STAFF_SCOPE,StaffQueue.PENDING,1).results.single().id)},
            StaffApiCase("accepted requests","GET","/api/v1/admin/makerspace/7/accepted-requests?page=1",{staffPage("request")}){assertEquals(601,it.requests(STAFF_SCOPE,StaffQueue.ACCEPTED,1).results.single().id)},
            StaffApiCase("active loans","GET","/api/v1/admin/makerspace/7/active-loans?page=1",{staffPage("request")}){assertEquals(601,it.requests(STAFF_SCOPE,StaffQueue.ACTIVE,1).results.single().id)},
            StaffApiCase("accept request","POST","/api/v1/admin/requests/601/accept",{staffChanged("request","status",JsonPrimitive("accepted"))},verify={assertEquals(Json.parseToJsonElement("""[{"item_id":61,"quantity":1}]"""),staffWire(it)["accepted_quantities"])}){assertEquals(601,it.accept(STAFF_SCOPE,601,staffBody("""{"accepted_quantities":[{"item_id":61,"quantity":1}]}""")).id)},
            StaffApiCase("reject request","POST","/api/v1/admin/requests/601/reject",{staffChanged("request","status",JsonPrimitive("rejected"))},verify={assertEquals(JsonPrimitive("Unavailable equipment"),staffWire(it)["reason"])}){assertEquals(601,it.reject(STAFF_SCOPE,601,RejectRequest("Unavailable equipment")).id)},
            StaffApiCase("assign box","POST","/api/v1/admin/requests/601/assign-box",{staffFixture("request")},verify={assertEquals(JsonPrimitive("CANONICAL-BOX"),staffWire(it)["box_code"])}){assertEquals(601,it.assignBox(STAFF_SCOPE,601,AssignBox("CANONICAL-BOX")).id)},
            StaffApiCase("issue request","POST","/api/v1/admin/requests/601/issue",{staffChanged("request","status",JsonPrimitive("issued"))},verify={val body=staffWire(it);assertEquals(JsonPrimitive(1301),body["evidence_id"]);assertEquals(JsonPrimitive(0),body["kit_checklist"]!!.jsonArray.single().jsonObject["present"])}){assertEquals(601,it.issue(STAFF_SCOPE,601,staffBody("""{"evidence_id":1301,"remark":"Condition checked","asset_qr_payloads":["CANONICAL-ASSET"],"kit_checklist":[{"key":"72000000-0000-4000-8000-000000000001","present":0}]}""")).id)},
            StaffApiCase("return request","POST","/api/v1/admin/requests/601/return",{staffChanged("request","status",JsonPrimitive("partially_returned"))},verify={val body=staffWire(it);assertEquals(JsonPrimitive("CANONICAL-BOX"),body["box_code"]);assertEquals(JsonPrimitive(61),body["resolutions"]!!.jsonArray.single().jsonObject["item_id"])}){assertEquals(601,it.returnRequest(STAFF_SCOPE,601,staffBody("""{"evidence_id":1302,"box_code":"CANONICAL-BOX","remark":"One item returned","resolutions":[{"item_id":61,"returned":1,"damaged":0,"missing":0}]}""")).id)},
            StaffApiCase("QR lookup only","POST","/api/v1/admin/qr/resolve",{staffFixture("qr")},verify={assertEquals(JsonPrimitive("OPAQUE-SCAN"),staffWire(it)["payload"])}){assertEquals("DEMO-BOX",it.resolveQR(STAFF_SCOPE,QrResolve("OPAQUE-SCAN")).qr.payload)},
            StaffApiCase("direct loans","GET","/api/v1/admin/makerspace/7/direct-loans?page=1",{staffPage("loan")}){assertEquals(71,it.directLoans(STAFF_SCOPE,1).results.single().returnItems.single().itemId)},
            StaffApiCase("borrowers","GET","/api/v1/admin/makerspace/7/direct-loan-members?page=1",{staffFixture("members")}){assertEquals(51,it.directMembers(STAFF_SCOPE,1).results.single().userId)},
            StaffApiCase("inventory","GET","/api/v1/admin/makerspace/7/inventory?page=1&q=drill",{staffFixture("inventory")}){assertEquals(901,it.inventory(STAFF_SCOPE,1,"drill").results.single().id)},
            StaffApiCase("containers","GET","/api/v1/admin/makerspace/7/containers?page=1",{staffFixture("containers")}){assertTrue(it.containers(STAFF_SCOPE,1).results.isEmpty())},
            StaffApiCase("direct issue","POST","/api/v1/admin/makerspace/7/direct-loans",{staffFixture("loan")},201,verify={val body=staffWire(it);assertEquals(JsonPrimitive(51),body["borrower_id"]);assertEquals(JsonPrimitive(901),body["items"]!!.jsonArray.single().jsonObject["product_id"])}){assertEquals(701,it.createDirect(STAFF_SCOPE,staffBody("""{"borrower_id":51,"evidence_id":1301,"remark":"Checked with borrower","items":[{"product_id":901,"quantity":2}]}""")).id)},
            StaffApiCase("direct return","POST","/api/v1/admin/direct-loans/701/return",{staffChanged("loan","status",JsonPrimitive("returned"))},verify={val body=staffWire(it);assertEquals(JsonPrimitive("Condition checked"),body["notes"]);assertEquals(JsonPrimitive(71),body["resolutions"]!!.jsonArray.single().jsonObject["item_id"])}){assertEquals(701,it.returnDirect(STAFF_SCOPE,701,staffBody("""{"evidence_id":1302,"notes":"Condition checked","resolutions":[{"item_id":71,"returned":2}]}""")).id)},
            StaffApiCase("evidence presign only","POST","/api/v1/admin/makerspaces/7/uploads/evidence-url",{EVIDENCE},201,verify={val body=staffWire(it);assertEquals(JsonPrimitive("issue"),body["evidence_type"]);assertEquals(JsonPrimitive("image/jpeg"),body["content_type"]);assertEquals(JsonPrimitive(123),body["size_bytes"])}){assertEquals(1301,it.evidence(STAFF_SCOPE,staffBody("""{"evidence_type":"issue","content_type":"image/jpeg","size_bytes":123}""")).evidenceId)},
            StaffApiCase("kit preview","POST","/api/v1/admin/makerspace/7/kit-checklist-preview",{staffFixture("kits")},verify={val body=staffWire(it);assertEquals(JsonPrimitive(601),body["request_id"]);assertEquals(JsonPrimitive("return"),body["phase"])}){assertEquals("72000000-0000-4000-8000-000000000001",it.kitPreview(STAFF_SCOPE,staffBody("""{"phase":"return","request_id":601,"box_code":"CANONICAL-BOX"}""")).components.single().key.toString())},
            StaffApiCase("events","GET","/api/v1/admin/makerspaces/7/events/?page=1",{staffPage("event",",\"default_registration_audience\":\"members\"")}){assertEquals(801,it.events(STAFF_SCOPE,1).results.single().id)},
            StaffApiCase("check-in lookup only","POST","/api/v1/admin/events/801/check-in/resolve/",{staffFixture("checkin")},verify={assertEquals(JsonPrimitive("OPAQUE-TICKET"),staffWire(it)["checkin_token"])}){assertTrue(it.resolveCheckIn(STAFF_SCOPE,801,EventCheckInResolveRequest("OPAQUE-TICKET")).confirmable)},
            StaffApiCase("attendance separate confirmation","POST","/api/v1/admin/event-registrations/1101/mark-attended/",{staffFixture("registration")},verify={assertEquals(JsonPrimitive("qr"),staffWire(it)["source"])}){assertEquals(1101,it.markAttended(STAFF_SCOPE,1101,staffBody("""{"source":"qr"}""")).id)},
            StaffApiCase("spaces","GET","/api/v1/admin/makerspaces/7/spaces/?page=1",{staffPage("space")}){assertEquals(901,it.spaces(STAFF_SCOPE,1).results.single().id)},
            StaffApiCase("bookings","GET","/api/v1/admin/spaces/901/bookings/?page=1",{staffPage("booking")}){assertEquals(901,it.bookings(STAFF_SCOPE,901,1).results.single().spaceId)},
            StaffApiCase("approve booking","POST","/api/v1/admin/bookings/1001/approve/",{staffChanged("booking","status",JsonPrimitive("confirmed"))},verify={assertEquals(0L,it.bodySize)}){assertEquals(1001,it.approveBooking(STAFF_SCOPE,1001).id)},
            StaffApiCase("reject booking","POST","/api/v1/admin/bookings/1001/reject/",{staffChanged("booking","status",JsonPrimitive("rejected"))},verify={assertEquals(0L,it.bodySize)}){assertEquals(1001,it.rejectBooking(STAFF_SCOPE,1001).id)}
        )
        const val EVIDENCE="""{"evidence_id":1301,"upload_url":"https://uploads.example.invalid/presign","fields":{"key":"evidence/sample.jpg","policy":"signed-policy"},"object_key":"evidence/sample.jpg"}"""
    }
}
