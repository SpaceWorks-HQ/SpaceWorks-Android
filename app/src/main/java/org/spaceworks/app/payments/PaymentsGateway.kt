package org.spaceworks.app.payments

import org.spaceworks.app.member.MemberScope

/** Immutable display projections; generated models remain the only transport models. */
data class PaymentRecord(val id:String,val label:String,val status:String?,val amount:String,val currency:String,
    val dueDate:String?,val dueState:String,val onlineAvailable:Boolean,val settlement:PaymentSettlement?,val refunds:List<PaymentRefund>)
data class PaymentSettlement(val method:String,val receivedAt:String,val reference:String)
data class PaymentRefund(val amount:String,val currency:String,val status:String,val reason:String,val reference:String)
data class PaymentDocument(val id:String,val paymentId:String,val number:String,val kind:String,val amount:String,val currency:String,val issuedAt:String)
data class PaymentDocumentPage(val rows:List<PaymentDocument>,val nextPage:Int?,val count:Int)

/** Ephemeral SDK input, never placed in persisted state, logs or a StateFlow. */
class PaymentIntent(val paymentId:String,val clientSecret:String,val publishableKey:String,val customerId:String?,val ephemeralKey:String?) {
    override fun toString()="PaymentIntent(redacted)"
}
class PreparedPayment(val reference:String,val intent:PaymentIntent) {
    override fun toString()="PreparedPayment(redacted)"
}
interface PaymentsGateway {
    suspend fun payments(scope:MemberScope):List<PaymentRecord>
    suspend fun intent(scope:MemberScope,paymentId:String):PaymentIntent
    suspend fun documents(scope:MemberScope,page:Int):PaymentDocumentPage
    suspend fun documentPDF(scope:MemberScope,documentId:String):ByteArray
}
enum class DemoPaymentScenario(val label:String) { PAID("Server confirms payment"), CANCELLED("Checkout cancelled"), FAILED("Checkout failed"), DELAYED("Confirmation delayed"), OFFLINE("Offline"), REFUSED("Payment unavailable") }
interface PaymentPreviewControl {
    suspend fun scenario(scope:MemberScope,paymentId:String,value:DemoPaymentScenario)
    suspend fun confirmDelayed(scope:MemberScope,paymentId:String)
    fun resetAccount()
}
