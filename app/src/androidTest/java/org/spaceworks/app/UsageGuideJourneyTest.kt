package org.spaceworks.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.spaceworks.app.guidance.*
import org.spaceworks.app.theme.LightScheme
import org.spaceworks.app.theme.DarkScheme

/** Authored native component journeys; no camera, permission request, network or secret screenshots. */
class UsageGuideJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val journey by lazy { NativeJourney(compose, "a8-usage") }
    private val initialScope = GuideScope("https://demo.invalid/", "fixture-member", 7)

    @Composable private fun Host(largeText: Boolean = false, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 3.2f else density.fontScale)) {
            MaterialTheme(colorScheme = if (DeviceProfile.dark) DarkScheme else LightScheme, shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp))) {
                Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() } }
            }
        }
    }

    @Test fun startHereActionsDismissAndMakerRemountStayScopedAndDemoNeverWritesLivePreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("usage-guidance", 0)
        val before = preferences.all.toMap()
        var scope by mutableStateOf(initialScope)
        lateinit var progress: GuideProgress
        var inventory = 0; var bookings = 0; var profiles = 0
        var bookingsAvailable by mutableStateOf(true)
        compose.setContent { Host {
            progress = rememberScopedGuidance(context, scope, demo = true)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                HomeStartHere(progress, { inventory++ }, if (bookingsAvailable) ({ bookings++ }) else null, { profiles++ })
            }
        } }
        journey.systemBars(DeviceProfile.dark)
        journey.clickTag("usage-browse-inventory"); journey.clickTag("usage-view-bookings")
        assertEquals(1, inventory); assertEquals(1, bookings)
        journey.clickTag("usage-dismiss-tip"); compose.onNodeWithTag("usage-start-here").assertDoesNotExist()
        compose.runOnIdle { scope = initialScope.copy(makerspaceID = 8); bookingsAvailable = false }
        journey.visible(compose.onNodeWithTag("usage-start-here"))
        compose.onNodeWithTag("usage-view-bookings").assertDoesNotExist()
        journey.clickTag("usage-view-profile"); assertEquals(1, profiles)
        compose.runOnIdle { scope = initialScope }
        compose.onNodeWithTag("usage-start-here").assertDoesNotExist()
        compose.runOnIdle { progress.resetTips() }
        journey.visible(compose.onNodeWithTag("usage-start-here"))
        assertEquals(before, preferences.all.toMap())
        journey.shot("a8-usage-start-here-reset")
    }

    @Test fun guideRemainsReadableAndActionsReachableAtLargestTextWithoutStartingAnotherFlow() {
        var replayed = 0; var reset = 0; var back = 0
        compose.setContent { Host(largeText = true) {
            UsageGuideScreen(demo = true, staffAvailable = false, onBack = { back++ },
                onReplayIntro = { replayed++ }, onResetTips = { reset++ })
        } }
        journey.systemBars(DeviceProfile.dark)
        journey.visible(compose.onNodeWithTag("usage-guide-heading"))
        compose.onNodeWithText("Staff: review and hand over").assertDoesNotExist()
        val inventory = compose.onNodeWithTag("usage-section-inventory")
        inventory.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
        compose.onNodeWithTag("usage-section-inventory-body-0").assertDoesNotExist()
        journey.click(inventory)
        inventory.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse))
        journey.visible(compose.onNodeWithTag("usage-section-inventory-body-0"))
            .assertTextEquals("Search Inventory or choose a category.")
        journey.visible(compose.onNodeWithTag("usage-section-inventory-body-5"))
            .assertTextEquals("Use its renewal action when available.")
        journey.shot("a8-usage-inventory-large-text")
        journey.click(inventory)
        inventory.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
        compose.onNodeWithTag("usage-section-inventory-body-0").assertDoesNotExist()
        compose.onNodeWithTag("usage-section-inventory-body-5").assertDoesNotExist()
        journey.clickTag("usage-replay-intro"); assertEquals(1, replayed)
        journey.clickTag("usage-reset-tips"); assertEquals(1, reset)
        journey.visible(compose.onNodeWithTag("usage-reset-notice"))
        journey.shot("a8-usage-guide-large-text")
        journey.clickTag("usage-guide-back"); assertEquals(1, back)
    }

    @Test fun authorizedStaffInstructionsAreExplicitAndGlobalGuideHidesMakerTipReset() {
        compose.setContent { Host {
            UsageGuideScreen(demo = true, staffAvailable = true, onBack = {}, onReplayIntro = {}, onResetTips = null)
        } }
        journey.systemBars(DeviceProfile.dark)
        journey.visible(compose.onNodeWithText("Staff: review and hand over"))
        val scan = compose.onNodeWithTag("usage-section-staff-scan")
        scan.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
        compose.onNodeWithTag("usage-section-staff-scan-body-0").assertDoesNotExist()
        journey.click(scan)
        scan.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse))
        journey.visible(compose.onNodeWithTag("usage-section-staff-scan-body-0"))
            .assertTextContains("Scanning alone never confirms an action.", substring = true)
        compose.onNodeWithTag("usage-reset-tips").assertDoesNotExist()
        journey.visible(compose.onNodeWithText("Choose permissions when you need them"))
        journey.shot("a8-usage-staff-permissions")
    }
}
