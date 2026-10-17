package org.spaceworks.app.platform

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow

/** Activity-owned bindings. Never retain this object in a controller or account cache. */
class NativeCheckoutBindings(
    val state:StateFlow<NativePaymentState>,
    val launchStripe:(StripeSheetInput)->PaymentLaunchResult,
    val acknowledge:()->Unit,
    val recoverInterrupted:()->Unit,
)

val LocalNativeCheckout=staticCompositionLocalOf<NativeCheckoutBindings?> {null}
