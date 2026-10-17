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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
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
            org.spaceworks.app.theme.SpaceWorksTheme(DeviceProfile.dark) {
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

    private fun assertWordBoundaries(node: SemanticsNodeInteraction) {
        val text=node.fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        val layouts=mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(layouts)}
        val layout=layouts.single()
        for(line in 0 until layout.lineCount-1) {
            val end=layout.getLineEnd(line,visibleEnd=true)
            assertTrue("Guide text must wrap between complete words: $text",end>=text.length || text[end].isWhitespace() || (end>0 && text[end-1].isWhitespace()))
        }
    }
    @Test fun guideRemainsReadableAndActionsReachableAtLargestTextWithoutStartingAnotherFlow() {
        var replayed = 0; var reset = 0; var back = 0
        var reducedMotion by mutableStateOf(false)
        compose.setContent { Host(largeText = true) { CompositionLocalProvider(org.spaceworks.app.platform.LocalReducedMotion provides reducedMotion) {
            UsageGuideScreen(demo = true, staffAvailable = false, onBack = { back++ },
                onReplayIntro = { replayed++ }, onResetTips = { reset++ })
        } } }
        journey.systemBars(DeviceProfile.dark)
        journey.visible(compose.onNodeWithTag("usage-guide-heading"))
        compose.onNodeWithText("Staff handovers").assertDoesNotExist()
        val inventory = compose.onNodeWithTag("usage-section-inventory")
        inventory.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
        compose.onNodeWithTag("usage-section-inventory-body-0").assertDoesNotExist()
        journey.click(inventory)
        inventory.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse))
        journey.visible(compose.onNodeWithTag("usage-section-inventory-body-0"))
            .assertTextEquals("Search Inventory or choose a category.")
        journey.visible(compose.onNodeWithTag("usage-section-inventory-body-5"))
            .assertTextEquals("Use its renewal action when available.")
        assertWordBoundaries(compose.onNodeWithTag("usage-section-inventory-body-5"))
        journey.shot("a8-usage-inventory-large-text")
        journey.click(inventory)
        inventory.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
        compose.onNodeWithTag("usage-section-inventory-body-0").assertDoesNotExist()
        compose.onNodeWithTag("usage-section-inventory-body-5").assertDoesNotExist()
        // Reduced motion keeps one immediate semantic body and the same native controls.
        compose.runOnIdle { reducedMotion = true }
        journey.click(inventory)
        compose.onAllNodesWithTag("usage-section-inventory-body-0").assertCountEquals(1)
        journey.click(inventory)
        compose.onNodeWithTag("usage-section-inventory-body-0").assertDoesNotExist()
        journey.clickTag("usage-replay-intro"); assertEquals(1, replayed)
        journey.clickTag("usage-reset-tips"); assertEquals(1, reset)
        journey.visible(compose.onNodeWithTag("usage-reset-notice"))
            .assertTextEquals("Tips reset for this account and makerspace.")
        assertWordBoundaries(compose.onNodeWithTag("usage-reset-notice"))
        journey.shot("a8-usage-guide-large-text")
        journey.clickTag("usage-guide-back"); assertEquals(1, back)
    }

    @Test fun authorizedStaffInstructionsAreExplicitAndGlobalGuideHidesMakerTipReset() {
        compose.setContent { Host {
            UsageGuideScreen(demo = true, staffAvailable = true, onBack = {}, onReplayIntro = {}, onResetTips = null)
        } }
        journey.systemBars(DeviceProfile.dark)
        journey.visible(compose.onNodeWithText("Staff handovers"))
        val scan = compose.onNodeWithTag("usage-section-staff-scan")
        scan.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
        compose.onNodeWithTag("usage-section-staff-scan-body-0").assertDoesNotExist()
        journey.click(scan)
        scan.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse))
        journey.visible(compose.onNodeWithTag("usage-section-staff-scan-body-0"))
            .assertTextContains("Scanning alone never confirms an action.", substring = true)
        compose.onNodeWithTag("usage-reset-tips").assertDoesNotExist()
        journey.visible(compose.onNodeWithText("Permissions"))
        journey.shot("a8-usage-staff-permissions")
    }
}
