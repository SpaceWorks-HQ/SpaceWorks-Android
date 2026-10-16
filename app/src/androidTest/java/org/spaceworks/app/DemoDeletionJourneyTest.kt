package org.spaceworks.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.spaceworks.app.account.*
import java.util.concurrent.atomic.AtomicLong

/** Synthetic Debug component journeys; controller entry/fix routing remains an activation check. */
class DemoDeletionJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val journey by lazy{NativeJourney(compose,"a7-deletion")}
    private fun waitFor(condition: () -> Boolean) { DeviceWindowGuard.awaitClear(); compose.waitUntil(12_000, condition) }
    private fun click(text: String) {
        journey.click(compose.onNode(hasText(text) and hasClickAction()))
    }
    private fun inputDone(tag: String, value: String) {
        val field = compose.onNodeWithTag(tag)
        journey.replace(field,value)
        field.performImeAction()
    }
    @Test fun allBlockersRemainReachableAndUnknownFixPreservesItsMakerspace() {
        val holder = DemoAccountDeletion(); var fixed: DemoDeletionBlocker? = null
        compose.setContent { A7ComponentHost { DemoAccountDeletionScreen(holder, {}, { blocker, _ -> fixed = blocker }, {}) } }
        journey.systemBars(false)
        click("Review account deletion")
        waitFor { holder.state.value.blockers.size == 13 }
        val unknown = compose.onNodeWithTag("deletion-blocker-future_backend_code")
        unknown.assertExists()
        journey.click(compose.onNode(hasText("Contact makerspace support") and hasAnyAncestor(hasTestTag("deletion-blocker-future_backend_code"))))
        compose.runOnIdle { assertEquals(8, fixed?.makerspaceID); assertEquals("future_backend_code", fixed?.code) }
        journey.shot("a7-deletion-unknown-blocker-support")
    }
    @Test fun readyAccountRequiresProofAndExactTypedConfirmationBeforeDemoReset() {
        val holder = DemoAccountDeletion(); var deleted = false
        compose.setContent { A7ComponentHost { DemoAccountDeletionScreen(holder, {}, { _, _ -> }, { deleted = true }) } }
        journey.systemBars(false)
        click("Ready to delete"); click("Review account deletion")
        inputDone("deletion-proof", "DemoPass!123")
        waitFor { holder.state.value.stage == DemoDeletionStage.CONFIRM }
        journey.replace(compose.onNodeWithTag("deletion-confirmation"),"delete")
        journey.dismissKeyboard();journey.visible(compose.onNodeWithTag("deletion-submit")).assertIsNotEnabled()
        inputDone("deletion-confirmation", "DELETE")
        waitFor { deleted }
        compose.runOnIdle { assertEquals(DemoDeletionStage.FINISHED, holder.state.value.stage); assertNull(holder.state.value.proofExpiresAt) }
        journey.shot("a7-deletion-demo-complete")
    }
    @Test fun obligationAddedAtConfirmationReturnsFreshBlockersWithoutResettingAccount() {
        val holder = DemoAccountDeletion(); var deleted = false
        compose.setContent { A7ComponentHost { DemoAccountDeletionScreen(holder, {}, { _, _ -> }, { deleted = true }) } }
        journey.systemBars(false)
        click("Obligation added at confirmation"); click("Review account deletion"); click("Authenticator code")
        inputDone("deletion-proof", "123456"); waitFor { holder.state.value.stage == DemoDeletionStage.CONFIRM }
        inputDone("deletion-confirmation", "DELETE")
        waitFor { holder.state.value.conflict }
        compose.runOnIdle { assertFalse(deleted); assertNull(holder.state.value.proofExpiresAt) }
        val blocker=compose.onNodeWithTag("deletion-blocker-active_loans")
        runCatching{blocker.performScrollTo()};blocker.assertIsDisplayed()
        compose.onAllNodesWithTag("deletion-confirmation").assertCountEquals(0)
        click("Check again")
        compose.runOnIdle { assertEquals(DemoDeletionStage.BLOCKERS, holder.state.value.stage) }
        journey.shot("a7-deletion-fresh-conflict-blockers")
    }
    @Test fun idleProofExpiryRemovesConfirmationAndRequiresFreshVerification() {
        val clock = AtomicLong(1000)
        val holder = DemoAccountDeletion(clock::get).apply {
            choose(DemoDeletionScenario.READY); review(); authenticate(DemoDeletionProof.PHONE, "123456")
        }
        compose.setContent { A7ComponentHost { DemoAccountDeletionScreen(holder, {}, { _, _ -> }, {}) } }
        journey.systemBars(false)
        journey.replace(compose.onNodeWithTag("deletion-confirmation"),"DELETE")
        clock.set(301000)
        waitFor { holder.state.value.stage == DemoDeletionStage.REAUTHENTICATE }
        compose.onAllNodesWithTag("deletion-confirmation").assertCountEquals(0)
        journey.visible(compose.onNodeWithTag("deletion-proof")).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        journey.dismissKeyboard();journey.shot("a7-deletion-expired-proof-cleared")
    }
    @Test fun replacingHolderClearsEnteredSecretsAndExternalRouteDisposalClearsProof() {
        val first = DemoAccountDeletion().apply { choose(DemoDeletionScenario.READY); review() }
        val second = DemoAccountDeletion().apply { choose(DemoDeletionScenario.READY); review() }
        var current by mutableStateOf(first)
        var shown by mutableStateOf(true)
        compose.setContent { A7ComponentHost { if (shown) DemoAccountDeletionScreen(current, {}, { _, _ -> }, {}) } }
        journey.systemBars(false)
        DeviceWindowGuard.awaitClear()
        journey.replace(compose.onNodeWithTag("deletion-proof"),"private-demo-input")
        compose.runOnIdle { current = second }
        journey.visible(compose.onNodeWithTag("deletion-proof"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        inputDone("deletion-proof", "DemoPass!123")
        waitFor { second.state.value.stage == DemoDeletionStage.CONFIRM }
        compose.runOnIdle { shown = false }
        waitFor { second.state.value.proofExpiresAt == null }
        compose.runOnIdle { assertEquals(DemoDeletionStage.EXPLANATION, second.state.value.stage); shown = true }
        compose.onAllNodesWithTag("deletion-confirmation").assertCountEquals(0)
    }
}
