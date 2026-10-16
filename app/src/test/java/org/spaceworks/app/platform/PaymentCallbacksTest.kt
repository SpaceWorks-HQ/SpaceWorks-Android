package org.spaceworks.app.platform

import org.junit.Assert.*
import org.junit.Test

class PaymentCallbacksTest {
    @Test fun demoCannotLaunchOrChangePaymentState() {
        var persisted = 0
        val callbacks = PaymentCallbackState { persisted++ }
        assertEquals(PaymentLaunchResult.DEMO_BLOCKED, callbacks.begin("charge-1", NativePaymentProvider.STRIPE, true))
        assertEquals(NativePaymentState.Idle, callbacks.state.value)
        assertEquals(0, persisted)
    }

    @Test fun providerSuccessRequiresServerRefreshAndNeverBecomesPaidLocally() {
        val saved = mutableListOf<NativePaymentState>()
        val callbacks = PaymentCallbackState(saveState = { saved.add(it) })
        assertEquals(PaymentLaunchResult.STARTED, callbacks.begin("charge-1", NativePaymentProvider.STRIPE, false))
        assertEquals(PaymentLaunchResult.ALREADY_PRESENTING, callbacks.begin("charge-2", NativePaymentProvider.RAZORPAY, false))
        assertTrue(callbacks.finish(NativePaymentProvider.STRIPE, SheetCloseReason.SUBMITTED))
        assertEquals(NativePaymentState.AwaitingServer("charge-1", NativePaymentProvider.STRIPE, SheetCloseReason.SUBMITTED), callbacks.state.value)
        assertTrue(saved.last() is NativePaymentState.AwaitingServer)
        assertEquals(PaymentLaunchResult.AWAITING_SERVER_REFRESH, callbacks.begin("charge-2", NativePaymentProvider.STRIPE, false))
        callbacks.acknowledgeServerRefresh()
        assertEquals(PaymentLaunchResult.STARTED, callbacks.begin("charge-2", NativePaymentProvider.STRIPE, false))
    }

    @Test fun wrongProviderAndDuplicateCallbacksCannotCompleteAnotherFlow() {
        val callbacks = PaymentCallbackState()
        callbacks.begin("charge-1", NativePaymentProvider.RAZORPAY, false)
        assertFalse(callbacks.finish(NativePaymentProvider.STRIPE, SheetCloseReason.SUBMITTED))
        assertTrue(callbacks.state.value is NativePaymentState.Presenting)
        assertTrue(callbacks.finish(NativePaymentProvider.RAZORPAY, SheetCloseReason.CANCELLED))
        assertFalse(callbacks.finish(NativePaymentProvider.RAZORPAY, SheetCloseReason.SUBMITTED))
        assertEquals(SheetCloseReason.CANCELLED, (callbacks.state.value as NativePaymentState.AwaitingServer).reason)
    }

    @Test fun failedAndCanceledSheetsAlsoRequireAuthoritativeRefresh() {
        for (reason in listOf(SheetCloseReason.FAILED, SheetCloseReason.CANCELLED)) {
            val callbacks = PaymentCallbackState()
            callbacks.begin("charge", NativePaymentProvider.STRIPE, false)
            assertTrue(callbacks.finish(NativePaymentProvider.STRIPE, reason, "Check the server."))
            assertEquals(reason, (callbacks.state.value as NativePaymentState.AwaitingServer).reason)
        }
    }

    @Test fun recreationRetainsOnlyReferenceAndInterruptedFlowRequiresStatusCheck() {
        var stored: NativePaymentState = NativePaymentState.Idle
        val oldActivity = PaymentCallbackState(saveState = { stored = it })
        oldActivity.begin("server-charge-id", NativePaymentProvider.RAZORPAY, false)
        val recreated = PaymentCallbackState(stored)
        recreated.acknowledgeServerRefresh() // Cannot clear a sheet while it is still running.
        assertTrue(recreated.state.value is NativePaymentState.Presenting)
        assertTrue(recreated.recoverInterrupted())
        assertEquals(SheetCloseReason.INTERRUPTED, (recreated.state.value as NativePaymentState.AwaitingServer).reason)
        assertFalse(recreated.recoverInterrupted())
    }

    @Test fun closedSheetStillRequiresRefreshAfterProcessRecreation() {
        var stored: NativePaymentState = NativePaymentState.Idle
        val original = PaymentCallbackState(saveState = { stored = it })
        original.begin("charge", NativePaymentProvider.STRIPE, false)
        original.finish(NativePaymentProvider.STRIPE, SheetCloseReason.SUBMITTED)
        val restored = PaymentCallbackState(stored)
        assertEquals(PaymentLaunchResult.AWAITING_SERVER_REFRESH, restored.begin("other-charge", NativePaymentProvider.RAZORPAY, false))
        restored.acknowledgeServerRefresh()
        assertEquals(PaymentLaunchResult.STARTED, restored.begin("other-charge", NativePaymentProvider.RAZORPAY, false))
    }

    @Test fun sdkInputValidationPreservesServerAmountAndRedactsCredentials() {
        val stripe = StripeSheetInput("1", "Workshop", "pk_test_fixture", "pi_secret_fixture", "cus_fixture", "eph_fixture")
        assertTrue(stripe.valid()); assertFalse(stripe.toString().contains("secret_fixture"))
        assertFalse(StripeSheetInput("1", "Workshop", "sk_secret", "pi_secret").valid())
        assertFalse(StripeSheetInput("1", "Workshop", "pk_test_fixture", "pi_secret", "cus_only").valid())
        val razorpay = RazorpaySheetInput("1", "Workshop", "rzp_test_fixture", "order_fixture", 12345, "INR")
        assertTrue(razorpay.valid()); assertEquals(12345L, razorpay.amountMinor)
        assertFalse(razorpay.toString().contains("order_fixture"))
        assertFalse(RazorpaySheetInput("1", "Workshop", "rzp_test_fixture", "order_fixture", 0, "INR").valid())
        assertFalse(RazorpaySheetInput("1", "Workshop", "rzp_test_fixture", "", 12345, "INR").valid())
        assertFalse(RazorpaySheetInput("1", "Workshop", "rzp_test_fixture", "order_fixture", 12345, "invalid").valid())
    }
}
