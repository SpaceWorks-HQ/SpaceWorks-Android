package org.spaceworks.app.payments

import org.spaceworks.app.platform.MobileText

import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.HttpUrl
import org.spaceworks.app.auth.requireBody
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiSession
import org.spaceworks.generated.api.PaymentsApi

/** The signed API URL is parsed into generated arguments; it is never opened directly. */
object PaymentDocumentLinks {
    fun token(value:String,id:String,origin:HttpUrl):String {
        val uri=checked(value,origin,"/api/v1/payment-documents/${positivePaymentId(id)}/download")
        require(uri.querySize==1 && uri.queryParameterName(0)=="token") { throw org.spaceworks.app.platform.MobileCopyException(PaymentStrings.PAYMENT_THIS_DOCUMENT_LINK_IS_UNAVAILABLE_REQUEST_A_NEW_LINK_AND_TRY) }
        return requireNotNull(uri.queryParameterValue(0)) {throw org.spaceworks.app.platform.MobileCopyException(PaymentStrings.PAYMENT_THIS_DOCUMENT_LINK_IS_UNAVAILABLE_REQUEST_A_NEW_LINK_AND_TRY)}.also {
            require(it.isNotEmpty() && it.toByteArray().size<=4096 && it.none(::control)) { throw org.spaceworks.app.platform.MobileCopyException(PaymentStrings.PAYMENT_THIS_DOCUMENT_LINK_IS_UNAVAILABLE_REQUEST_A_NEW_LINK_AND_TRY) }
        }
    }
    fun next(value:String?,page:Int,scope:MemberScope,origin:HttpUrl):Int? {
        if(value==null)return null
        val uri=checked(value,origin,"/api/v1/member/makerspaces/${scope.id}/payment-documents")
        require(uri.querySize==1 && uri.queryParameterName(0)=="page") { throw org.spaceworks.app.platform.MobileCopyException(PaymentStrings.PAYMENT_THE_NEXT_DOCUMENT_PAGE_IS_UNAVAILABLE_REFRESH_AND_TRY_AGAIN) }
        val raw=uri.queryParameterValue(0).orEmpty()
        require(raw.isNotEmpty() && raw.all {it in '0'..'9'}) {throw org.spaceworks.app.platform.MobileCopyException(PaymentStrings.PAYMENT_THE_NEXT_DOCUMENT_PAGE_IS_UNAVAILABLE_REFRESH_AND_TRY_AGAIN)}
        return requireNotNull(raw.toIntOrNull()) {throw org.spaceworks.app.platform.MobileCopyException(PaymentStrings.PAYMENT_THE_NEXT_DOCUMENT_PAGE_IS_UNAVAILABLE_REFRESH_AND_TRY_AGAIN)}.also {require(it>page) {throw org.spaceworks.app.platform.MobileCopyException(PaymentStrings.PAYMENT_THE_NEXT_DOCUMENT_PAGE_IS_UNAVAILABLE_REFRESH_AND_TRY_AGAIN)}}
    }
    private fun checked(value:String,origin:HttpUrl,path:String):HttpUrl = try {
        val message=org.spaceworks.app.platform.MobileCopyException(PaymentStrings.PAYMENT_THIS_DOCUMENT_LINK_IS_UNAVAILABLE_REQUEST_A_NEW_LINK_AND_TRY)
        require(value.toByteArray().size<=8192 && '\\' !in value && value.none(::control)) {throw message}
        val raw=URI(value)
        require(raw.rawPath==path && raw.rawFragment==null && raw.rawUserInfo==null) {throw message}
        require(raw.rawAuthority?.contains('@')!=true && raw.rawQuery?.substringBefore('=') in setOf("page","token")) {throw message}
        val target=requireNotNull(origin.resolve(value)) {throw message}
        require(target.scheme==origin.scheme && target.host==origin.host && target.port==origin.port && target.username.isEmpty() && target.password.isEmpty()) {throw message}
        target
    } catch (_:Exception) {throw org.spaceworks.app.platform.MobileCopyException(PaymentStrings.PAYMENT_THIS_DOCUMENT_LINK_IS_UNAVAILABLE_REQUEST_A_NEW_LINK_AND_TRY)}
    private fun control(c:Char)=c.isISOControl()
}
internal fun positivePaymentId(value:String):Int = requireNotNull(value.toIntOrNull()).also {require(it>0 && it.toString()==value)}

class GeneratedPaymentsGateway(
    private val session:ApiSession,
    private val text:MobileText=MobileText.ENGLISH,
    private val operationEpoch:()->Long={session.installationEpoch}
):PaymentsGateway {
    private fun api(scope:MemberScope)=session.scopedApi(PaymentsApi::class.java,scope.id)
    override suspend fun payments(scope:MemberScope)=api(scope).apiV1MemberMakerspacesPaymentsList(makerspaceId=scope.id).requireBody().map { row->
        PaymentRecord(row.id.toString(),row.subjectLabel,row.status?.value,row.amount.toPlainString(),row.currency,row.dueDate,row.dueState,row.onlinePaymentAvailable,
            row.settlement?.let {PaymentSettlement(it.method,it.receivedAt.toString(),it.reference)},
            row.refunds.map {PaymentRefund(it.amount.toPlainString(),it.currency,it.status,it.reason,it.reference)})
    }
    override suspend fun intent(scope:MemberScope,paymentId:String):PaymentIntent {
        val value=api(scope).apiV1MemberMakerspacesPaymentsMobileIntentCreate(scope.id,positivePaymentId(paymentId)).requireBody()
        return PaymentIntent(value.paymentId.toString(),value.clientSecret,value.publishableKey,value.customerId,value.ephemeralKey)
    }
    override suspend fun documents(scope:MemberScope,page:Int):PaymentDocumentPage {
        require(page>0)
        val value=api(scope).apiV1MemberMakerspacesPaymentDocumentsRetrieve(scope.id,page).requireBody()
        return PaymentDocumentPage(value.results.map {PaymentDocument(it.id.toString(),it.payment.toString(),it.number,it.kind.value,it.amount.toPlainString(),it.currency,it.issuedAt.toString())},
            PaymentDocumentLinks.next(value.next?.toString(),page,scope,session.server),value.count)
    }
    override suspend fun documentPDF(scope:MemberScope,documentId:String):ByteArray {
        val expectedEpoch=operationEpoch()
        fun owned()=operationEpoch()==expectedEpoch
        fun checkOwner(){if(!owned())throw CancellationException("Payment document owner changed")}
        try {
        val id=positivePaymentId(documentId)
        checkOwner()
        val grant=session.scopedApi(PaymentsApi::class.java,scope.id){call->if(!owned())call.cancel()}
            .apiV1MemberMakerspacesPaymentDocumentsDownloadUrlRetrieve(id,scope.id).requireBody()
        checkOwner()
        require(grant.expiresIn>0) { text(PaymentStrings.PAYMENT_THIS_DOCUMENT_LINK_EXPIRED_TRY_AGAIN_TO_REQUEST_A_NEW_LINK) }
        val token=PaymentDocumentLinks.token(grant.url,documentId,session.server)
        // Unlike the public export ZIP, this documented PDF operation explicitly requires jwtAuth.
        return coroutineScope { suspendCancellableCoroutine { continuation->
            val active=AtomicReference<Call?>()
            val reader=launch(Dispatchers.IO) {
                try {
                    checkOwner()
                    val service=session.scopedApi(PaymentsApi::class.java,scope.id) {call->
                        active.set(call)
                        if(!continuation.isActive || !owned())call.cancel()
                    }
                    checkOwner()
                    val body=service.apiV1PaymentDocumentsDownloadRetrieve(id,token).requireBody()
                    val bytes=body.use {
                        checkOwner()
                        require(it.contentType()?.let {type->type.type=="application" && type.subtype=="pdf"}==true) { text(PaymentStrings.PAYMENT_THIS_DOCUMENT_CANNOT_BE_OPENED) }
                        require(it.contentLength()<=PDF_LIMIT) { text(PaymentStrings.PAYMENT_THIS_DOCUMENT_IS_TOO_LARGE_TO_OPEN) }
                        val output=ByteArrayOutputStream();val buffer=ByteArray(8192);val input=it.byteStream()
                        while(true){checkOwner();val read=input.read(buffer,0,minOf(buffer.size,PDF_LIMIT-output.size()+1));checkOwner();if(read<0)break;require(read<=PDF_LIMIT-output.size()) {text(PaymentStrings.PAYMENT_THIS_DOCUMENT_IS_TOO_LARGE_TO_OPEN)};output.write(buffer,0,read)}
                        output.toByteArray().also {value->require(value.size>=5 && value.copyOfRange(0,5).contentEquals("%PDF-".toByteArray())) {text(PaymentStrings.PAYMENT_THIS_DOCUMENT_CANNOT_BE_OPENED)}}
                    }
                    checkOwner()
                    continuation.resume(bytes)
                }catch(failure:Exception){continuation.resumeWithException(failure)}finally{active.set(null)}
            }
            continuation.invokeOnCancellation {active.get()?.cancel();reader.cancel()}
        } }
        }catch(failure:Exception){
            if(!owned())throw CancellationException("Payment document owner changed").apply{initCause(failure)}
            throw failure
        }
    }
    companion object {private const val PDF_LIMIT=25*1024*1024}
}
