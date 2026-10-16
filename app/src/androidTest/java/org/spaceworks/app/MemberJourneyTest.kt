package org.spaceworks.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

class MemberJourneyTest {
    @get:Rule val compose=createComposeRule()
    private fun waitText(text:String){try {compose.waitUntil(10000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}catch(f:Throwable){screenshot("a2-failure-${text.filter {it.isLetterOrDigit()}.take(30)}");throw f}}
    private fun screenshot(name:String) {
        val dir=InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("validation")!!;dir.mkdirs()
        File(dir,"$name.png").outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        val screen=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(dir,"$name-screen.png").outputStream().use {screen.compress(Bitmap.CompressFormat.PNG,100,it)};screen.recycle()
    }
    private fun signedIn(){
        compose.setContent {SpaceWorksApp(ConnectionStateHolder("",false,true,{},{}))}
        compose.onNodeWithText("Explore demo").performScrollTo().performClick();waitText("Email or username")
        compose.onNodeWithTag("submitSignIn").performScrollTo().performClick();waitText("Choose your makerspace")
        compose.onNodeWithText("Fab Lab Kochi").performClick();compose.onNodeWithText("Continue").performScrollTo().performClick()
        waitText("Welcome back, Meera Nair.")
    }
    private fun tab(name:String){compose.onAllNodesWithText(name,useUnmergedTree=true).onLast().performClick()}
    private fun scenario(name:String){
        tab("Profile");compose.onNodeWithText("Choose a demo state").performScrollTo().performClick()
        compose.onNodeWithText(name).performClick()
    }
    @Test fun memberHomeCardInventoryDetailAndSearchAreUsable() {
        signedIn();compose.onNodeWithText("Membership card").performScrollTo().performClick();waitText("Active card")
        compose.waitUntil(5000){compose.onAllNodesWithContentDescription("Membership QR code").fetchSemanticsNodes().isNotEmpty()}
        screenshot("a2-membership-card")
        compose.onNodeWithText("Back to Home").performScrollTo().performClick()
        tab("Inventory");waitText("Cordless drill")
        compose.onNodeWithText("Cordless drill").performScrollTo().performClick();waitText("Borrowing terms")
        screenshot("a2-inventory-detail")
        compose.onNodeWithText("Back to inventory").performScrollTo().performClick()
        compose.onNodeWithTag("inventorySearch").performScrollTo().performTextReplacement("unlikely item")
        compose.onNodeWithTag("inventorySearch").performImeAction();waitText("No results. Try another search.")
        screenshot("a2-inventory-filtered-empty")
        compose.onNodeWithTag("inventorySearch").performTextReplacement("drill")
        compose.onNodeWithTag("inventorySearch").performImeAction();waitText("Cordless drill")
    }
    @Test fun inboxReadActionsRefreshTheUnreadList() {
        signedIn();tab("Inbox");waitText("Loan due soon")
        compose.onAllNodesWithText("Unread",substring=false).onFirst().performClick();waitText("Loan due soon")
        compose.onNodeWithText("Loan due soon").performScrollTo().performClick();waitText("Mark as read")
        screenshot("a2-inbox-message")
        compose.onNodeWithText("Mark as read").performScrollTo().performClick();waitText("You're registered")
        compose.onNodeWithText("Loan due soon").assertDoesNotExist()
        compose.onNodeWithText("Mark all as read").performScrollTo().performClick();waitText("Nothing here yet.")
        screenshot("a2-inbox-unread-empty")
    }
    @Test fun revokedCardUnknownDuesAndMakerspaceSwitchNeverShowPriorData() {
        signedIn();scenario("Revoked card");tab("Home")
        compose.onNodeWithText("Membership card").performScrollTo().performClick();waitText("This card is not active. Ask your makerspace for help.")
        compose.onNodeWithContentDescription("Membership QR code").assertDoesNotExist();screenshot("a2-revoked-card")
        scenario("Dues unavailable");tab("Home");waitText("Dues are unavailable. Try again later.")
        compose.onNodeWithText("Dues are unavailable. Try again later.").performScrollTo();screenshot("a2-unknown-dues")
        scenario("Populated");tab("Profile")
        compose.onNodeWithText("Switch to Maker Space Bengaluru").performScrollTo().performClick()
        tab("Inventory");waitText("Orbital sander")
        compose.onNodeWithText("Cordless drill").assertDoesNotExist();screenshot("a2-switched-makerspace")
        scenario("Empty");tab("Inventory");waitText("Nothing here yet.");screenshot("a2-inventory-empty")
        scenario("Offline");tab("Home");waitText("Couldn't connect. Try again.")
        compose.onNodeWithText("Couldn't connect. Try again.").performScrollTo();screenshot("a2-offline-retry")
    }
}
