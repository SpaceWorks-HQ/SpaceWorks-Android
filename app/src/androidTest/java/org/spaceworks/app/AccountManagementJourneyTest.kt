package org.spaceworks.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.spaceworks.app.account.*
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.theme.LightScheme

/** Component tests use the app's brand, real OS safe area and configured font scale. */
@Composable internal fun A7ComponentHost(largeText:Boolean=false,content:@Composable ()->Unit) {
    val density=LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density,if(largeText)3.2f else density.fontScale)) {
        MaterialTheme(colorScheme=LightScheme,shapes=Shapes(small=RoundedCornerShape(10.dp),medium=RoundedCornerShape(16.dp))) {
            Surface(Modifier.fillMaxSize()){Box(Modifier.fillMaxSize().safeDrawingPadding()){content()}}
        }
    }
}

/** Synthetic Debug demo only. Every capture follows the full-OS window guard. */
class AccountManagementJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val journey by lazy{NativeJourney(compose,"a7-account")}
    @Test fun appNoMembershipPickerStillOffersEmailAndGlobalAccountAccess() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { SpaceWorksApp(ConnectionStateHolder("",false,true,{},{}),demoFactory={
            org.spaceworks.app.auth.DemoSupport.create(context=context,
                paymentFiles=org.spaceworks.app.payments.CachedPaymentFiles(File(context.cacheDir,"native-documents")))
        }) }
        journey.systemBars(false);click("Explore demo");waitText("Email or username")
        journey.replace(compose.onNodeWithTag("username"),"empty.unverified.demo")
        tag("submitSignIn");waitText("No makerspace access yet")
        compose.onNodeWithText("Confirm your email").assertExists()
        tag("account-entry");waitText("Account and security")
        for(page in listOf("sessions","membership","exports"))compose.onAllNodesWithTag("account-$page").assertCountEquals(0)
        tag("account-devices");waitText("Revoke device access")
        shot("a7-app-global-devices-without-membership")
    }
    @Test fun appDeletionPreviewFixUsesAuthorizedMakerThenSuccessClearsOnlyDemo() {
        account();tag("account-deletion-preview");waitText("Account deletion")
        click("Review account deletion")
        journey.click(compose.onNode(hasText("Open Payments") and hasAnyAncestor(hasTestTag("deletion-blocker-unpaid_charges"))))
        waitText("Payments");shot("a7-app-deletion-fix-payments")
        click("Back");waitText("Welcome back, Meera Nair.")
        journey.click(compose.onAllNodesWithText("Profile",useUnmergedTree=true).onLast())
        tag("account-entry");tag("account-deletion-preview")
        click("Ready to delete");click("Review account deletion")
        journey.replace(compose.onNodeWithTag("deletion-proof"),"DemoPass!123")
        compose.onNodeWithTag("deletion-proof").performImeAction()
        waitText("Type DELETE")
        journey.replace(compose.onNodeWithTag("deletion-confirmation"),"DELETE")
        compose.onNodeWithTag("deletion-confirmation").performImeAction()
        waitText("Connect to your Space Works server")
        compose.onAllNodesWithText("Demo mode").assertCountEquals(0)
        shot("a7-app-deletion-demo-reset")
    }
    @Test fun accountWithNoMembershipCanReachDevicesAndSecurityWhileTenantPagesStayHidden() {
        val holder = AccountManagementStateHolder(DemoAccountGateway()).also { it.activateGlobal("demo-account-no-membership") }
        compose.setContent { A7ComponentHost { AccountScreens(holder, true, {}) } }
        journey.systemBars(false)
        DeviceWindowGuard.awaitClear()
        for (page in listOf("sessions", "membership", "exports")) compose.onAllNodesWithTag("account-$page").assertCountEquals(0)
        tag("account-devices")
        compose.waitUntil(12_000) { holder.state.value.grants.isNotEmpty() }
        compose.runOnIdle { org.junit.Assert.assertNull(holder.state.value.scope) }
        shot("a7-global-devices-no-membership")
        click("Back"); tag("account-security"); waitText("Current password")
        DeviceWindowGuard.awaitClear(); compose.onNodeWithTag("account-current-password").assertExists()
    }
    private fun waitText(text: String)=journey.waitText(text)
    private fun click(text: String) {
        journey.click(compose.onNode(hasText(text) and hasClickAction()))
    }
    private fun tag(name: String)=journey.clickTag(name)
    private fun shot(name: String)=journey.shot(name)
    private fun account() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { SpaceWorksApp(ConnectionStateHolder("", false, true, {}, {}),demoFactory={
            org.spaceworks.app.auth.DemoSupport.create(context=context,
                paymentFiles=org.spaceworks.app.payments.CachedPaymentFiles(File(context.cacheDir,"native-documents")))
        }) }
        journey.systemBars(false)
        click("Explore demo"); waitText("Email or username"); tag("submitSignIn")
        waitText("Choose your makerspace"); click("Fab Lab Kochi"); click("Continue"); waitText("Welcome back, Meera Nair.")
        journey.click(compose.onAllNodesWithText("Profile", useUnmergedTree = true).onLast())
        tag("account-entry"); waitText("Account and security")
    }
    @Test fun phoneVerificationAndCurrentSessionRevocationReturnToSignIn() {
        account(); tag("account-phone"); waitText("Phone number")
        journey.replace(compose.onNodeWithTag("account-phone-number"),"9876543210")
        click("Send verification code"); waitText("Six-digit verification code")
        journey.replace(compose.onNodeWithTag("account-phone-code"),"000000")
        click("Verify phone number"); waitText("Invalid or expired verification code.")
        journey.replace(compose.onNodeWithTag("account-phone-code"),"123456")
        click("Verify phone number"); waitText("Phone number verified."); shot("a7-phone-verified")
        click("Back"); tag("account-sessions"); waitText("This session"); tag("account-session-demo-current-session")
        click("Confirm"); waitText("Email or username")
    }
    @Test fun recoveryReplacementRequiresExplicitSavedAcknowledgementOutsideSignedInShell() {
        account(); tag("account-security"); waitText("Current password")
        journey.replace(compose.onNodeWithTag("account-current-password"),"sample")
        click("Regenerate recovery codes")
        DeviceWindowGuard.awaitClear();journey.dismissKeyboard()
        // No capture-capable helper or OS diagnostic screenshot while one-time codes can be visible.
        // NativeJourney.visible still checks complete physical bounds; interactions remain real clicks.
        journey.visible(compose.onNode(hasText("Confirm") and hasClickAction())).assertIsEnabled().performClick()
        compose.waitUntil(12_000){compose.onAllNodesWithText("Save your recovery codes").fetchSemanticsNodes().isNotEmpty()}
        journey.visible(compose.onNodeWithTag("account-security-continue")).assertIsNotEnabled()
        journey.dismissKeyboard()
        journey.visible(compose.onNodeWithTag("account-recovery-saved")).assertIsEnabled().performClick()
        journey.visible(compose.onNodeWithTag("account-security-continue")).assertIsEnabled().performClick()
        compose.waitUntil(12_000){compose.onAllNodesWithText("Email or username").fetchSemanticsNodes().isNotEmpty()&&
            compose.onAllNodesWithText("Save your recovery codes").fetchSemanticsNodes().isEmpty()}
        compose.waitForIdle()
        DeviceWindowGuard.awaitClear()
    }
    @Test fun personalExportNeedsFreshStatusBeforeNativeArchivePresentation() {
        account(); tag("account-exports"); click("Request data export"); waitText("Preparing your archive")
        DeviceWindowGuard.awaitClear(); compose.onAllNodesWithText("Download archive").assertCountEquals(0)
        click("Check status"); waitText("Archive ready"); click("Download archive")
        waitText("Space Works data export"); waitText("ZIP archive · save it to a private location to inspect its contents.")
        shot("a7-demo-export-archive"); click("Done"); waitText("Your data")
    }
    @Test fun idleAvailableArchiveChangesToExpiredWithoutAnotherServerRequestOrTap() {
        val clock = AtomicLong(System.currentTimeMillis())
        val holder = AccountManagementStateHolder(DemoAccountGateway(), now = clock::get).also { it.activate(MemberScope(7, "workshop")) }
        runBlocking { holder.open(AccountPage.EXPORTS); holder.requestExport(); holder.checkExport(holder.state.value.exports.single()) }
        val row = holder.state.value.exports.single()
        compose.setContent { A7ComponentHost { AccountScreens(holder, true, {}, now = clock::get) } }
        journey.systemBars(false)
        waitText("Download archive")
        clock.set(OffsetDateTime.parse(row.expiresAt).toInstant().toEpochMilli() + 1)
        waitText("This archive has expired. Request a new export.")
        DeviceWindowGuard.awaitClear(); compose.onAllNodesWithText("Download archive").assertCountEquals(0)
    }
}
