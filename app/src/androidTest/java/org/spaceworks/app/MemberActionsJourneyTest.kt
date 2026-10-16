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
    private fun waitText(text:String){DeviceWindowGuard.awaitClear();try {compose.waitUntil(12000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}catch(f:Throwable){shot("a3-failure-${text.filter(Char::isLetterOrDigit).take(30)}");throw f}}
    private fun click(text:String){DeviceWindowGuard.awaitClear();compose.waitUntil(12000){runCatching {compose.onNodeWithText(text).assertIsEnabled();true}.getOrDefault(false)};compose.onNodeWithText(text).reachable().performClick()}
    private fun SemanticsNodeInteraction.reachable():SemanticsNodeInteraction {if(runCatching {assertIsDisplayed()}.isFailure)performScrollTo();return assertIsDisplayed()}
    private fun tab(text:String){DeviceWindowGuard.awaitClear();compose.onAllNodesWithText(text,useUnmergedTree=true).onLast().performClick()}
    private fun shot(name:String){DeviceWindowGuard.awaitClear();val dir=InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("validation")!!;dir.mkdirs();File(dir,"$name.png").outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)};val full=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());File(dir,"$name-screen.png").outputStream().use {full.compress(Bitmap.CompressFormat.PNG,100,it)};full.recycle()}
    private fun signedIn(){compose.setContent {SpaceWorksApp(ConnectionStateHolder("",false,true,{},{}))};click("Explore demo");waitText("Email or username");compose.onNodeWithTag("submitSignIn").reachable().performClick();waitText("Choose your makerspace");click("Fab Lab Kochi");click("Continue");waitText("Welcome back, Meera Nair.")}
    @Test fun reviewBorrowRequestAndRenewalReturnServerConfirmedReceipts(){
        signedIn();tab("Inventory");waitText("Cordless drill");click("Cordless drill");waitText("Borrowing terms");click("Add to request");click("Review request");waitText("Review your request");shot("a3-cart-review");click("Submit request");waitText("Submit your request?");click("Confirm");waitText("Request received");shot("a3-request-receipt");click("Refresh request status");waitText("1 × Cordless drill");click("Done")
        tab("Activity");waitText("Renew loan");compose.onAllNodesWithText("Renew loan").onFirst().reachable().performClick();waitText("Renewed 0 times");click("Confirm renewal");waitText("Renew this loan?");click("Confirm");waitText("Loan renewed");shot("a3-renewal-receipt")
    }
    @Test fun bookWithRequiredFalseAnswerThenRegisterAndViewConfirmedTicket(){
        signedIn();click("Book a space");waitText("Electronics bench");click("Electronics bench");waitText("Submit booking request");click("Check availability");waitText("No overlapping bookings were returned. Final availability is checked when you book.")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("What will you work on? (required)").reachable().performTextReplacement("A sensor enclosure")
        click("No");click("Submit booking request");waitText("Space booked");shot("a3-booking-receipt");click("Done")
        tab("Home");click("Explore events");waitText("Make a desk light");click("Make a desk light");waitText("Register for this event");click("Register for this event");waitText("Confirm registration")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("What will you work on? (required)").reachable().performTextReplacement("A reading light")
        click("Yes");click("Member place · INR 350.00 · 10 left");click("Confirm registration");waitText("Registration confirmed");shot("a3-registration-receipt");click("Done");tab("Activity");waitText("View registration")
        DeviceWindowGuard.awaitClear()
        compose.onAllNodesWithText("View registration").onFirst().reachable().performClick();waitText("Show QR ticket");click("Show QR ticket");compose.waitUntil(10000){compose.onAllNodesWithContentDescription("Your event registration QR ticket").fetchSemanticsNodes().isNotEmpty()};shot("a3-event-qr")
    }
    @Test fun profileDiscardAndOptionalPreferencesRemainAccessible(){
        signedIn();tab("Profile");click("Edit profile");waitText("Display name");compose.onNodeWithText("Display name").performTextReplacement("Meera Makes");click("Back");waitText("Discard changes?");click("Keep editing");click("Save changes");waitText("Profile saved");shot("a3-profile-saved");click("Done")
        click("Communication preferences");waitText("Always-on safety messages");shot("a3-preferences");compose.onNodeWithContentDescription("Hardware Email optional messages").reachable().performClick();waitText("These messages continue even when optional messages are off.")
    }
    @Test fun nativeTicketRendererRejectsExternalContentAndRendersGeneratedDemo(){
        val svg="""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><path d="M0 0H10V10H0z" fill="#000000" /></svg>"""
        val bitmap=org.spaceworks.app.actions.SafeTicketSvg.render(svg.toByteArray());assertEquals(720,bitmap.width);assertEquals(android.graphics.Color.BLACK,bitmap.getPixel(360,360));bitmap.recycle()
        listOf("<!DOCTYPE svg [<!ENTITY x SYSTEM 'https://example.invalid/private'>]>","<image href='https://example.invalid/photo'/>","<script>alert(1)</script>","<foreignObject/>").forEach {bad->val text=if(bad.startsWith("<!"))bad+svg else svg.replace("<path d=\"M0 0H10V10H0z\" fill=\"#000000\" />",bad);assertTrue(runCatching {org.spaceworks.app.actions.SafeTicketSvg.render(text.toByteArray())}.isFailure)}
        val demo=org.spaceworks.app.actions.SafeTicketSvg.render(org.spaceworks.app.actions.DEMO_TICKET_SVG.toByteArray());assertEquals(720,demo.height);demo.recycle()
    }
}
