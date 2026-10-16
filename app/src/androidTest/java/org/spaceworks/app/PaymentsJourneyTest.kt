package org.spaceworks.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.payments.*
import org.spaceworks.app.platform.PaymentLaunchResult
import org.spaceworks.app.theme.*

class PaymentsJourneyTest {
    @get:Rule val compose=createComposeRule()
    private val journey by lazy {NativeJourney(compose,"a5")}
    private val launches=AtomicInteger()
    private fun start(dark:Boolean=false,largeText:Boolean=false):PaymentsStateHolder {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val holder=PaymentsStateHolder(DemoPaymentsGateway(),CachedPaymentFiles(File(context.cacheDir,"native-documents")),pause={}).apply{activate(MemberScope(7,"demo-makerspace"))}
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,if(largeText)3.2f else density.fontScale)){
                MaterialTheme(colorScheme=if(dark)DarkScheme else LightScheme,shapes=Shapes(small=RoundedCornerShape(10.dp),medium=RoundedCornerShape(16.dp))){
                    Surface(Modifier.fillMaxSize()){PaymentsScreen(holder,true,{},launchStripe={launches.incrementAndGet();PaymentLaunchResult.DEMO_BLOCKED})}
                }
            }
        }
        waitText("Sample equipment deposit");return holder
    }
    private fun waitText(text:String)=journey.waitText(text)
    private fun click(text:String)=journey.clickText(text)
    private fun openDeposit(){journey.click(compose.onNodeWithContentDescription("Review payment Sample equipment deposit"));waitText("Continue to payment")}
    private fun shot(name:String)=journey.shot(name)

    @Test fun signInToProfilePaymentsAndMakerSwitchKeepDemoCheckoutIsolated(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var session:org.spaceworks.app.auth.AuthBundle
        val binding=org.spaceworks.app.platform.NativeCheckoutBindings(
            kotlinx.coroutines.flow.MutableStateFlow(org.spaceworks.app.platform.NativePaymentState.Idle),
            {launches.incrementAndGet();PaymentLaunchResult.DEMO_BLOCKED},{},{})
        compose.setContent {CompositionLocalProvider(org.spaceworks.app.platform.LocalNativeCheckout provides binding) {
            SpaceWorksApp(ConnectionStateHolder("",false,true,{},{}),demoFactory={
                org.spaceworks.app.auth.DemoSupport.create(paymentFiles=CachedPaymentFiles(File(context.cacheDir,"native-documents"))).also {session=it}
            })
        }}
        click("Explore demo");waitText("Email or username");journey.clickTag("submitSignIn")
        waitText("Choose your makerspace");click("Fab Lab Kochi");click("Continue");waitText("Welcome back, Meera Nair.")
        journey.click(compose.onAllNodesWithText("Profile",useUnmergedTree=true).onLast());journey.clickTag("payments-entry")
        waitText("Sample equipment deposit");openDeposit();click("Continue to payment");click("Server confirms payment");waitText("Paid online")
        assertEquals("paid_online",session.payments!!.record("501")!!.status);shot("a5-app-profile-payment-confirmed")
        click("Back");click("Back");waitText("Meera Nair");click("Switch to Maker Space Bengaluru");waitText("Welcome back, Meera Nair.")
        journey.click(compose.onAllNodesWithText("Profile",useUnmergedTree=true).onLast());journey.clickTag("payments-entry");waitText("Sample equipment deposit")
        assertEquals(2,session.payments!!.state.value.scope!!.id);assertEquals("pending",session.payments!!.record("501")!!.status)
        assertNull(session.payments!!.state.value.selectedId);assertEquals(0,launches.get());shot("a5-app-maker-payment-isolation")
    }

    @Test fun serverConfirmedPreviewCheckoutOpensNativeReceiptWithoutAnySDKCharge(){
        val holder=start();openDeposit();shot("a5-payment-review");click("Continue to payment");waitText("Preview checkout");click("Server confirms payment");waitText("Paid online");shot("a5-server-confirmed-payment")
        click("Receipts and credit notes");waitText("Receipt DEMO-RECEIPT-001");journey.click(compose.onNodeWithContentDescription("Open PDF DEMO-RECEIPT-001"));waitText("Save PDF")
        compose.waitUntil(10000){compose.onAllNodesWithContentDescription("DEMO-RECEIPT-001, page 1 of 1").fetchSemanticsNodes().isNotEmpty()}
        val file=holder.state.value.preview!!.file;assertTrue(file.exists());shot("a5-native-pdf-receipt");click("Done");compose.waitForIdle();assertNull(holder.state.value.preview);assertFalse(file.exists());assertEquals(0,launches.get())
    }
    @Test fun delayedConfirmationStaysPendingUntilALaterServerRefresh(){
        val holder=start();openDeposit();click("Continue to payment");click("Confirmation delayed");waitText("Your payment is awaiting server confirmation. Check its status again shortly.")
        assertEquals("pending",holder.record("501")!!.status);compose.onAllNodesWithText("Paid online").assertCountEquals(0);shot("a5-payment-awaiting-confirmation")
        click("Simulate later server confirmation");waitText("Paid online");assertEquals("paid_online",holder.record("501")!!.status);assertEquals(0,launches.get())
    }
    @Test fun cancelledAndFailedCheckoutAreReadableAtLargestTextInDarkMode(){
        val holder=start(dark=true,largeText=true);openDeposit();click("Continue to payment");click("Checkout cancelled");waitText("Checkout closed. Check the current payment status before trying again.")
        assertEquals("pending",holder.record("501")!!.status);click("Continue to payment");click("Checkout failed");waitText("Checkout could not finish. Check the payment status and try again.")
        assertEquals("pending",holder.record("501")!!.status);assertFalse(holder.state.value.busy);shot("a5-payment-failed-dark-large-text");assertEquals(0,launches.get())
    }
}
