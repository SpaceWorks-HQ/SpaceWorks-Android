package org.spaceworks.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
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
    private var documentContextScale:Float?=null
    private val documentTextProofs=org.json.JSONArray()
    @OptIn(ExperimentalTestApi::class)
    private fun start(dark:Boolean=DeviceProfile.dark,largeText:Boolean=false,configurationBackedDocument:Boolean=false):PaymentsStateHolder {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val holder=PaymentsStateHolder(DemoPaymentsGateway(),CachedPaymentFiles(File(context.cacheDir,"native-documents")),pause={}).apply{activate(MemberScope(7,"demo-makerspace"))}
        compose.setContent {
            @Composable fun paymentContent() {
                org.spaceworks.app.theme.SpaceWorksTheme(dark){
                    Surface(Modifier.fillMaxSize()){Box(Modifier.fillMaxSize().safeDrawingPadding()){
                        PaymentsScreen(holder,true,{},launchCheckout={launches.incrementAndGet();PaymentLaunchResult.DEMO_BLOCKED})
                    }}
                }
            }
            val density=LocalDensity.current
            if(configurationBackedDocument) {
                // Dialog creates a new native owner from LocalView.context; a density-only
                // override on the parent composition cannot configure that window.
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(3.2f)) {
                    AndroidView(modifier=Modifier.fillMaxSize(),factory={scopedContext ->
                        documentContextScale=scopedContext.resources.configuration.fontScale
                        ComposeView(scopedContext).apply {
                            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                            setContent {paymentContent()}
                        }
                    },onRelease={it.disposeComposition()})
                }
            } else {
            CompositionLocalProvider(LocalDensity provides Density(density.density,if(largeText)3.2f else density.fontScale)){
                paymentContent()
            }
            }
        }
        journey.systemBars(dark);waitText("Sample equipment deposit");return holder
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
            }, appearance = DeviceProfile.appearance())
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

    private fun documentAction(label:String) {
        val action=journey.visible(compose.onNodeWithText(label)).assertIsEnabled()
        assertEquals("Native bridge context must carry the configuration-backed scale",3.2f,requireNotNull(documentContextScale),.001f)
        val textNode=compose.onNodeWithText(label,useUnmergedTree=true)
        val ownerScale=textNode.fetchSemanticsNode().layoutInfo.density.fontScale
        assertEquals("The actual dialog text owner must retain that scale",3.2f,ownerScale,.001f)
        val layouts=mutableListOf<TextLayoutResult>()
        textNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
        val layout=layouts.single();val input=layout.layoutInput
        assertEquals("Actual dialog text layout must use the overridden configuration",3.2f,input.density.fontScale,.001f)
        assertTrue(input.style.fontSize.isSp && input.style.fontSize.value>0)
        val unscaledPx=input.style.fontSize.value*input.density.density
        val scaledPx=with(input.density){input.style.fontSize.toPx()}
        val characterHeight=layout.getBoundingBox(0).height
        val lineHeight=layout.getLineBottom(0)-layout.getLineTop(0)
        assertTrue("Actual dialog character boxes and line metrics must be enlarged",scaledPx>unscaledPx*1.5f && characterHeight>unscaledPx*1.5f && lineHeight>unscaledPx*1.5f)
        val lineEnds=(0 until layout.lineCount).map{layout.getLineEnd(it,visibleEnd=true)}
        documentTextProofs.put(org.json.JSONObject().put("action",label).put("context_font_scale",documentContextScale)
            .put("owner_font_scale",ownerScale).put("dialog_font_scale",input.density.fontScale)
            .put("font_size_sp",input.style.fontSize.value).put("unscaled_font_pixels",unscaledPx)
            .put("scaled_font_pixels",scaledPx).put("character_box_height_pixels",characterHeight).put("line_height_pixels",lineHeight)
            .put("line_end_offsets",org.json.JSONArray(lineEnds)))
        val output=InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("validation")!!
        output.mkdirs();File(output,"a5-native-pdf-action-scale.json").writeText(documentTextProofs.toString(2),Charsets.UTF_8)
        lineEnds.dropLast(1).forEach{end->assertTrue("$label must wrap between complete words",end>=label.length || label[end].isWhitespace() || (end>0 && label[end-1].isWhitespace()))}
        val viewport=action.onAncestors().filter(hasScrollAction()).onFirst().fetchSemanticsNode().boundsInRoot
        val actual=action.fetchSemanticsNode().boundsInRoot
        assertTrue("Full $label action must fit the actual document scroll viewport",actual.width>0 && actual.height>0 &&
            actual.left>=viewport.left && actual.right<=viewport.right && actual.top>=viewport.top && actual.bottom<=viewport.bottom)
    }
    private fun demoAction(label:String) {
        val action=journey.visible(compose.onNodeWithText(label)).assertIsEnabled()
        val viewport=compose.onNodeWithTag("demo-payment-outcome-scroll").fetchSemanticsNode().boundsInRoot
        val actual=action.fetchSemanticsNode().boundsInRoot
        assertTrue("Full $label action must fit the actual demo scroll viewport",viewport.width>0 && viewport.height>0 &&
            actual.width>0 && actual.height>0 && actual.left>=viewport.left && actual.right<=viewport.right &&
            actual.top>=viewport.top && actual.bottom<=viewport.bottom)
    }
    @Test fun serverConfirmedPreviewCheckoutOpensNativeReceiptWithoutAnySDKCharge(){
        val holder=start(largeText=true,configurationBackedDocument=true);openDeposit();shot("a5-payment-review");click("Continue to payment");waitText("Preview checkout")
        listOf("Server confirms payment","Checkout cancelled","Checkout failed","Confirmation delayed","Offline","Payment unavailable","Keep reviewing").forEach(::demoAction)
        demoAction("Server confirms payment");shot("a5-demo-checkout-confirm-action");click("Server confirms payment");waitText("Paid online");shot("a5-server-confirmed-payment")
        click("Receipts and credit notes");waitText("Receipt DEMO-RECEIPT-001");journey.click(compose.onNodeWithContentDescription("Open PDF DEMO-RECEIPT-001"));waitText("Save PDF")
        compose.waitUntil(10000){compose.onAllNodesWithContentDescription("DEMO-RECEIPT-001, page 1 of 1").fetchSemanticsNodes().isNotEmpty()}
        val file=holder.state.value.preview!!.file;assertTrue(file.exists())
        val page=compose.onNodeWithContentDescription("DEMO-RECEIPT-001, page 1 of 1")
        page.performScrollTo();compose.waitForIdle();page.assertIsDisplayed()
        val pageBounds=page.fetchSemanticsNode().boundsInRoot
        val fullPage=page.getUnclippedBoundsInRoot();val pixelsPerDp=compose.density.density
        val pageViewport=page.onAncestors().filter(hasScrollAction()).onFirst().fetchSemanticsNode().boundsInRoot
        val visiblePage=pageBounds.intersect(pageViewport)
        assertTrue("A meaningful rendered PDF region must be visible inside its document viewport",visiblePage.width>0 &&
            fullPage.left.value*pixelsPerDp>=pageViewport.left && fullPage.right.value*pixelsPerDp<=pageViewport.right &&
            visiblePage.height>=minOf(96f*compose.density.density,pageViewport.height/2))
        shot("a5-native-pdf-receipt")
        documentAction("Save PDF");shot("a5-native-pdf-save-action")
        documentAction("Share document");shot("a5-native-pdf-share-action")
        documentAction("Done");shot("a5-native-pdf-done-action");click("Done");compose.waitForIdle();assertNull(holder.state.value.preview);assertFalse(file.exists());assertEquals(0,launches.get())
    }
    @Test fun delayedConfirmationStaysPendingUntilALaterServerRefresh(){
        val holder=start();openDeposit();click("Continue to payment");click("Confirmation delayed");waitText("Your payment is awaiting server confirmation. Check its status again shortly.")
        assertEquals("pending",holder.record("501")!!.status);compose.onAllNodesWithText("Paid online").assertCountEquals(0);shot("a5-payment-awaiting-confirmation")
        click("Simulate later server confirmation");waitText("Paid online");assertEquals("paid_online",holder.record("501")!!.status);assertEquals(0,launches.get())
    }
    @Test fun cancelledAndFailedCheckoutAreReadableAtLargestTextInDarkMode(){
        val holder=start(dark=true,largeText=true);openDeposit();click("Continue to payment");click("Checkout cancelled");waitText("Checkout closed. Check the current payment status before trying again.")
        assertEquals("pending",holder.record("501")!!.status);click("Continue to payment");click("Checkout failed");waitText("Checkout could not finish. Check the payment status and try again.")
        assertEquals("pending",holder.record("501")!!.status);assertFalse(holder.state.value.busy);journey.visible(compose.onNodeWithText("Check payment status")).assertIsEnabled();shot("a5-payment-failed-dark-large-text");assertEquals(0,launches.get())
    }
}
