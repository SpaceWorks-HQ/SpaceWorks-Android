package org.spaceworks.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class OnboardingAuthTest {
    @get:Rule val compose=createComposeRule()
    private fun waitText(text:String){compose.waitUntil(10000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}
    private fun screenshot(name:String){
        val output=InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("validation")!!
        output.mkdirs();File(output,"$name.png").outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun demo(){
        compose.setContent {SpaceWorksApp(ConnectionStateHolder("",false,true,{},{}))}
        compose.onNodeWithText("Explore demo").performScrollTo().performClick();waitText("Email or username")
    }
    @Test fun introductionCompletesWithoutPermissionPromptsAndCanReplay() {
        var saved=false;val intro=OnboardingStateHolder(false){saved=it}
        compose.setContent {SpaceWorksApp(ConnectionStateHolder("",false,true,{},{}),onboarding=intro)}
        compose.onNodeWithText("Make room.").assertExists();screenshot("ux-introduction-first")
        compose.onNodeWithText("Next").performScrollTo().performClick();waitText("Borrow with confidence.")
        compose.onNodeWithText("Next").performScrollTo().performClick();waitText("One place for your making.");screenshot("ux-introduction-third")
        compose.onNodeWithText("Get started").performScrollTo().performClick();assertTrue(saved)
        compose.onNodeWithText("Connect to your makerspace").assertExists()
        compose.runOnIdle {intro.replay()};compose.onNodeWithText("Make room.").assertExists()
        compose.onNodeWithText("Skip introduction").performScrollTo().performClick();assertTrue(saved)
    }
    @Test fun passwordRecoveryUsesUniformFailureThenReturnsToSignIn() {
        demo();compose.onNodeWithText("Forgot password?").performScrollTo().performClick()
        compose.onNodeWithTag("accountEmail").performTextInput("meera@example.invalid")
        compose.onNodeWithText("Request recovery code").performScrollTo().performClick();waitText("Enter your recovery code")
        screenshot("ux-recovery-request")
        compose.onNodeWithTag("accountCode").performTextInput("000000")
        compose.onNodeWithTag("accountPassword").performTextInput("new-demo-password")
        compose.onNodeWithTag("accountConfirmation").performTextInput("new-demo-password")
        compose.onNodeWithText("Update password").performScrollTo().performClick();waitText("Invalid or expired verification code.")
        compose.onNodeWithTag("accountCode").performScrollTo().performTextInput("123456")
        compose.onNodeWithText("Update password").performScrollTo().performClick();waitText("Password updated")
        screenshot("ux-recovery-success")
        compose.onNodeWithText("Go to sign in").performScrollTo().performClick();waitText("Welcome back.")
        compose.onNodeWithTag("username").assertTextContains("meera@example.invalid")
    }
    @Test fun signupAcknowledgementDoesNotEnterSignedInShell() {
        demo();compose.onNodeWithText("Create account").performScrollTo().performClick()
        compose.onNodeWithText("Name").performTextInput("Demo New Member")
        compose.onNodeWithTag("accountEmail").performTextInput("new.member@example.invalid")
        compose.onNodeWithTag("accountPassword").performTextInput("new-demo-password")
        compose.onNodeWithTag("accountConfirmation").performTextInput("new-demo-password")
        compose.onNodeWithText("Create account",useUnmergedTree=true).performScrollTo().performClick();waitText("Account created")
        compose.onNodeWithText("Home").assertDoesNotExist();screenshot("ux-signup-acknowledgement")
        compose.onNodeWithText("Go to sign in").performScrollTo().performClick();waitText("Welcome back.")
        compose.onNodeWithTag("username").assertTextContains("new.member@example.invalid")
    }
    @Test fun providerPreviewCanCancelOrRequireMfaWithoutOpeningAnSdk() {
        demo();compose.onNodeWithText("Preview sign-in options").performScrollTo().performClick()
        compose.onNodeWithText("Preview Apple").performScrollTo().performClick();waitText("Apple sign-in preview")
        compose.onNodeWithText("Simulate unavailable").performScrollTo().performClick()
        compose.onNodeWithText("This sign-in option isn't available on this device.").assertExists()
        screenshot("ux-provider-preview-unavailable")
        compose.onNodeWithText("Cancel preview").performScrollTo().performClick();waitText("Welcome back.")
        compose.onNodeWithText("Preview sign-in options").performScrollTo().performClick()
        compose.onNodeWithText("Preview Google").performScrollTo().performClick()
        compose.onNodeWithText("Require extra security in this preview").performClick()
        compose.onNodeWithText("Continue with sample account").performScrollTo().performClick();waitText("Check your authenticator")
        compose.onNodeWithTag("mfaCode").performTextInput("123456")
        compose.onNodeWithText("Verify").performScrollTo().performClick();waitText("Choose your makerspace")
    }
    @Test fun phonePreviewShowsInvalidExpiredAndVerifiedStates() {
        demo();compose.onNodeWithText("Preview sign-in options").performScrollTo().performClick()
        compose.onNodeWithText("Preview Phone").performScrollTo().performClick()
        compose.onNodeWithTag("previewPhone").performTextInput("9876543210")
        compose.onNodeWithText("Send code").performScrollTo().performClick();waitText("Enter your verification code")
        compose.onNodeWithTag("previewCode").performTextInput("000000")
        compose.onNodeWithText("Verify",substring=false).performScrollTo().performClick();waitText("That code didn't work. Check it and try again.")
        screenshot("ux-phone-preview-code-error")
        compose.onNodeWithText("Simulate expired code").performScrollTo().performClick()
        compose.onNodeWithText("This code has expired. Request another code.").assertExists()
        compose.onNodeWithText("Cancel preview").performScrollTo().performClick();waitText("Welcome back.")
        compose.onNodeWithText("Preview sign-in options").performScrollTo().performClick()
        compose.onNodeWithText("Preview Makerspace SSO").performScrollTo().performClick()
        compose.onNodeWithText("Continue with sample account").performScrollTo().performClick();waitText("Choose your makerspace")
    }
    @Test fun compactSignInAtDoubleFontPreservesPasswordFocusAndShowsFieldErrors() {
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                Box(Modifier.width(320.dp).fillMaxHeight()) {SpaceWorksApp(ConnectionStateHolder("",false,true,{},{}))}
            }
        }
        compose.onNodeWithText("Explore demo").performScrollTo().performClick();waitText("Email or username")
        compose.onNodeWithTag("password").performScrollTo().performTextClearance()
        compose.onNodeWithTag("password").performTextInput("correctable-demo-password")
        compose.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        compose.onNodeWithTag("password").assertIsFocused()
        compose.onNodeWithContentDescription("Hide password").performClick()
        compose.onNodeWithTag("username").performScrollTo().performTextClearance()
        compose.onNodeWithTag("submitSignIn").performScrollTo().performClick();waitText("Enter your email or username.")
        compose.onNodeWithTag("username").assertIsFocused();screenshot("ux-signin-320dp-font2-error")
    }
}
