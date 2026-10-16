package org.spaceworks.app

import android.Manifest
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NotificationJourneyTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var bundle:org.spaceworks.app.auth.AuthBundle
    private fun waitText(text:String){DeviceWindowGuard.awaitClear();try {compose.waitUntil(12000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}catch(f:Throwable){shot("a4-failure-${text.filter(Char::isLetterOrDigit).take(30)}");throw f}}
    private fun click(text:String){DeviceWindowGuard.awaitClear();try {
        compose.waitUntil(12000){runCatching {compose.onNodeWithText(text).assertIsEnabled();true}.getOrDefault(false)}
        val node=compose.onNodeWithText(text)
        if(runCatching {node.assertIsDisplayed()}.isFailure)node.performScrollTo()
        node.assertIsDisplayed().performClick()
    }catch(f:Throwable){shot("a4-click-failure-${text.filter(Char::isLetterOrDigit).take(28)}");throw f}}
    private fun tab(text:String){DeviceWindowGuard.awaitClear();compose.onAllNodesWithText(text,useUnmergedTree=true).onLast().performClick()}
    private fun shot(name:String){DeviceWindowGuard.awaitClear();if(!name.contains("failure"))compose.waitUntil(10000){bundle.push?.state?.value?.busy!=true && bundle.links?.state?.value?.busy!=true}
        val dir=InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("validation")!!;dir.mkdirs()
        File(dir,"$name.png").outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        val screen=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(dir,"$name-screen.png").outputStream().use {screen.compress(Bitmap.CompressFormat.PNG,100,it)};screen.recycle()
    }
    private fun signedIn(){compose.setContent {SpaceWorksApp(ConnectionStateHolder("",false,true,{},{}),demoFactory={org.spaceworks.app.auth.DemoSupport.create().also {bundle=it}})};click("Explore demo");waitText("Email or username");compose.onNodeWithTag("submitSignIn").performScrollTo().performClick();waitText("Choose your makerspace");click("Fab Lab Kochi");click("Continue");waitText("Welcome back, Meera Nair.")}
    @Test fun consentThenNotificationTapSelectsFreshAuthorizedMakerspaceAndProduct(){
        signedIn();tab("Inbox");waitText("Makerspace notifications");click("Enable notifications");waitText("Notifications are on for:");shot("a4-notifications-enabled")
        click("Preview notification scenarios");click("Preview another makerspace update");waitText("Borrowing terms");waitText("Orbital sander")
        compose.waitUntil(10000){bundle.member!!.state.value.product.value?.id=="201" && bundle.member!!.state.value.inbox.value!=null && bundle.links!!.state.value.delivered==null}
        compose.runOnIdle {assertEquals(2,bundle.holder.state.value.selectedId);assertEquals(2,bundle.member!!.state.value.scope!!.id);assertNull(bundle.actions!!.state.value.route);assertNull(bundle.member!!.state.value.inbox.error)}
        shot("a4-authorized-update-product")
    }
    @Test fun unavailableMakerspaceDoesNotChangeScopeAndUnknownVersionFallsBackToInbox(){
        signedIn();tab("Inbox");waitText("Makerspace notifications");click("Preview notification scenarios");click("Preview unavailable makerspace")
        waitText("Your makerspace access couldn't be confirmed. Open Inbox or try the link again.")
        compose.runOnIdle {assertEquals(1,bundle.holder.state.value.selectedId);assertEquals(1,bundle.member!!.state.value.scope!!.id);assertNull(bundle.member!!.state.value.productId)}
        shot("a4-unavailable-maker")
        click("Preview unknown update version");compose.waitUntil(10000){bundle.links!!.state.value.delivered==null && bundle.links!!.state.value.error==null && bundle.member!!.state.value.inbox.value!=null}
        compose.onNodeWithText("Makerspace notifications").assertExists();compose.runOnIdle {assertEquals(1,bundle.holder.state.value.selectedId);assertNull(bundle.actions!!.state.value.route)}
        shot("a4-unknown-version-inbox")
    }
    @Test fun deniedPermissionServiceRetryOffAndSignoutRemainLocalInDemo(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val permission=context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        signedIn();tab("Inbox");waitText("Makerspace notifications");click("Preview notification scenarios");click("Preview denied permission")
        waitText("Notifications are blocked in system settings. Inbox is still available.")
        assertEquals(permission,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));shot("a4-permission-denied")
        click("Preview notification service unavailable");waitText("Notifications are unavailable. Try again later.");shot("a4-service-unavailable")
        click("Restore notification service");waitText("Notifications are on for:");click("Turn off notifications");waitText("Notifications are off on this device.")
        compose.runOnIdle {assertTrue(bundle.push!!.state.value.registered.isEmpty());assertFalse(bundle.push!!.state.value.consent)}
        click("Enable notifications");waitText("Notifications are on for:");tab("Profile");click("Sign out");waitText("Sign out?");DeviceWindowGuard.awaitClear();compose.onAllNodesWithText("Sign out").onLast().performClick();waitText("Welcome back.")
        compose.runOnIdle {assertNull(bundle.tokens.read());assertNull(bundle.holder.state.value.user);assertTrue(bundle.push!!.state.value.registered.isEmpty());assertNull(bundle.member!!.state.value.scope);assertNull(bundle.links!!.state.value.delivered)}
        assertEquals(permission,context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));shot("a4-signed-out")
    }
    @Test fun incomingIntentCannotSetDeploymentAndIsConsumedOnlyInMemory(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val controller=AppController(context.applicationContext as Application)
        val original=controller.connection.state.value.url
        val intent=Intent(Intent.ACTION_VIEW,Uri.parse("https://untrusted.example/makerspaces/999/inbox"))
        controller.receive(intent)
        assertEquals(original,controller.connection.state.value.url);assertNull(intent.data)
        val pending=controller.incoming.value as PendingNativeIntent.Link
        assertEquals("https://untrusted.example/makerspaces/999/inbox",pending.raw)
        controller.consumed(pending.sequence);assertNull(controller.incoming.value)
    }
}
