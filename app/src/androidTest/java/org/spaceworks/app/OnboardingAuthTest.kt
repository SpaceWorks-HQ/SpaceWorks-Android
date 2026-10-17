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
    private fun waitText(text:String){DeviceWindowGuard.awaitClear();
        try {compose.waitUntil(10000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}
        catch(f:Throwable){screenshot("ux-failure-${text.filter {it.isLetterOrDigit()}.take(40)}");throw f}
    }
    private fun screenshot(name:String){DeviceWindowGuard.awaitClear();
        val output=InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("validation")!!
        output.mkdirs();File(output,"$name.png").outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        val screen=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(output,"$name-screen.png").outputStream().use {screen.compress(Bitmap.CompressFormat.PNG,100,it)}
        screen.recycle()
    }
    private fun demo(){
        DeviceWindowGuard.awaitClear()
        compose.setContent {SpaceWorksApp(ConnectionStateHolder("",false,true,{},{}), appearance = DeviceProfile.appearance())}
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Explore demo").performScrollTo().performClick();waitText("Email or username")
    }
    @Test fun introductionCompletesWithoutPermissionPromptsAndCanReplay() {
        var saved=false;val intro=OnboardingStateHolder(false){saved=it}
        DeviceWindowGuard.awaitClear()
        compose.setContent {SpaceWorksApp(ConnectionStateHolder("",false,true,{},{}),onboarding=intro, appearance = DeviceProfile.appearance())}
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Make room.").assertExists();screenshot("ux-introduction-first")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Next").performScrollTo().performClick();waitText("Borrow with confidence.")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Next").performScrollTo().performClick();waitText("One place for your making.");screenshot("ux-introduction-third")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Get started").performScrollTo().performClick();assertTrue(saved)
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Connect to your makerspace").assertExists()
        DeviceWindowGuard.awaitClear()
        compose.runOnIdle {intro.replay()};compose.onNodeWithText("Make room.").assertExists()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Skip introduction").performScrollTo().performClick();assertTrue(saved)
    }
    @Test fun passwordRecoveryUsesUniformFailureThenReturnsToSignIn() {
        demo();compose.onNodeWithText("Forgot password?").performScrollTo().performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("accountEmail").performTextReplacement("meera@example.invalid")
        compose.onNodeWithTag("accountEmail").assertTextContains("meera@example.invalid")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("accountEmail").performImeAction();waitText("Enter your recovery code")
        screenshot("ux-recovery-request")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("accountCode").performTextInput("000000")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("accountPassword").performTextInput("new-demo-password")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("accountConfirmation").performTextInput("new-demo-password")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("accountConfirmation").performImeAction();waitText("Invalid or expired verification code.")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("accountCode").performScrollTo().performTextReplacement("123456")
        compose.onNodeWithTag("accountCode").assertTextContains("123456")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("accountConfirmation").performScrollTo().performImeAction();waitText("Password updated")
        screenshot("ux-recovery-success")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Go to sign in").performScrollTo().performClick();waitText("Welcome back.")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("username").assertTextContains("meera@example.invalid")
    }
    @Test fun signupAcknowledgementDoesNotEnterSignedInShell() {
        demo();compose.onNodeWithText("Create account").performScrollTo().performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Name").performTextReplacement("Demo New Member")
        compose.onNodeWithText("Name").assertTextContains("Demo New Member")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("accountEmail").performTextReplacement("new.member@example.invalid")
        compose.onNodeWithTag("accountEmail").assertTextContains("new.member@example.invalid")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("accountPassword").performTextReplacement("new-demo-password")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("accountConfirmation").performTextReplacement("new-demo-password")
        compose.onNodeWithText("Name").assertTextContains("Demo New Member")
        compose.onNodeWithTag("accountEmail").assertTextContains("new.member@example.invalid")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("accountConfirmation").performImeAction();waitText("Account created")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Home").assertDoesNotExist();screenshot("ux-signup-acknowledgement")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Go to sign in").performScrollTo().performClick();waitText("Welcome back.")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("username").assertTextContains("new.member@example.invalid")
    }
    @Test fun providerPreviewCanCancelOrRequireMfaWithoutOpeningAnSdk() {
        val journey=NativeJourney(compose,"ux-provider-preview")
        demo();compose.onNodeWithText("Preview sign-in options").performScrollTo().performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Preview Apple").performScrollTo().performClick();waitText("Apple sign-in preview")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Simulate unavailable").performScrollTo().performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("This sign-in option isn't available on this device.").assertExists()
        screenshot("ux-provider-preview-unavailable")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Cancel preview").performScrollTo().performClick();waitText("Welcome back.")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Preview sign-in options").performScrollTo().performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Preview Google").performScrollTo().performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Require extra security in this preview").performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Continue with sample account").performScrollTo().performClick();waitText("Check your authenticator")
        DeviceWindowGuard.awaitClear()
        journey.replace(compose.onNodeWithTag("mfaCode"),"123456")
        DeviceWindowGuard.awaitClear()
        journey.clickText("Verify");waitText("Choose your makerspace")
    }
    @Test fun phonePreviewShowsInvalidExpiredStatesAndSsoCompletes() {
        demo();compose.onNodeWithText("Preview sign-in options").performScrollTo().performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Preview Phone").performScrollTo().performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("previewPhone").performTextInput("9876543210")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("previewPhone").assertTextContains("9876543210").performImeAction();waitText("Enter your verification code")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("previewCode").performTextInput("000000")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Verify",substring=false).performScrollTo().performClick();waitText("That code didn't work. Check it and try again.")
        screenshot("ux-phone-preview-code-error")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Simulate expired code").performScrollTo().performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("This code has expired. Request another code.").assertExists()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Cancel preview").performScrollTo().performClick();waitText("Welcome back.")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Preview sign-in options").performScrollTo().performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Preview Makerspace SSO").performScrollTo().performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Continue with sample account").performScrollTo().performClick();waitText("Choose your makerspace")
    }
    @Test fun phonePreviewValidCodeCreatesOnlyTheDemoMemberSession() {
        val journey=NativeJourney(compose,"ux-phone-preview")
        demo();journey.clickText("Preview sign-in options")
        DeviceWindowGuard.awaitClear()
        journey.clickText("Preview Phone")
        DeviceWindowGuard.awaitClear()
        journey.replace(compose.onNodeWithTag("previewPhone"),"9876543210")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("previewPhone").performImeAction();waitText("Enter your verification code")
        DeviceWindowGuard.awaitClear()
        journey.replace(compose.onNodeWithTag("previewCode"),"123456")
        DeviceWindowGuard.awaitClear()
        journey.clickText("Verify");waitText("Choose your makerspace")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Demo mode").assertExists()
        screenshot("ux-phone-preview-verified")
    }
    @Test fun compactSignInAtDoubleFontPreservesPasswordFocusAndShowsFieldErrors() {
        DeviceWindowGuard.awaitClear()
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                Box(Modifier.width(320.dp).fillMaxHeight()) {SpaceWorksApp(ConnectionStateHolder("",false,true,{},{}), appearance = DeviceProfile.appearance())}
            }
        }
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithText("Explore demo").performScrollTo().performClick();waitText("Email or username")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("password").performScrollTo().performTextClearance()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("password").performTextInput("correctable-demo-password")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithContentDescription("Show password").assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp).performScrollTo().performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("password").assertIsFocused()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithContentDescription("Hide password").performClick()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("username").performScrollTo().performTextClearance()
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("submitSignIn").performScrollTo().performClick();waitText("Enter your email or username.")
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("username").assertIsFocused();screenshot("ux-signin-320dp-font2-error")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first()
            activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken,0)
        }
        DeviceWindowGuard.awaitClear()
        compose.waitUntil(5000) {
            var shown=false
            instrumentation.runOnMainSync {
                val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first()
                shown=androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true
            };!shown
        }
        DeviceWindowGuard.awaitClear()
        compose.onNodeWithTag("submitSignIn").performScrollTo().assertIsDisplayed()
        screenshot("ux-signin-320dp-font2-keyboard-hidden")
    }
}
