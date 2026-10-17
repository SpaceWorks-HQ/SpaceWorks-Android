package org.spaceworks.app.staff

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.openapitools.client.infrastructure.*
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.generated.model.*
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.util.UUID

enum class StaffDemoScenario { NORMAL, EMPTY, REFUSED, OFFLINE, RATE_LIMITED, ISSUE_AFTER_ASSIGNMENT_FAILS, KIT_CHECKLIST }

/** Install in src/debug only. All fixtures are local, labelled samples; no storage/API/SDK calls. */
class DemoStaffGateway internal constructor(private val readFixture:(String)->String,private val photo:()->ByteArray):StaffGateway,StaffPreviewControl {
    constructor(context:Context):this(context.applicationContext.assets.let{assets->{name:String->assets.open("staff/$name.json").bufferedReader().use{it.readText()}}},{
        Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888).let{bitmap->
            try{bitmap.eraseColor(Color.LTGRAY);ByteArrayOutputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,80,it);it.toByteArray()}}finally{bitmap.recycle()}}
    })
    private val json=Json{ignoreUnknownKeys=true;explicitNulls=false;serializersModule=SerializersModule{
        contextual(UUID::class,UUIDAdapter);contextual(OffsetDateTime::class,OffsetDateTimeAdapter)
        contextual(java.net.URI::class,URIAdapter);contextual(java.math.BigDecimal::class,BigDecimalAdapter)
    }}
    private data class Workspace(val requests:MutableMap<Int,JsonObject>,val loans:MutableMap<Int,JsonObject>,val bookings:MutableMap<Int,JsonObject>,var attended:Boolean=false)
    private val workspaces=mutableMapOf<Int,Workspace>()
    var scenario=StaffDemoScenario.NORMAL
    override val previewScenarioId get()=scenario.name
    override val previewOptions get()=listOf(
        StaffPreviewOption("NORMAL",StaffStrings.STAFF_PREVIEW_NORMAL),
        StaffPreviewOption("EMPTY",StaffStrings.STAFF_PREVIEW_EMPTY),
        StaffPreviewOption("REFUSED",StaffStrings.STAFF_PREVIEW_REFUSED),
        StaffPreviewOption("OFFLINE",StaffStrings.STAFF_PREVIEW_OFFLINE),
        StaffPreviewOption("RATE_LIMITED",StaffStrings.STAFF_PREVIEW_RATE_LIMITED),
        StaffPreviewOption("ISSUE_AFTER_ASSIGNMENT_FAILS",StaffStrings.STAFF_PREVIEW_PARTIAL_ASSIGNMENT),
        StaffPreviewOption("KIT_CHECKLIST",StaffStrings.STAFF_PREVIEW_KIT_CHECKLIST))
    override fun selectPreviewScenario(id:String,scope:MemberScope):Boolean {
        val selected=StaffDemoScenario.entries.firstOrNull{it.name==id}?:return false
        workspaces.remove(scope.id);scenario=selected;return true
    }
    val authority=StaffAuthorizer{scope,_->scope.id>0}
    /** Main holder still calls this upload only after a local Debug presign. */
    val uploads=StaffPhotoUpload{_,_->refusal()}
    override fun resetAccount(){workspaces.clear();scenario=StaffDemoScenario.NORMAL}
    override fun samplePhoto():ByteArray=photo()
    private fun fixture(name:String)=json.parseToJsonElement(readFixture(name))
    private fun obj(name:String)=fixture(name).jsonObject
    private fun patch(value:JsonObject,vararg fields:Pair<String,JsonElement>)=JsonObject(value+fields.toMap())
    private inline fun <reified T> decode(value:JsonElement):T=json.decodeFromJsonElement(value)
    private fun workspace(scope:MemberScope)=workspaces.getOrPut(scope.id){
        val base=patch(obj("request"),"makerspace_id" to JsonPrimitive(scope.id),"has_kit_checklist" to JsonPrimitive(scenario==StaffDemoScenario.KIT_CHECKLIST))
        val requests=listOf(601 to "pending",602 to "accepted",603 to "issued").associate{(id,status)->id to patch(base,"id" to JsonPrimitive(id),"status" to JsonPrimitive(status),
            "issue_evidence_id" to if(status=="issued")JsonPrimitive(1401)else JsonNull,
            "items" to JsonArray(base.getValue("items").jsonArray.map{item->patch(item.jsonObject,
                "accepted_quantity" to JsonPrimitive(if(status=="pending")0 else 2),
                "issued_quantity" to JsonPrimitive(if(status=="issued")2 else 0),
                "returned_quantity" to JsonPrimitive(0),"damaged_quantity" to JsonPrimitive(0),"missing_quantity" to JsonPrimitive(0))}))}.toMutableMap()
        Workspace(requests,mutableMapOf(701 to obj("loan")),mutableMapOf(1001 to obj("booking")))
    }
    private fun refusal(){when(scenario){StaffDemoScenario.REFUSED->throw ApiFailure(403,"Demo staff access was revoked.",null,null);StaffDemoScenario.OFFLINE->throw java.io.IOException();StaffDemoScenario.RATE_LIMITED->throw ApiFailure(429,"Demo request limit reached.",null,30);else->Unit}}
    private fun envelope(rows:List<JsonElement>)=buildJsonObject{put("count",rows.size);put("next",JsonNull);put("previous",JsonNull);put("results",JsonArray(rows))}
    override suspend fun requests(scope:MemberScope,queue:StaffQueue,page:Int):PaginatedAdminRequestList{
        refusal();val statuses=when(queue){StaffQueue.PENDING->setOf("pending");StaffQueue.ACCEPTED->setOf("accepted");StaffQueue.ACTIVE->setOf("issued","partially_returned")}
        return decode(envelope(if(scenario==StaffDemoScenario.EMPTY)emptyList()else workspace(scope).requests.values.filter{it["status"]?.jsonPrimitive?.content in statuses}))
    }
    private fun updateRequest(scope:MemberScope,id:Int,status:String):AdminRequest{val state=workspace(scope);val old=state.requests[id]?:throw ApiFailure(404,"Sample request not found.",null,null)
        val next=patch(old,"status" to JsonPrimitive(status));state.requests[id]=next;return decode(next)}
    override suspend fun accept(scope:MemberScope,id:Int,body:AcceptRequest):AdminRequest{refusal();val s=workspace(scope);val old=s.requests.getValue(id)
        s.requests[id]=patch(old,"items" to JsonArray(old.getValue("items").jsonArray.map{item->val o=item.jsonObject;patch(o,"accepted_quantity" to JsonPrimitive(body.acceptedQuantities.orEmpty().firstOrNull{it.itemId==o.getValue("id").jsonPrimitive.int}?.quantity?:0))}))
        return updateRequest(scope,id,"accepted")}
    override suspend fun reject(scope:MemberScope,id:Int,body:RejectRequest):AdminRequest{refusal();return updateRequest(scope,id,"rejected")}
    override suspend fun assignBox(scope:MemberScope,id:Int,body:AssignBox):AdminRequest{refusal();val s=workspace(scope);s.requests[id]=patch(s.requests.getValue(id),"assigned_box_label" to JsonPrimitive("Sample kit box"));return decode(s.requests.getValue(id))}
    override suspend fun issue(scope:MemberScope,id:Int,body:IssueRequest):AdminRequest{refusal();if(scenario==StaffDemoScenario.ISSUE_AFTER_ASSIGNMENT_FAILS)throw ApiFailure(409,"Sample inventory changed after box assignment.",null,null)
        val s=workspace(scope);val old=s.requests.getValue(id)
        if(old["status"]?.jsonPrimitive?.content!="accepted")throw ApiFailure(409,"Sample request is no longer accepted.",null,null)
        val items=old.getValue("items").jsonArray.map{item->val row=item.jsonObject;patch(row,"issued_quantity" to JsonPrimitive(number(row,"accepted_quantity")))}
        if(items.none{number(it,"issued_quantity")>0})throw ApiFailure(400,"Choose sample equipment before issuing.",null,null)
        s.requests[id]=patch(old,"items" to JsonArray(items),"issue_evidence_id" to JsonPrimitive(body.evidenceId))
        return updateRequest(scope,id,"issued")}
    private fun number(row:JsonObject,key:String)=(row[key] as? JsonPrimitive)?.intOrNull?:0
    private fun invalidReturn():Nothing=throw ApiFailure(400,"Review the sample outstanding quantities before returning.",null,null)
    private fun resolved(value:ReturnItemResolution,remaining:Int,assets:Set<Int> = emptySet()):List<Int> {
        var counts=listOf(value.returned?:0,value.damaged?:0,value.missing?:0)
        val returnedAssets=value.assets.orEmpty()
        if(returnedAssets.isNotEmpty()){
            if(returnedAssets.map{it.assetId}.distinct().size!=returnedAssets.size||returnedAssets.any{it.assetId !in assets})invalidReturn()
            val actual=listOf("returned","damaged","missing").map{outcome->returnedAssets.count{it.outcome.value==outcome}}
            if(counts.any{it!=0}&&counts!=actual)invalidReturn()
            counts=actual
        }
        if(counts.any{it<0}||counts.sumOf{it.toLong()}>remaining.toLong())invalidReturn()
        return counts
    }
    override suspend fun returnRequest(scope:MemberScope,id:Int,body:ReturnRequest):AdminRequest{refusal()
        val s=workspace(scope);val old=s.requests.getValue(id);val rows=old.getValue("items").jsonArray.map{it.jsonObject}
        if(old["status"]?.jsonPrimitive?.content !in setOf("issued","partially_returned"))invalidReturn()
        if(body.resolutions.map{it.itemId}.distinct().size!=body.resolutions.size||body.resolutions.any{r->rows.none{number(it,"id")==r.itemId}})invalidReturn()
        var changed=false
        val items=rows.map{row->val resolution=body.resolutions.firstOrNull{it.itemId==number(row,"id")}?:return@map row
            val remaining=number(row,"issued_quantity")-number(row,"returned_quantity")-number(row,"damaged_quantity")-number(row,"missing_quantity")
            val counts=resolved(resolution,remaining);if(counts.any{it>0})changed=true
            patch(row,"returned_quantity" to JsonPrimitive(number(row,"returned_quantity")+counts[0]),
                "damaged_quantity" to JsonPrimitive(number(row,"damaged_quantity")+counts[1]),"missing_quantity" to JsonPrimitive(number(row,"missing_quantity")+counts[2]))}
        if(!changed)invalidReturn()
        val complete=items.all{number(it,"issued_quantity")==number(it,"returned_quantity")+number(it,"damaged_quantity")+number(it,"missing_quantity")}
        s.requests[id]=patch(old,"items" to JsonArray(items),"return_evidence_ids" to JsonArray((old["return_evidence_ids"]?.jsonArray.orEmpty()+JsonPrimitive(body.evidenceId)).distinct()))
        return updateRequest(scope,id,if(complete)"returned"else "partially_returned")}
    override suspend fun resolveQR(scope:MemberScope,body:QrResolve):QrResolveResult{refusal();val value=body.payload
        if(value !in setOf("DEMO-BOX","DEMO-ASSET","DEMO-PRODUCT"))throw ApiFailure(404,"Sample code not found. Use the labelled sample code.",null,null)
        val base=obj("qr");val box=value=="DEMO-BOX";val type=if(box)"box"else if(value=="DEMO-ASSET")"asset"else "product"
        val qr=patch(base.getValue("qr").jsonObject,"makerspace" to JsonPrimitive(scope.id),"payload" to JsonPrimitive(value),"target_type" to JsonPrimitive(type),"target_id" to JsonPrimitive(if(box)91 else if(type=="asset")1301 else 901),"id" to JsonPrimitive(if(box)1201 else 1202))
        val target=buildJsonObject{put("type",type);put("id",if(box)91 else if(type=="asset")1301 else 901);put("label",if(box)"Sample kit box"else "Sample equipment");if(!box){put("product_id",901);put("status","available")}}
        return decode(patch(base,"qr" to qr,"target" to target,"allowed_actions" to JsonArray(listOf("view","direct_handout").map(::JsonPrimitive))))}
    override suspend fun directLoans(scope:MemberScope,page:Int):PaginatedDirectLoanList{refusal();return decode(envelope(if(scenario==StaffDemoScenario.EMPTY)emptyList()else workspace(scope).loans.values.toList()))}
    override suspend fun directMembers(scope:MemberScope,page:Int):PaginatedDirectLoanMemberList{refusal();return decode(if(scenario==StaffDemoScenario.EMPTY)envelope(emptyList())else fixture("members"))}
    override suspend fun inventory(scope:MemberScope,page:Int,search:String):PaginatedInventoryProductAdminList{refusal();val base=obj("inventory")
        return decode(if(scenario==StaffDemoScenario.EMPTY)envelope(emptyList())else patch(base,"results" to JsonArray(base.getValue("results").jsonArray.map{patch(it.jsonObject,"makerspace" to JsonPrimitive(scope.id))})))}
    override suspend fun containers(scope:MemberScope,page:Int):PaginatedBoxList{refusal();return decode(if(scenario==StaffDemoScenario.EMPTY)envelope(emptyList())else fixture("containers"))}
    override suspend fun createDirect(scope:MemberScope,body:DirectLoanIssue):DirectLoan{refusal();val s=workspace(scope);val id=(s.loans.keys.maxOrNull()?:700)+1
        val explicit=body.items.orEmpty();val scans=body.qrPayloads.orEmpty()
        if(body.containerId!=null||explicit.any{it.productId!=901||it.quantity !in 1..4}||explicit.map{it.productId}.distinct().size!=explicit.size||
            scans.any{it !in setOf("DEMO-PRODUCT","DEMO-ASSET")}||scans.size>1||explicit.isNotEmpty()&&scans.isNotEmpty()||explicit.isEmpty()&&scans.isEmpty())
            throw ApiFailure(400,"Choose the labelled sample product or asset once.",null,null)
        val quantity=explicit.singleOrNull()?.quantity?:1;val individual=scans.singleOrNull()=="DEMO-ASSET"
        val item=buildJsonObject{put("product_name","Sample precision toolkit");put("quantity",quantity)}
        val returnItem=buildJsonObject{put("item_id",id*100+1);put("product_name","Sample precision toolkit");put("remaining_quantity",quantity)
            put("tracking_mode",if(individual)"individual"else "quantity");put("assets",JsonArray(if(individual)listOf(buildJsonObject{put("asset_id",1301);put("asset_tag","Sample asset 1301")})else emptyList()))}
        val loan=patch(obj("loan"),"id" to JsonPrimitive(id),"status" to JsonPrimitive("active"),"issue_evidence_id" to JsonPrimitive(body.evidenceId),
            "public_token" to JsonPrimitive(UUID.nameUUIDFromBytes("demo-staff-${scope.id}-$id".toByteArray()).toString()),
            "items" to JsonArray(listOf(item)),"return_items" to JsonArray(listOf(returnItem)),"has_kit_checklist" to JsonPrimitive(scenario==StaffDemoScenario.KIT_CHECKLIST),
            "target_type" to JsonPrimitive(if(individual)"asset"else "product"),"return_scan_required" to JsonPrimitive(individual))
        s.loans[id]=loan;return decode(loan)}
    override suspend fun returnDirect(scope:MemberScope,id:Int,body:DirectLoanReturn):DirectLoan{refusal();val s=workspace(scope);val old=s.loans.getValue(id)
        if(old["status"]?.jsonPrimitive?.content!="active")invalidReturn()
        if((old["return_scan_required"] as? JsonPrimitive)?.booleanOrNull==true&&body.qrPayload!="DEMO-ASSET")invalidReturn()
        val rows=old.getValue("return_items").jsonArray.map{it.jsonObject};val resolutions=body.resolutions.orEmpty()
        if(resolutions.map{it.itemId}.distinct().size!=resolutions.size||resolutions.any{r->rows.none{number(it,"item_id")==r.itemId}})invalidReturn()
        var changed=false
        val items=rows.map{row->val resolution=resolutions.firstOrNull{it.itemId==number(row,"item_id")}?:return@map row
            val assets=row["assets"]?.jsonArray.orEmpty();val counts=resolved(resolution,number(row,"remaining_quantity"),assets.map{number(it.jsonObject,"asset_id")}.toSet())
            if(assets.isNotEmpty()&&resolution.assets.orEmpty().isEmpty()&&counts.any{it>0})invalidReturn()
            if(counts.any{it>0})changed=true
            val resolvedIds=resolution.assets.orEmpty().map{it.assetId}.toSet()
            patch(row,"remaining_quantity" to JsonPrimitive(number(row,"remaining_quantity")-counts.sum()),"assets" to JsonArray(assets.filter{number(it.jsonObject,"asset_id") !in resolvedIds}))}
        if(!changed)invalidReturn()
        val loan=patch(old,"return_items" to JsonArray(items),"status" to JsonPrimitive(if(items.all{number(it,"remaining_quantity")==0})"returned"else "active"),
            "return_evidence_id" to JsonPrimitive(body.evidenceId),"return_notes" to JsonPrimitive(body.notes))
        s.loans[id]=loan;return decode(loan)}
    override suspend fun evidence(scope:MemberScope,body:EvidenceUrlRequest):EvidenceUrlResponse{refusal();return decode(buildJsonObject{
        put("evidence_id",1401);put("object_key","demo/evidence.jpg");put("upload_url","https://sample.invalid/never-upload");put("method","POST");put("fields",buildJsonObject{});put("headers",buildJsonObject{})})}
    override suspend fun kitPreview(scope:MemberScope,body:KitPreviewInput):KitPreview{refusal();return decode(fixture("kits"))}
    override suspend fun events(scope:MemberScope,page:Int):EventListWithDefaults{refusal();val row=patch(obj("event"),"makerspace_id" to JsonPrimitive(scope.id));return decode(patch(envelope(if(scenario==StaffDemoScenario.EMPTY)emptyList()else listOf(row)),"default_registration_audience" to JsonPrimitive("members")))}
    override suspend fun resolveCheckIn(scope:MemberScope,eventId:Int,body:EventCheckInResolveRequest):EventCheckInResolveResponse{refusal();if(body.checkinToken!="DEMO-CHECKIN")throw ApiFailure(404,"This is not a sample check-in code for this event.",null,null)
        return decode(patch(obj("checkin"),"status" to JsonPrimitive(if(workspace(scope).attended)"attended"else "registered"),"confirmable" to JsonPrimitive(!workspace(scope).attended)))}
    override suspend fun markAttended(scope:MemberScope,registrationId:Int,body:EventAttendanceMark):EventRegistrationAdmin{refusal();val s=workspace(scope);if(s.attended)throw ApiFailure(409,"Attendance was already recorded.",null,null);s.attended=true
        return decode(patch(obj("registration"),"status" to JsonPrimitive("attended")))}
    override suspend fun spaces(scope:MemberScope,page:Int):BookableSpaceListResponse{refusal();return decode(envelope(if(scenario==StaffDemoScenario.EMPTY)emptyList()else listOf(patch(obj("space"),"makerspace_id" to JsonPrimitive(scope.id)))))}
    override suspend fun bookings(scope:MemberScope,spaceId:Int,page:Int):BookingListResponse{refusal();return decode(envelope(if(scenario==StaffDemoScenario.EMPTY)emptyList()else workspace(scope).bookings.values.toList()))}
    private fun decide(scope:MemberScope,id:Int,status:String):BookingAdmin{refusal();val s=workspace(scope);val row=patch(s.bookings.getValue(id),"status" to JsonPrimitive(status));s.bookings[id]=row;return decode(row)}
    override suspend fun approveBooking(scope:MemberScope,id:Int)=decide(scope,id,"confirmed")
    override suspend fun rejectBooking(scope:MemberScope,id:Int)=decide(scope,id,"rejected")
}
