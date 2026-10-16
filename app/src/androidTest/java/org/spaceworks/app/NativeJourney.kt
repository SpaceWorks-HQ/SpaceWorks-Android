package org.spaceworks.app

import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File

/** Real pointer/IME interactions with bounded visible targets and full OS failure evidence. */
class NativeJourney(private val compose:ComposeContentTestRule,private val phase:String) {
    fun waitText(text:String) {DeviceWindowGuard.awaitClear();try {
        compose.waitUntil(12000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
    }catch(f:Throwable){shot("$phase-failure-${text.filter(Char::isLetterOrDigit).take(36)}");throw f}}
    fun dismissKeyboard() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first().let {
            it.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.window.decorView.windowToken,0)
            it.window.decorView.clearFocus()
        }}
        compose.waitUntil(5000) {var shown=false;instrumentation.runOnMainSync {
            val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            shown=ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true
        };!shown};compose.waitForIdle()
    }
    fun visible(node:SemanticsNodeInteraction):SemanticsNodeInteraction {
        runCatching {node.performScrollTo()};compose.waitForIdle()
        compose.waitUntil(5000) {runCatching {
            node.assertIsDisplayed();val actual=node.fetchSemanticsNode().boundsInRoot;val full=node.getUnclippedBoundsInRoot();val density=compose.density.density
            actual.width+1f>=(full.right-full.left).value*density && actual.height+1f>=(full.bottom-full.top).value*density
        }.getOrDefault(false)}
        return node.assertIsDisplayed()
    }
    fun click(node:SemanticsNodeInteraction) {DeviceWindowGuard.awaitClear();dismissKeyboard();try {
        visible(node).assertIsEnabled().performClick()
    }catch(f:Throwable){shot("$phase-click-failure");throw f}}
    fun clickText(text:String)=click(compose.onNodeWithText(text))
    fun clickTag(tag:String)=click(compose.onNodeWithTag(tag))
    fun replace(node:SemanticsNodeInteraction,value:String) {
        DeviceWindowGuard.awaitClear();visible(node).performClick();node.assertIsFocused()
        node.performTextReplacement(value);node.assertTextContains(value)
    }
    fun systemBars(dark:Boolean) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            androidx.core.view.WindowCompat.getInsetsController(activity.window,activity.window.decorView).apply {
                isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
            }
        }
    }
    fun shot(name:String) {DeviceWindowGuard.awaitClear();compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation();val output=instrumentation.targetContext.getExternalFilesDir("validation")!!;output.mkdirs()
        File(output,"$name.png").outputStream().use {compose.onAllNodes(isRoot(),useUnmergedTree=true).onLast().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        val full=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {File(output,"$name-screen.png").outputStream().use {full.compress(Bitmap.CompressFormat.PNG,100,it)}}finally {full.recycle()}
    }
}
