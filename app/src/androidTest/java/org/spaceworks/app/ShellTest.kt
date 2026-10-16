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
    private fun screenshot(name:String) {
        val dir=InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("validation")!!
        dir.mkdirs();File(dir,"$name.png").outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun waitText(text:String) {compose.waitUntil(10000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}
    private fun enterDemo() {
        compose.setContent {SpaceWorksApp(ConnectionStateHolder("",false,true,{},{}))}
        compose.onNodeWithText("Explore demo").performScrollTo().performClick()
        waitText("Email or username")
    }
    @Test fun invalidConnectionRemainsAccessibleAtLargeFontScale() {
        val holder=ConnectionStateHolder("",false,false,{}, {})
        compose.setContent { val density=LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {SpaceWorksApp(holder)} }
        compose.onNodeWithText("Dark mode").assertIsDisplayed().performClick()
        compose.onNodeWithText("Light mode").assertIsDisplayed()
        compose.onNodeWithTag("serverUrl").performTextInput("http://example.org")
        compose.onNodeWithText("Connect",useUnmergedTree=true).performScrollTo().performClick()
        waitText("Enter a valid HTTPS server URL.")
        compose.waitUntil(5000) {
            compose.onNodeWithTag("connectionError").performScrollTo()
            try {compose.onNodeWithTag("connectionError").assertIsDisplayed();true}catch(_:AssertionError){false}
        }
        screenshot("a1-server-error-dark-font2")
        compose.onNodeWithText("Sign in").assertDoesNotExist()
    }
    @Test fun demoSignInSelectMakerspaceNavigateAndSignOut() {
        enterDemo();screenshot("a1-sign-in")
        compose.onNodeWithTag("submitSignIn").performScrollTo().performClick()
        waitText("Choose your makerspace");screenshot("a1-makerspace-picker")
        compose.onNodeWithText("Fab Lab Kochi").performClick()
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        waitText("Welcome, Meera Nair");screenshot("a1-home")
        compose.onNodeWithText("Profile",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Sign out").performScrollTo().performClick()
        compose.onAllNodesWithText("Sign out").onLast().performClick()
        waitText("Email or username")
        compose.onNodeWithText("Signed out.").assertExists()
        compose.onNodeWithText("Exit demo").performClick()
        compose.onNodeWithText("Connect to your makerspace").assertExists()
    }
    @Test fun demoMfaEnrollmentRequiresRecoveryAcknowledgement() {
        enterDemo()
        compose.onNodeWithText("Demo scenarios").performScrollTo().performClick()
        compose.onNodeWithText("Enrollment").performScrollTo().performClick()
        compose.onNodeWithTag("submitSignIn").performScrollTo().performClick()
        waitText("Show setup code")
        compose.onNodeWithText("Show setup code").performScrollTo().performClick()
        waitText("Authentication code")
        screenshot("a1-mfa-enrollment")
        compose.onNodeWithTag("mfaCode").performScrollTo().performTextInput("123456")
        compose.onNodeWithText("Verify and continue",useUnmergedTree=true).performScrollTo().performClick()
        waitText("Save your recovery codes");screenshot("a1-recovery")
        compose.onNodeWithText("Choose your makerspace").assertDoesNotExist()
        compose.onNodeWithContentDescription("I've saved my recovery codes").performScrollTo().performClick()
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        waitText("Choose your makerspace")
    }
}
