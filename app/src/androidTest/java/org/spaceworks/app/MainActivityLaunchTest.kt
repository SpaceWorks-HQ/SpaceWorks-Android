package org.spaceworks.app

import android.graphics.Point
import android.graphics.Rect
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercise the real manifest activity/controller and SDK registration, without launching a provider. */
class MainActivityLaunchTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val journey by lazy {NativeJourney(compose,"a8-startup")}

    private fun imeVisible(scenario: ActivityScenario<MainActivity>): Boolean {
        var shown=false
        scenario.onActivity { shown=ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true }
        return shown
    }

    @Suppress("DEPRECATION")
    private fun assertPinnedChrome(scenario: ActivityScenario<MainActivity>, keyboardShown: Boolean, name: String) {
        compose.waitForIdle()
        val evidence=JSONObject().put("ime_expected",keyboardShown)
        var statusBottom=0; var keyboardTop=0; var screenWidth=0
        var observedIme=false;var softInputMode=0
        scenario.onActivity { activity ->
            val decor=activity.window.decorView
            val insets=requireNotNull(ViewCompat.getRootWindowInsets(decor))
            observedIme=insets.isVisible(WindowInsetsCompat.Type.ime())
            softInputMode=activity.window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
            val size=Point();activity.windowManager.defaultDisplay.getRealSize(size)
            screenWidth=size.x;statusBottom=insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top
            keyboardTop=size.y-insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val location=IntArray(2);decor.getLocationOnScreen(location)
            evidence.put("screen_width",size.x).put("screen_height",size.y).put("status_bottom",statusBottom)
                .put("keyboard_top",keyboardTop).put("decor_screen_y",location[1])
                .put("ime_observed",observedIme).put("soft_input_adjustment",softInputMode)
        }
        // Accessibility screen coordinates include decor panning; local Compose bounds alone do not.
        val root=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        val controls=listOf("Demo mode",if(DeviceProfile.dark)"Light mode"else"Dark mode").associateWith {label ->
            val node=root?.findAccessibilityNodeInfosByText(label)
                ?.firstOrNull {it.text?.toString()==label && it.isVisibleToUser}
            node?.let {Rect().also(it::getBoundsInScreen)}.also {evidence.put(label,it?.toShortString()?:"missing")}
        }
        // Preserve real screen evidence before an assertion, including a panned or missing control.
        val output=requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("validation"))
        output.mkdirs();File(output,"$name.json").writeText(evidence.toString(2))
        journey.shot(name)
        assertEquals(keyboardShown,observedIme)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,softInputMode)
        controls.forEach {(label,observedBounds) ->
            assertNotNull("Pinned app control is not exposed on screen: $label",observedBounds)
            val bounds=requireNotNull(observedBounds)
            assertTrue("Pinned app control must stay below status bar: $label $bounds",bounds.top>=statusBottom)
            assertTrue("Pinned app control must stay above keyboard: $label $bounds",bounds.bottom<=keyboardTop)
            assertTrue("Pinned app control must stay inside screen: $label $bounds",bounds.left>=0 && bounds.right<=screenWidth)
        }
    }

    @Test fun actualEntryPointRegistersAdaptersAndReachesBrandedSetupWithoutPrivateState() {
        DeviceWindowGuard.awaitClear()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val preferences=context.getSharedPreferences("deployment",0)
        val keys=setOf("intro.complete","theme.dark","server")
        val previous=preferences.all.filterKeys {it in keys}
        // Change only this test application's three startup preferences, then restore them.
        assertTrue(preferences.edit().putBoolean("intro.complete",false)
            .putBoolean("theme.dark",DeviceProfile.dark).putString("server","").commit())
        try {
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                journey.waitText("Make room.")
                compose.onNodeWithText(if(DeviceProfile.dark)"Light mode"else"Dark mode").assertIsDisplayed()
                compose.onNodeWithText("Demo mode").assertDoesNotExist()
                scenario.onActivity {assertFalse(it.isFinishing)}
                if(BuildConfig.SERVER_URL.isBlank()) {
                    journey.clickText("Skip introduction")
                    journey.waitText("Connect to your makerspace")
                    journey.visible(compose.onNodeWithTag("serverUrl")).assert(
                        SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
                    compose.onNodeWithTag("submitSignIn").assertDoesNotExist()
                    journey.shot("a8-main-activity-sdk-startup")
                    journey.clickText("Explore demo");journey.waitText("Email or username")
                    assertPinnedChrome(scenario,false,"a8-main-activity-before-keyboard")
                    journey.replace(compose.onNodeWithTag("username"),"layout-fixture")
                    compose.waitUntil(5000) {imeVisible(scenario)}
                    assertPinnedChrome(scenario,true,"a8-main-activity-keyboard-insets")
                    journey.dismissKeyboard()
                    assertPinnedChrome(scenario,false,"a8-main-activity-keyboard-dismissed")
                    journey.clickText("Exit demo");journey.waitText("Connect to your makerspace")
                }
                // A fixed-server white label stays in the intro, preventing automatic health I/O.
                journey.shot("a8-main-activity-sdk-startup")
            }
        } finally {
            val editor=preferences.edit()
            keys.forEach(editor::remove)
            previous.forEach {(key,value)->when(value) {
                is Boolean->editor.putBoolean(key,value)
                is String->editor.putString(key,value)
                is Int->editor.putInt(key,value)
                is Long->editor.putLong(key,value)
                is Float->editor.putFloat(key,value)
                is Set<*>->editor.putStringSet(key,value.filterIsInstance<String>().toSet())
            }}
            assertTrue(editor.commit())
        }
    }
}
