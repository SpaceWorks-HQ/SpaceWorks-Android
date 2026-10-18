package org.spaceworks.app.payments

import org.spaceworks.app.member.MemberScope

/** Immutable display projections; generated models remain the only transport models. */
data class PaymentRecord(val id:String,val label:String,val status:String?,val amount:String,val currency:String,
    val dueDate:String?,val dueState:String,val onlineAvailable:Boolean,val settlement:PaymentSettlement?,val refunds:List<PaymentRefund>)
data class PaymentSettlement(val method:String,val receivedAt:String,val reference:String)
data class PaymentRefund(val amount:String,val currency:String,val status:String,val reason:String,val reference:String)
data class PaymentDocument(val id:String,val paymentId:String,val number:String,val kind:String,val amount:String,val currency:String,val issuedAt:String)
data class PaymentDocumentPage(val rows:List<PaymentDocument>,val nextPage:Int?,val count:Int)

/** Ephemeral SDK input, never placed in persisted state, logs or a StateFlow. The server selects the vendor. */
sealed class PaymentIntent(val paymentId:String) {
    class Stripe(paymentId:String,val clientSecret:String,val publishableKey:String,val customerId:String?,val ephemeralKey:String?):PaymentIntent(paymentId) {
        override fun valid()=publishableKey.startsWith("pk_") && clientSecret.isNotBlank() && (customerId.isNullOrBlank()==ephemeralKey.isNullOrBlank())
    }
    /** [amountMinor] is the server's integer minor-unit amount; it is passed to the SDK unchanged. */
    class Razorpay(paymentId:String,val keyId:String,val orderId:String,val amountMinor:Long,val currency:String,val name:String,val description:String):PaymentIntent(paymentId) {
        override fun valid()=keyId.startsWith("rzp_") && orderId.startsWith("order_") && amountMinor>0 && Regex("[A-Za-z]{3}").matches(currency)
    }
    abstract fun valid():Boolean
    override fun toString()="PaymentIntent(redacted)"
}
/** Stripe intent shorthand used by fixtures and the Stripe rail. */
fun PaymentIntent(paymentId:String,clientSecret:String,publishableKey:String,customerId:String?,ephemeralKey:String?):PaymentIntent=
    PaymentIntent.Stripe(paymentId,clientSecret,publishableKey,customerId,ephemeralKey)
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
