package org.spaceworks.app.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class NativePaymentProvider { STRIPE, RAZORPAY }
enum class SheetCloseReason { SUBMITTED, CANCELLED, FAILED, INTERRUPTED }
enum class PaymentLaunchResult {
    STARTED, ALREADY_PRESENTING, AWAITING_SERVER_REFRESH, DEMO_BLOCKED, INVALID_CONFIGURATION, HOST_NOT_READY,
}

sealed interface NativePaymentState {
    data object Idle : NativePaymentState
    data class Presenting(val paymentReference: String, val provider: NativePaymentProvider) : NativePaymentState
    /** Every sheet closure requires an API refetch. SUBMITTED is deliberately never named "paid". */
    data class AwaitingServer(val paymentReference: String, val provider: NativePaymentProvider,
        val reason: SheetCloseReason, val message: String? = null) : NativePaymentState
}

/** Local callback state, not a transport/payment status model. It stores no SDK credentials. */
class PaymentCallbackState(initial: NativePaymentState = NativePaymentState.Idle,
    private val saveState: (NativePaymentState) -> Unit = {}) {
    private val mutable = MutableStateFlow(initial)
    val state = mutable.asStateFlow()

    @Synchronized fun begin(reference: String, provider: NativePaymentProvider, demo: Boolean): PaymentLaunchResult {
        if (demo) return PaymentLaunchResult.DEMO_BLOCKED
        if (reference.isBlank()) return PaymentLaunchResult.INVALID_CONFIGURATION
        if (mutable.value is NativePaymentState.Presenting) return PaymentLaunchResult.ALREADY_PRESENTING
        if (mutable.value is NativePaymentState.AwaitingServer) return PaymentLaunchResult.AWAITING_SERVER_REFRESH
        val active = NativePaymentState.Presenting(reference, provider)
        saveState(active)
        mutable.value = active
        return PaymentLaunchResult.STARTED
    }

    @Synchronized fun finish(provider: NativePaymentProvider, reason: SheetCloseReason, message: String? = null): Boolean {
        val active = mutable.value as? NativePaymentState.Presenting ?: return false
        if (active.provider != provider) return false
        val closed = NativePaymentState.AwaitingServer(active.paymentReference, provider, reason, message)
        saveState(closed)
        mutable.value = closed
        return true
    }

    /** Explicit recovery after process death/lost SDK activity. Recheck the server before offering another payment. */
    @Synchronized fun recoverInterrupted(message:String="Check the payment status before trying again."): Boolean {
        val active = mutable.value as? NativePaymentState.Presenting ?: return false
        return finish(active.provider, SheetCloseReason.INTERRUPTED, message)
    }

    @Synchronized fun acknowledgeServerRefresh() {
        if (mutable.value is NativePaymentState.AwaitingServer) {
            saveState(NativePaymentState.Idle)
            mutable.value = NativePaymentState.Idle
        }
    }
}

class NativePaymentViewModel(handle: SavedStateHandle) : ViewModel() {
    private val restored: NativePaymentState = handle.get<String>("payment-reference")?.let { reference ->
        handle.get<String>("payment-provider")?.let { value ->
            runCatching {
                val provider = NativePaymentProvider.valueOf(value)
                val reason = handle.get<String>("payment-close-reason")
                if (reason == null) NativePaymentState.Presenting(reference, provider)
                else NativePaymentState.AwaitingServer(reference, provider, SheetCloseReason.valueOf(reason), handle["payment-message"])
            }.getOrNull()
        }
    } ?: NativePaymentState.Idle
    val callbacks = PaymentCallbackState(restored) { state ->
        when (state) {
            NativePaymentState.Idle -> {
                handle.remove<String>("payment-reference"); handle.remove<String>("payment-provider")
                handle.remove<String>("payment-close-reason"); handle.remove<String>("payment-message")
            }
            is NativePaymentState.Presenting -> {
                handle["payment-reference"] = state.paymentReference
                handle["payment-provider"] = state.provider.name
                handle.remove<String>("payment-close-reason"); handle.remove<String>("payment-message")
            }
            is NativePaymentState.AwaitingServer -> {
                handle["payment-reference"] = state.paymentReference
                handle["payment-provider"] = state.provider.name
                handle["payment-close-reason"] = state.reason.name
                handle["payment-message"] = state.message
            }
        }
    }
}

/** Values copied from a generated gateway response; no amount calculation or API request lives here. */
sealed interface NativeSheetInput { val paymentReference: String }

class StripeSheetInput(override val paymentReference: String, val merchantName: String, val publishableKey: String,
    val clientSecret: String, val customerId: String? = null, val ephemeralKey: String? = null) : NativeSheetInput {
    fun valid(): Boolean = paymentReference.isNotBlank() && merchantName.isNotBlank() && publishableKey.startsWith("pk_") &&
        clientSecret.isNotBlank() && (customerId.isNullOrBlank() == ephemeralKey.isNullOrBlank())
    override fun toString() = "StripeSheetInput(redacted)"
}

class RazorpaySheetInput(override val paymentReference: String, val merchantName: String, val keyId: String,
    val orderId: String, val amountMinor: Long, val currency: String, val description: String = "") : NativeSheetInput {
    fun valid(): Boolean = paymentReference.isNotBlank() && merchantName.isNotBlank() && keyId.startsWith("rzp_") &&
        orderId.startsWith("order_") && amountMinor > 0 && Regex("[A-Za-z]{3}").matches(currency)
    override fun toString() = "RazorpaySheetInput(redacted)"
}

internal fun stripeCloseReason(result: PaymentSheetResult): SheetCloseReason = when (result) {
    is PaymentSheetResult.Completed -> SheetCloseReason.SUBMITTED
    is PaymentSheetResult.Canceled -> SheetCloseReason.CANCELLED
    is PaymentSheetResult.Failed -> SheetCloseReason.FAILED
}

/** Construct once in Activity.onCreate BEFORE STARTED; Stripe restores its Activity Result registration. */
class StripeSheetAdapter(private val activity: ComponentActivity,
    val callbacks: PaymentCallbackState = ViewModelProvider(activity)[NativePaymentViewModel::class.java].callbacks) {
    private val text=MobileText.resources(activity.applicationContext)
    init { check(!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { "Register payment adapters in onCreate." } }
    private val sheet = PaymentSheet.Builder { result ->
        val reason = stripeCloseReason(result)
        callbacks.finish(NativePaymentProvider.STRIPE, reason,
            if (reason == SheetCloseReason.FAILED) text(org.spaceworks.app.payments.PaymentStrings.PAYMENT_CHECKOUT_COULD_NOT_FINISH_CHECK_THE_PAYMENT_STATUS_AND_TRY_A) else null)
    }.build(activity)

    @MainThread fun launch(input: StripeSheetInput, demo: Boolean): PaymentLaunchResult {
        if (demo) return PaymentLaunchResult.DEMO_BLOCKED
        if (!input.valid()) return PaymentLaunchResult.INVALID_CONFIGURATION
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || activity.isFinishing || activity.isDestroyed) return PaymentLaunchResult.HOST_NOT_READY
        val result = callbacks.begin(input.paymentReference, NativePaymentProvider.STRIPE, false)
        if (result != PaymentLaunchResult.STARTED) return result
        try {
            PaymentConfiguration.init(activity, input.publishableKey)
            val customer = if (!input.customerId.isNullOrBlank() && !input.ephemeralKey.isNullOrBlank()) {
                PaymentSheet.CustomerConfiguration(input.customerId, input.ephemeralKey)
            } else null
            sheet.presentWithPaymentIntent(input.clientSecret, PaymentSheet.Configuration(
                merchantDisplayName = input.merchantName, customer = customer, allowsDelayedPaymentMethods = false,
                appearance = stripeBrandAppearance(),
            ))
        } catch (_: Exception) {
            callbacks.finish(NativePaymentProvider.STRIPE, SheetCloseReason.FAILED, text(org.spaceworks.app.payments.PaymentStrings.PAYMENT_SDK_CHECKOUT_UNAVAILABLE))
        }
        return result
    }
}

/** Construct once in Activity.onCreate alongside StripeSheetAdapter, using their shared ViewModel. */
class RazorpaySheetAdapter(private val activity: ComponentActivity,
    val callbacks: PaymentCallbackState = ViewModelProvider(activity)[NativePaymentViewModel::class.java].callbacks) {
    private val text=MobileText.resources(activity.applicationContext)
    init { check(!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { "Register payment adapters in onCreate." } }
    private val launcher = activity.registerForActivityResult(RazorpayCheckoutContract()) { reason ->
        callbacks.finish(NativePaymentProvider.RAZORPAY, reason,
            if (reason == SheetCloseReason.FAILED) text(org.spaceworks.app.payments.PaymentStrings.PAYMENT_CHECKOUT_COULD_NOT_FINISH_CHECK_THE_PAYMENT_STATUS_AND_TRY_A) else null)
    }

    @MainThread fun launch(input: RazorpaySheetInput, demo: Boolean): PaymentLaunchResult {
        if (demo) return PaymentLaunchResult.DEMO_BLOCKED
        if (!input.valid()) return PaymentLaunchResult.INVALID_CONFIGURATION
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || activity.isFinishing || activity.isDestroyed) return PaymentLaunchResult.HOST_NOT_READY
        val result = callbacks.begin(input.paymentReference, NativePaymentProvider.RAZORPAY, false)
        if (result != PaymentLaunchResult.STARTED) return result
        try { launcher.launch(input) }
        catch (_: Exception) { callbacks.finish(NativePaymentProvider.RAZORPAY, SheetCloseReason.FAILED, text(org.spaceworks.app.payments.PaymentStrings.PAYMENT_SDK_CHECKOUT_UNAVAILABLE)) }
        return result
    }
}

private class RazorpayCheckoutContract : ActivityResultContract<RazorpaySheetInput, SheetCloseReason>() {
    override fun createIntent(context: Context, input: RazorpaySheetInput): Intent =
        Intent(context, RazorpayCheckoutActivity::class.java)
            .putExtra("reference", input.paymentReference).putExtra("merchant", input.merchantName)
            .putExtra("key", input.keyId).putExtra("order", input.orderId)
            .putExtra("amount", input.amountMinor).putExtra("currency", input.currency)
            .putExtra("description", input.description)
    override fun parseResult(resultCode: Int, intent: Intent?): SheetCloseReason = when {
        resultCode == Activity.RESULT_OK -> SheetCloseReason.SUBMITTED
        intent?.getBooleanExtra("interrupted", false) == true -> SheetCloseReason.INTERRUPTED
        intent?.getBooleanExtra("failed", false) == true -> SheetCloseReason.FAILED
        else -> SheetCloseReason.CANCELLED
    }
}

/** Private SDK host: declare exported=false. Only the provider UI runs here; no web/API session is passed. */
class RazorpayCheckoutActivity : ComponentActivity(), PaymentResultWithDataListener {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // Never charge/open the order a second time on rotation or process recreation.
            // Checkout can still deliver its callback. If it was interrupted, offer explicit recovery.
            val dark=getSharedPreferences("deployment",0).getBoolean("theme.dark",false)
            androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).apply {
                isAppearanceLightStatusBars=!dark
                isAppearanceLightNavigationBars=!dark
            }
            setContent {
                org.spaceworks.app.theme.SpaceWorksTheme(dark) {
                    CheckoutRecoveryScreen {
                        setResult(Activity.RESULT_CANCELED, Intent().putExtra("interrupted", true)); finish()
                    }
                }
            }
            return
        }
        val input = RazorpaySheetInput(intent.getStringExtra("reference").orEmpty(), intent.getStringExtra("merchant").orEmpty(),
            intent.getStringExtra("key").orEmpty(), intent.getStringExtra("order").orEmpty(),
            intent.getLongExtra("amount", 0), intent.getStringExtra("currency").orEmpty(),
            intent.getStringExtra("description").orEmpty())
        if (!input.valid()) { fail(); return }
        try {
            val options = JSONObject().put("name", input.merchantName).put("order_id", input.orderId)
                .put("amount", input.amountMinor).put("currency", input.currency)
                .apply { if (input.description.isNotBlank()) put("description", input.description) }
                .put("theme.color",razorpayBrandColor())
            Checkout().apply { setKeyID(input.keyId) }.open(this, options)
        } catch (_: Exception) { fail() }
    }
    override fun onPaymentSuccess(paymentId: String?, paymentData: PaymentData?) {
        // Provider callbacks only close the sheet. The caller polls its own generated payment endpoint.
        setResult(Activity.RESULT_OK); finish()
    }
    override fun onPaymentError(code: Int, response: String?, paymentData: PaymentData?) {
        if (code == Checkout.PAYMENT_CANCELED) { setResult(Activity.RESULT_CANCELED); finish() }
        else fail()
    }
    private fun fail() { setResult(Activity.RESULT_CANCELED, Intent().putExtra("failed", true)); finish() }
}
