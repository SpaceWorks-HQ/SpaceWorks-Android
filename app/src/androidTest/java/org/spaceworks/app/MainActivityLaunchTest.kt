package org.spaceworks.app

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
