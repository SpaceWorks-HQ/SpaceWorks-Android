package org.spaceworks.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.spaceworks.app.auth.AuthBundle
import org.spaceworks.app.auth.DemoSupport
import org.spaceworks.app.payments.CachedPaymentFiles
import java.io.File

class FullAppGuideJourneyTest {
    @get:Rule val compose=createComposeRule()
    private val journey by lazy {NativeJourney(compose,"a8-app-guide")}
    private fun profile()=journey.click(compose.onAllNodesWithText("Profile",useUnmergedTree=true).onLast())
    private fun home()=journey.click(compose.onAllNodesWithText("Home",useUnmergedTree=true).onLast())

    @Test fun profileGuideReplaysIntroductionPreservesTipsAndResetRestoresActualHomeActions() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val preferences=context.getSharedPreferences("usage-guidance",0)
        val before=preferences.all.toMap()
        lateinit var bundle:AuthBundle
        val intro=OnboardingStateHolder(true){}
        compose.setContent {SpaceWorksApp(ConnectionStateHolder("",false,true,{},{}),demoFactory={
            DemoSupport.create(context=context,paymentFiles=CachedPaymentFiles(File(context.cacheDir,"native-documents"))).also {bundle=it}
        },onboarding=intro, appearance = DeviceProfile.appearance())}
        journey.clickText("Explore demo");journey.waitText("Email or username");journey.clickTag("submitSignIn")
        journey.waitText("Choose your makerspace");journey.clickText("Fab Lab Kochi");journey.clickText("Continue")
        journey.waitText("Welcome back, Meera Nair.")
        compose.waitUntil(10000){bundle.actions?.state?.value?.capabilities?.value?.bookableSpaces==true}
        journey.clickTag("usage-view-bookings")
        journey.waitText("Bookings")
        compose.waitUntil(10000){bundle.member?.state?.value?.overview?.loading==false}
        // This is the typed booking destination, not Activity's first unrelated section.
        compose.onNodeWithText("Requests").assertDoesNotExist()
        journey.shot("a8-app-guide-typed-bookings")
        journey.clickText("Back");journey.waitText("Welcome back, Meera Nair.")
        journey.clickTag("usage-dismiss-tip");compose.onNodeWithTag("usage-start-here").assertDoesNotExist()
        profile();journey.clickTag("usage-guide-entry");journey.waitText("App guide")
        journey.shot("a8-app-guide-profile-entry")
        journey.clickTag("usage-replay-intro");journey.waitText("Make room.")
        journey.clickText("Skip introduction");journey.waitText("Welcome back, Meera Nair.")
        compose.onNodeWithTag("usage-start-here").assertDoesNotExist()
        profile();journey.clickTag("usage-guide-entry");journey.clickTag("usage-reset-tips")
        journey.visible(compose.onNodeWithTag("usage-reset-notice"));journey.clickTag("usage-guide-back")
        home();journey.visible(compose.onNodeWithText("Start here"))
        journey.visible(compose.onNodeWithTag("usage-browse-inventory"))
        assertEquals(before,preferences.all.toMap())
        journey.shot("a8-app-guide-reset-home")
    }
}
