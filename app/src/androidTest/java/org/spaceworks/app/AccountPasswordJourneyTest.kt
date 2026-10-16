package org.spaceworks.app

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.spaceworks.app.account.*

/** Future A7 native component proof. Synthetic credentials only; never launches a live operation. */
class AccountPasswordJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val journey by lazy{NativeJourney(compose,"a7-password")}
    private fun ready() = AccountManagementStateHolder(DemoAccountGateway()).also {
        it.activateGlobal("demo-no-membership"); runBlocking { it.open(AccountPage.PASSWORD) }
    }
    private fun field(tag: String) = compose.onNodeWithTag(tag).let { node ->
        DeviceWindowGuard.awaitClear()
        journey.visible(node)
    }
    private fun enter(tag: String, value: String)=journey.replace(compose.onNodeWithTag(tag),value)
    private fun submit()=journey.clickTag("account-password-submit")
    private fun waitNotice(copy: AccountCopy)=journey.waitText(copy.english)
    private fun empty(tag: String) { assertEquals("", field(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text) }
    @Test fun largeTextManualPasswordShowsRefusalsThenClearsEverySecretAfterExplicitPreview() {
        val holder = ready()
        compose.setContent { A7ComponentHost(largeText=true) { AccountScreens(holder, true, {}) } }
        journey.systemBars(DeviceProfile.dark)
        enter("account-password-current", "DemoPass!123"); enter("account-password-new", "replacement"); enter("account-password-confirmation", "different")
        submit(); waitNotice(AccountCopy.PASSWORD_CONFIRMATION); empty("account-password-current")
        assertEquals(0L, holder.state.value.passwordRevision)
        enter("account-password-current", "wrong"); enter("account-password-confirmation", "replacement")
        submit(); compose.waitUntil(12_000) { holder.state.value.error == "The current password is incorrect." }
        empty("account-password-current"); assertNotNull(holder.state.value.globalAccountKey)
        enter("account-password-current", "DemoPass!123"); submit(); waitNotice(AccountCopy.DEMO_PASSWORD_CHANGED)
        empty("account-password-current"); empty("account-password-new"); empty("account-password-confirmation")
        journey.dismissKeyboard();journey.shot("a7-manual-password-demo-large")
    }
    @Test fun externalRouteDisposalAndHolderReplacementDoNotRetainEnteredPasswords() {
        val first = ready(); var holder by mutableStateOf(first); var visible by mutableStateOf(true)
        compose.setContent { A7ComponentHost { if (visible) AccountScreens(holder, true, {}) else Text("Another route") } }
        journey.systemBars(DeviceProfile.dark)
        enter("account-password-current", "unsubmitted"); enter("account-password-new", "private-new"); enter("account-password-confirmation", "private-new")
        compose.runOnIdle { visible = false }; compose.waitForIdle(); compose.runOnIdle { visible = true }
        empty("account-password-current"); empty("account-password-new"); empty("account-password-confirmation")
        enter("account-password-current", "another-unsubmitted"); enter("account-password-new", "another-new")
        val second = ready(); compose.runOnIdle { holder = second }
        empty("account-password-current"); empty("account-password-new"); empty("account-password-confirmation")
        assertEquals(0L, first.state.value.passwordRevision); assertEquals(0L, second.state.value.passwordRevision)
    }
}
