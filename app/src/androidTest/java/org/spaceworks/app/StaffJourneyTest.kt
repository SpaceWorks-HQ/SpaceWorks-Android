package org.spaceworks.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.staff.*
import org.spaceworks.app.theme.*
import java.io.File

/** Full UI and full OS screenshot evidence; Debug fixtures never use camera/storage/network SDKs. */
class StaffJourneyTest {
    @get:Rule val compose=createComposeRule()
    private val journey by lazy{NativeJourney(compose,"a6")}
    private fun start(dark:Boolean=DeviceProfile.dark,large:Boolean=false,scenario:StaffDemoScenario=StaffDemoScenario.NORMAL):StaffStateHolder{
        val api=DemoStaffGateway(InstrumentationRegistry.getInstrumentation().targetContext).apply{this.scenario=scenario}
        val holder=StaffStateHolder(api,api.authority,api.uploads).apply{activate(MemberScope(7,"demo-makerspace"))}
        compose.setContent{val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,if(large)3.2f else density.fontScale)){
                org.spaceworks.app.theme.SpaceWorksTheme(dark){Surface(Modifier.fillMaxSize()){Box(Modifier.fillMaxSize().safeDrawingPadding()){StaffScreen(holder,true,{},previewScope=MemberScope(7,"demo-makerspace"))}}}
            }}
        journey.systemBars(dark);waitText("Request #601");return holder
    }
    private fun waitText(text:String)=journey.waitText(text)
    private fun click(text:String)=journey.clickText(text)
    private fun tag(tag:String)=journey.clickTag(tag)
    private fun replace(tag:String,text:String)=journey.replace(compose.onNodeWithTag(tag),text)
    private fun shot(name:String)=journey.shot(name)
    private fun photoAndConfirm(){tag("staff-demo-photo");waitText("Upload evidence photo");tag("staff-upload-evidence");waitText("Evidence photo uploaded");tag("staff-handover-confirm");waitText("Confirm");tag("staff-confirm-action")}
    private fun preparedIssue(){tag("staff-queue-accepted");waitText("Request #602");tag("staff-request-602");waitText("Prepare issue");tag("staff-prepare-issue")
        tag("staff-box-code-demo");waitText("Verified: Sample kit box");tag("staff-handover-next");tag("staff-borrower-confirmed");tag("staff-handover-next");tag("staff-handover-next")}
    @Test fun staffPreviewOpensFromProfileAndRequiresExplicitRequestConfirmation(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var session:org.spaceworks.app.auth.AuthBundle
        compose.setContent{SpaceWorksApp(ConnectionStateHolder("",false,true,{},{}),demoFactory={org.spaceworks.app.auth.DemoSupport.create(context=context).also{session=it}}, appearance = DeviceProfile.appearance())}
        click("Explore demo");waitText("Email or username");click("Demo scenarios");click("Staff");tag("submitSignIn")
        waitText("Choose your makerspace");click("Fab Lab Kochi");click("Continue");waitText("Welcome back, Arjun Rao.")
        journey.click(compose.onAllNodesWithText("Profile",useUnmergedTree=true).onLast());tag("staff-entry");waitText("Request #601")
        assertTrue(session.staffAvailable());tag("staff-request-601");replace("staff-accept-quantity-61","1");tag("staff-accept");waitText("Accept this request?")
        session.links!!.offer("/makerspaces/2/inbox");compose.waitForIdle()
        assertEquals(1,session.holder.state.value.selectedId);assertEquals(2,session.links!!.state.value.pending!!.makerspaceId)
        assertTrue(session.staff!!.protectsNavigation);assertEquals("pending",session.staff!!.state.value.selectedRequest!!.status);shot("a6-app-staff-request-confirmation")
        tag("staff-confirm-action");waitText("Request accepted.");assertEquals("accepted",session.staff!!.state.value.selectedRequest!!.status)
        shot("a6-app-staff-request-accepted")
    }
    @Test fun ordinaryMemberHasNoStaffEntryOrAuthority(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        lateinit var session:org.spaceworks.app.auth.AuthBundle
        compose.setContent{SpaceWorksApp(ConnectionStateHolder("",false,true,{},{}),demoFactory={org.spaceworks.app.auth.DemoSupport.create(context=context).also{session=it}}, appearance = DeviceProfile.appearance())}
        click("Explore demo");waitText("Email or username");tag("submitSignIn");waitText("Choose your makerspace");click("Fab Lab Kochi");click("Continue");waitText("Welcome back, Meera Nair.")
        journey.click(compose.onAllNodesWithText("Profile",useUnmergedTree=true).onLast());waitText("Meera Nair")
        compose.onAllNodesWithTag("staff-entry").assertCountEquals(0);assertFalse(session.staffAvailable());assertFalse(session.staff!!.can())
        shot("a6-app-member-without-staff-tools")
    }
    @Test fun previewRecoversFromRefusalAndRequiresExplicitKitCountBeforeIssue(){
        val holder=start()
        fun scenario(id:String){tag("staff-preview-options");tag("staff-preview-$id")}
        scenario("EMPTY");waitText("No requests in this queue.");assertTrue(holder.state.value.requests.isEmpty())
        scenario("REFUSED");waitText("Staff tools are unavailable for this device session. Use the web console.")
        assertFalse(holder.state.value.available);assertNull(holder.state.value.scope)
        scenario("NORMAL");waitText("Request #601");assertTrue(holder.state.value.available)
        scenario("KIT_CHECKLIST");waitText("Request #601");preparedIssue();tag("staff-kit-preview")
        waitText("Present Sample probe for Precision toolkit");tag("staff-demo-photo");tag("staff-upload-evidence");waitText("Evidence photo uploaded")
        assertTrue(holder.state.value.handover!!.kitCounts.isEmpty());compose.onNodeWithTag("staff-handover-confirm").assertIsNotEnabled()
        replace("staff-kit-72000000-0000-4000-8000-000000000001","2")
        assertTrue(holder.ready());shot("a6-kit-explicit-count-review");tag("staff-handover-confirm");tag("staff-confirm-action");waitText("Issue #602 recorded");shot("a6-kit-issue-receipt")
    }
    @Test fun acceptsExplicitQuantitiesThenReviewsPhotoBeforeIssue(){
        val holder=start();tag("staff-request-601");replace("staff-accept-quantity-61","1");tag("staff-accept");waitText("Accept this request?");tag("staff-confirm-action");waitText("Request accepted.")
        tag("staff-prepare-issue");tag("staff-box-code-demo");waitText("Verified: Sample kit box");tag("staff-handover-next");tag("staff-borrower-confirmed");tag("staff-handover-next");tag("staff-handover-next")
        shot("a6-issue-review");photoAndConfirm();waitText("Issue #601 recorded");assertNotNull(holder.state.value.handover!!.receipt);shot("a6-issue-receipt")
    }
    @Test fun explicitReturnCountsAndDirectBorrowerSelectionCompleteSeparateGuides(){
        val holder=start();tag("staff-queue-active");waitText("Request #603");tag("staff-request-603");tag("staff-prepare-return");tag("staff-box-code-demo");tag("staff-handover-next")
        replace("staff-returned-61","1");tag("staff-handover-next");journey.replace(compose.onNodeWithText("Return notes (required)"),"One toolkit returned in good condition.")
        photoAndConfirm();waitText("Return #603 recorded");shot("a6-return-receipt");click("Back")
        tag("staff-tab-direct");waitText("New direct handout");tag("staff-direct-new");waitText("Select borrower");tag("staff-borrower-51");tag("staff-handover-next");waitText("Quantity for Sample precision toolkit")
        replace("staff-product-901","1");tag("staff-handover-next");photoAndConfirm();waitText("Issue #702 recorded");assertEquals(StaffHandoverMode.DIRECT_ISSUE,holder.state.value.handover!!.mode);shot("a6-direct-receipt")
    }
    @Test fun checkInRequiresConfirmationAndBookingApprovalIsReadableInLargeDarkLayout(){
        val holder=start(dark=true,large=true);tag("staff-tab-events");waitText("Sample repair workshop");tag("staff-event-801");tag("staff-checkin-code-demo");waitText("Confirm attendance")
        compose.onAllNodesWithTag("staff-attendance-receipt").assertCountEquals(0);assertNotNull(holder.state.value.person);shot("a6-checkin-review-dark-large")
        tag("staff-checkin-confirm");waitText("Confirm attendance?");tag("staff-confirm-action");waitText("Attendance recorded for Meera Nair.");shot("a6-attendance-receipt")
        click("Back");tag("staff-tab-spaces");waitText("Sample project room");tag("staff-space-901");tag("staff-booking-approve-1001");waitText("Approve booking?");tag("staff-confirm-action");waitText("Booking approved.")
        assertEquals("confirmed",holder.state.value.bookings.single().status.value);shot("a6-booking-approved-dark-large")
    }
    @Test fun failedIssuePreservesAssignmentAndDoesNotShowSuccessReceipt(){
        val holder=start(scenario=StaffDemoScenario.ISSUE_AFTER_ASSIGNMENT_FAILS);preparedIssue();photoAndConfirm()
        waitText("The outcome needs to be reconciled. Close this handover and refresh its latest status before continuing.")
        assertTrue(holder.state.value.handover!!.assignmentRecorded);assertTrue(holder.state.value.handover!!.needsReload);assertNull(holder.state.value.handover!!.receipt)
        compose.onAllNodesWithTag("staff-handover-confirm").assertCountEquals(0);shot("a6-partial-assignment-conflict")
    }
}
