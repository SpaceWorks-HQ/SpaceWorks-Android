package org.spaceworks.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

class ShellTest {
    @get:Rule val compose=createComposeRule()
    private fun screenshot(name:String) {DeviceWindowGuard.awaitClear();
        val dir=InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("validation")!!
        dir.mkdirs();File(dir,"$name.png").outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun waitText(text:String) {DeviceWindowGuard.awaitClear();compose.waitUntil(10000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}
    private fun enterDemo() {
        DeviceWindowGuard.awaitClear()
        compose.setContent {SpaceWorksApp(ConnectionStateHolder("",false,true,{},{}))}
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Explore demo").performScrollTo().performClick()
        waitText("Email or username")
    }
    @Test fun invalidConnectionRemainsAccessibleAtLargeFontScale() {
        val holder=ConnectionStateHolder("",false,false,{}, {})
        DeviceWindowGuard.awaitClear()
        compose.setContent { val density=LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {SpaceWorksApp(holder)} }
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Dark mode").assertIsDisplayed().performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Light mode").assertIsDisplayed()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("serverUrl").performTextInput("http://example.org")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Connect",useUnmergedTree=true).performScrollTo().performClick()
        waitText("Enter a valid HTTPS server URL.")
        DeviceWindowGuard.awaitClear()
        compose.waitUntil(5000) {
            DeviceWindowGuard.awaitClear()
            compose.onNodeWithTag("connectionError").performScrollTo()
            try {compose.onNodeWithTag("connectionError").assertIsDisplayed();true}catch(_:AssertionError){false}
        }
        screenshot("a1-server-error-dark-font2")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Sign in").assertDoesNotExist()
    }
    @Test fun demoSignInSelectMakerspaceNavigateAndSignOut() {
        enterDemo();screenshot("a1-sign-in")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("submitSignIn").performScrollTo().performClick()
        waitText("Choose your makerspace");screenshot("a1-makerspace-picker")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Fab Lab Kochi").performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        waitText("Welcome back, Meera Nair.");screenshot("a1-home")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Profile",useUnmergedTree=true).performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Sign out").performScrollTo().performClick()
        DeviceWindowGuard.awaitClear()
        compose.onAllNodesWithText("Sign out").onLast().performClick()
        waitText("Email or username")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Signed out.").assertExists()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Exit demo").performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Connect to your makerspace").assertExists()
    }
    @Test fun demoMfaEnrollmentRequiresRecoveryAcknowledgement() {
        enterDemo()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Demo scenarios").performScrollTo().performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Enrollment").performScrollTo().performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("submitSignIn").performScrollTo().performClick()
        waitText("Show setup code")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Show setup code").performScrollTo().performClick()
        waitText("Authentication code")
        screenshot("a1-mfa-enrollment")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("mfaCode").performScrollTo().performTextInput("123456")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Verify and continue",useUnmergedTree=true).performScrollTo().performClick()
        waitText("Save your recovery codes");screenshot("a1-recovery")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Choose your makerspace").assertDoesNotExist()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithContentDescription("I've saved my recovery codes").performScrollTo().performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        waitText("Choose your makerspace")
    }
}
