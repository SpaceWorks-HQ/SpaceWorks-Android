package org.spaceworks.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class MemberActionsJourneyTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var session:org.spaceworks.app.auth.AuthBundle
    private val journey by lazy {NativeJourney(compose,"a3")}
    private fun answers(project:String,safety:Boolean) {
        try {compose.runOnIdle {
            val actual=session.actions!!.state.value.answers
            assertEquals(kotlinx.serialization.json.JsonPrimitive(project),actual["project"])
            assertEquals(kotlinx.serialization.json.JsonPrimitive(safety),actual["safety"])
        }}catch(f:Throwable){shot("a3-answer-retention-failure");throw f}
    }
    private fun waitText(text:String){DeviceWindowGuard.awaitClear();try {compose.waitUntil(12000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}catch(f:Throwable){shot("a3-failure-${text.filter(Char::isLetterOrDigit).take(30)}");throw f}}
    private fun click(text:String){DeviceWindowGuard.awaitClear();dismissKeyboard();try {compose.waitUntil(12000){runCatching {compose.onNodeWithText(text).assertIsEnabled();true}.getOrDefault(false)};compose.onNodeWithText(text).reachable().performClick()}catch(f:Throwable){shot("a3-click-failure-${text.filter(Char::isLetterOrDigit).take(30)}");throw f}}
    private fun dismissKeyboard() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first()
            activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(activity.window.decorView.windowToken,0)
            activity.window.decorView.clearFocus()
        }
        compose.waitUntil(5000) {
            var shown=false
            instrumentation.runOnMainSync {
                val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first()
                shown=androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true
            };!shown
        }
        compose.waitForIdle()
    }
    private fun SemanticsNodeInteraction.reachable():SemanticsNodeInteraction {
        // A partly clipped node is "displayed", but its click centre can still be outside
        // the scroll viewport. Bring scrollable controls fully into view before a real tap;
        // dialog controls have no scroll ancestor and must already fit their window.
        runCatching {performScrollTo()}
        compose.waitForIdle()
        compose.waitUntil(5000) {
            runCatching {
                assertIsDisplayed()
                val actual=fetchSemanticsNode().boundsInRoot
                val full=getUnclippedBoundsInRoot()
                val density=compose.density.density
                actual.width+1f>=(full.right-full.left).value*density && actual.height+1f>=(full.bottom-full.top).value*density
            }.getOrDefault(false)
        }
        return assertIsDisplayed()
    }
    private fun tab(text:String){DeviceWindowGuard.awaitClear();compose.onAllNodesWithText(text,useUnmergedTree=true).onLast().performClick()}
    private fun shot(name:String){DeviceWindowGuard.awaitClear();if(::session.isInitialized && !name.contains("failure"))compose.waitUntil(10000){session.actions?.state?.value?.busy!=true};val dir=InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("validation")!!;dir.mkdirs();if(::session.isInitialized && session.demo && name.contains("failure")){val state=session.actions?.state?.value;File(dir,"$name-demo-form.txt").writeText("route=${state?.route}\nanswers=${state?.answers}\nfields=${state?.fieldErrors}\nticket=${state?.ticketId}\nerror=${state?.error}\n")};File(dir,"$name.png").outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)};val full=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());File(dir,"$name-screen.png").outputStream().use {full.compress(Bitmap.CompressFormat.PNG,100,it)};full.recycle()}
    private fun signedIn(){compose.setContent {SpaceWorksApp(ConnectionStateHolder("",false,true,{},{}),demoFactory={org.spaceworks.app.auth.DemoSupport.create().also {session=it}})};click("Explore demo");waitText("Email or username");compose.onNodeWithTag("submitSignIn").reachable().performClick();waitText("Choose your makerspace");click("Fab Lab Kochi");click("Continue");waitText("Welcome back, Meera Nair.")}
    @Test fun reviewBorrowRequestAndRenewalReturnServerConfirmedReceipts(){
        signedIn();tab("Inventory");waitText("Cordless drill");click("Cordless drill");waitText("Borrowing terms");click("Add to request");click("Review request");waitText("Review your request");shot("a3-cart-review");click("Submit request");waitText("Submit your request?");click("Confirm");waitText("Request received");shot("a3-request-receipt");click("Refresh request status");waitText("1 × Cordless drill");click("Done")
        tab("Activity");waitText("Renew loan");compose.onAllNodesWithText("Renew loan").onFirst().reachable().performClick();waitText("Renewed 0 times");click("Confirm renewal");waitText("Renew this loan?");click("Confirm");waitText("Loan renewed");shot("a3-renewal-receipt")
    }
    @Test fun bookWithRequiredFalseAnswerThenRegisterAndViewConfirmedTicket(){
        signedIn();click("Book a space");waitText("Electronics bench");click("Electronics bench");waitText("Submit booking request");click("Check availability");waitText("No overlapping bookings were returned. Final availability is checked when you book.")
        DeviceWindowGuard.awaitClear()
        journey.replace(compose.onNodeWithText("What will you work on? (required)"),"A sensor enclosure")
        click("No");compose.onNodeWithText("No").assertIsSelected();answers("A sensor enclosure",false);click("Submit booking request");waitText("Space booked");shot("a3-booking-receipt");click("Done")
        tab("Home");click("Explore events");waitText("Make a desk light");click("Make a desk light");waitText("Register for this event");click("Register for this event");waitText("Confirm registration")
        DeviceWindowGuard.awaitClear()
        journey.replace(compose.onNodeWithText("What will you work on? (required)"),"A reading light")
        click("Yes");compose.onNodeWithText("Yes").assertIsSelected();answers("A reading light",true)
        click("Member place · INR 350.00 · 10 left");answers("A reading light",true);click("Confirm registration");waitText("Registration confirmed");shot("a3-registration-receipt");click("Done");tab("Activity");waitText("View registration")
        DeviceWindowGuard.awaitClear()
        compose.onAllNodesWithText("View registration").onFirst().reachable().performClick();waitText("Show QR ticket");click("Show QR ticket");compose.waitUntil(10000){compose.onAllNodesWithContentDescription("Your event registration QR ticket").fetchSemanticsNodes().isNotEmpty()};shot("a3-event-qr")
    }
    @Test fun profileDiscardAndOptionalPreferencesRemainAccessible(){
        signedIn();tab("Profile");click("Edit profile");waitText("Display name");compose.onNodeWithText("Display name").performTextReplacement("Meera Makes");compose.onNodeWithText("Display name").assertTextContains("Meera Makes");compose.waitUntil(5000){session.actions?.profileDirty==true};compose.onNodeWithText("Save changes").assertIsEnabled();shot("a3-profile-dirty");click("Back");waitText("Discard changes?");click("Keep editing");click("Save changes");waitText("Profile saved");shot("a3-profile-saved");click("Done")
        click("Communication preferences");waitText("Always-on safety messages");shot("a3-preferences");compose.onNodeWithContentDescription("Hardware Email optional messages").reachable().performClick();waitText("These messages continue even when optional messages are off.")
    }
    @Test fun nativeTicketRendererRejectsExternalContentAndRendersGeneratedDemo(){
        val svg="""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><path d="M0 0H10V10H0z" fill="#000000" /></svg>"""
        val bitmap=org.spaceworks.app.actions.SafeTicketSvg.render(svg.toByteArray());assertEquals(720,bitmap.width);assertEquals(android.graphics.Color.BLACK,bitmap.getPixel(360,360));bitmap.recycle()
        listOf("<!DOCTYPE svg [<!ENTITY x SYSTEM 'https://example.invalid/private'>]>","<image href='https://example.invalid/photo'/>","<script>alert(1)</script>","<foreignObject/>").forEach {bad->val text=if(bad.startsWith("<!"))bad+svg else svg.replace("<path d=\"M0 0H10V10H0z\" fill=\"#000000\" />",bad);assertTrue(runCatching {org.spaceworks.app.actions.SafeTicketSvg.render(text.toByteArray())}.isFailure)}
        val demo=org.spaceworks.app.actions.SafeTicketSvg.render(org.spaceworks.app.actions.DEMO_TICKET_SVG.toByteArray());assertEquals(720,demo.height);demo.recycle()
    }
}
