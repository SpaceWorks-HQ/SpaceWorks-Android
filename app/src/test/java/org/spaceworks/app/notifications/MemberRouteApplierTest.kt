package org.spaceworks.app.notifications

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.actions.*
import org.spaceworks.app.member.*
import org.spaceworks.app.network.ApiFailure

@OptIn(ExperimentalCoroutinesApi::class)
class MemberRouteApplierTest {
    private val scope = MemberScope(7, "workshop")
    private val other = MemberScope(8, "other")
    private val booking = MemberBooking("real-booking", "Studio", "UTC", "2030-01-01T10:00:00Z", "2030-01-01T11:00:00Z", "confirmed", true, "2030-01-01T09:00:00Z")
    private val registration = MemberRegistration("42", "Workshop", "2030-01-01T10:00:00Z", "2030-01-01T11:00:00Z", "registered", true, "2030-01-01T09:00:00Z", "ticket-secret", null, false, null)

    private inner class Fixture {
        val calls = mutableListOf<String>()
        val gateway = Reads(calls)
        val actionGateway = Actions(calls)
        val reads = MemberStateHolder(gateway, now = { 0L })
        val actions = MemberActionsStateHolder(actionGateway, reads, now = { 0L })
        val tabs = mutableListOf<Int>()
        val extras = mutableListOf<String>()
        val payments = mutableListOf<String?>()
        val router = MemberRouteApplier(reads, actions, { tabs += it; calls += "tab:$it" }, { extras += it }, { payments += it })
        var current = true
        suspend fun start() { reads.activate(scope); actions.activate(scope); calls.clear() }
        suspend fun open(section: MobileSection, id: String? = null, notification: Boolean = true, maker: Int = scope.id) =
            router.open(AuthorizedNavigation(MobileDestination(maker, section, id), 1, notification), scope) { current }
    }

    @Test fun pushRefreshesInboxBeforeReadingTheTargetAndNeverMarksItRead() = runTest {
        val f = Fixture(); f.start()
        assertNull(f.open(MobileSection.INVENTORY, "19"))
        assertEquals(listOf("inbox:7", "tab:1", "product:7:19"), f.calls)
        assertEquals("19", f.reads.state.value.product.value!!.id)
    }

    @Test fun inboxRefusalBlocksEveryTargetEvenWhenCachedInboxExists() = runTest {
        for (status in listOf(401, 403, 429)) {
            val f = Fixture(); f.start()
            f.gateway.inboxBlock = { throw ApiFailure(status, "Refused", null, 30) }
            assertEquals("Couldn't refresh this update. Try again from Inbox.", f.open(MobileSection.EVENTS, "event-1"))
            assertEquals(listOf("inbox:7", "tab:3"), f.calls)
            assertNull(f.actions.state.value.route)
        }
    }

    @Test fun directInventoryLinkDoesNotFetchInbox() = runTest {
        val f = Fixture(); f.start(); f.open(MobileSection.INVENTORY, "23", notification = false)
        assertEquals(listOf("tab:1", "product:7:23"), f.calls)
    }

    @Test fun revokedContextDuringInboxReadCannotNavigateOrReadTarget() = runTest {
        val f = Fixture(); f.start(); val gate = CompletableDeferred<MemberPage<InboxItem>>()
        f.gateway.inboxBlock = { gate.await() }
        val pending = async { f.open(MobileSection.EVENTS, "event-1") }; runCurrent()
        f.current = false; gate.complete(MemberPage(emptyList(), null, 0)); assertNull(pending.await())
        assertEquals(listOf("inbox:7"), f.calls); assertTrue(f.tabs.isEmpty()); assertNull(f.actions.state.value.route)
    }

    @Test fun mismatchedDestinationOrHolderScopeCannotReadOrNavigate() = runTest {
        val f = Fixture(); f.start()
        assertNull(f.open(MobileSection.INBOX, maker = other.id)); assertTrue(f.calls.isEmpty())
        f.actions.activate(other)
        assertNull(f.open(MobileSection.INBOX)); assertTrue(f.calls.isEmpty())
    }

    @Test fun bookingAndRegistrationComeOnlyFromFreshScopedActivityRows() = runTest {
        val f = Fixture(); f.start()
        f.gateway.overviewValue = overview().copy(bookings = MemberPage(listOf(booking), null, 1), registrations = listOf(registration))
        assertNull(f.open(MobileSection.BOOKINGS, booking.id))
        assertEquals(listOf("inbox:7", "tab:2", "overview:7"), f.calls)
        assertEquals(booking, f.actions.state.value.booking)
        assertTrue(f.actions.close()); f.calls.clear()
        assertNull(f.open(MobileSection.REGISTRATION, registration.id))
        assertEquals(registration, f.actions.state.value.registration)
        assertEquals(listOf("inbox:7", "tab:2", "overview:7"), f.calls)
        assertNull(f.actions.state.value.qr)
    }

    @Test fun missingActualBookingOrRegistrationIdCannotManufactureAnAction() = runTest {
        val f = Fixture(); f.start()
        assertEquals("This booking is no longer available. Refresh Activity.", f.open(MobileSection.BOOKINGS, "missing"))
        assertNull(f.actions.state.value.route)
        assertEquals("This registration is no longer available. Refresh Activity.", f.open(MobileSection.REGISTRATION, "missing"))
        assertNull(f.actions.state.value.route)
    }

    @Test fun activityRefusalCannotOpenCachedBooking() = runTest {
        val f = Fixture(); f.gateway.overviewValue = overview().copy(bookings = MemberPage(listOf(booking), null, 1)); f.start()
        f.gateway.overviewBlock = { throw ApiFailure(403, "Restricted", null, null) }
        assertEquals("Couldn't refresh this update. Try again from Activity.", f.open(MobileSection.BOOKINGS, booking.id))
        assertNull(f.actions.state.value.route)
    }

    @Test fun replacedLinkDuringActivityReadCannotOpenItsOldBooking() = runTest {
        val f = Fixture(); f.start(); val gate = CompletableDeferred<MemberOverview>()
        f.gateway.overviewBlock = { gate.await() }
        val pending = async { f.open(MobileSection.BOOKINGS, booking.id) }; runCurrent()
        f.current = false
        gate.complete(overview().copy(bookings = MemberPage(listOf(booking), null, 1)))
        assertNull(pending.await()); assertNull(f.actions.state.value.route)
        assertEquals(listOf("inbox:7", "tab:2", "overview:7"), f.calls)
    }

    @Test fun existingCartIsPreservedAfterPushInboxRefresh() = runTest {
        val f = Fixture(); f.start(); f.actions.add(item("19")); f.actions.purpose("Keep this draft"); f.actions.openCart()
        assertEquals("Finish your current action before opening this update.", f.open(MobileSection.EVENTS, "event-1"))
        assertEquals(listOf("inbox:7"), f.calls); assertEquals(ActionRoute.CART, f.actions.state.value.route)
        assertEquals("Keep this draft", f.actions.state.value.purpose); assertEquals("19", f.actions.state.value.cart.single().item.id)
    }

    @Test fun formOpenedDuringActivityRefreshCannotBeReplacedByTheLink() = runTest {
        val f = Fixture(); f.start(); val gate = CompletableDeferred<MemberOverview>()
        f.gateway.overviewBlock = { gate.await() }
        val pending = async { f.open(MobileSection.BOOKINGS, booking.id) }; runCurrent()
        f.actions.openCart(); f.actions.purpose("New draft")
        gate.complete(overview().copy(bookings = MemberPage(listOf(booking), null, 1)))
        assertEquals("Finish your current action before opening this update.", pending.await())
        assertEquals(ActionRoute.CART, f.actions.state.value.route); assertEquals("New draft", f.actions.state.value.purpose)
        assertNull(f.actions.state.value.booking)
    }

    @Test fun delayedHomeListCannotOpenAnExtraAfterContextChanges() = runTest {
        val f = Fixture(); f.start(); val gate = CompletableDeferred<List<MemberEvent>>()
        f.gateway.eventsBlock = { gate.await() }
        val pending = async { f.open(MobileSection.EVENTS) }; runCurrent()
        f.current = false; gate.complete(emptyList()); pending.await()
        assertTrue(f.extras.isEmpty()); assertNull(f.actions.state.value.route)
    }

    @Test fun paymentLinkPassesOnlyActualResourceIdAfterFreshInbox() = runTest {
        val f = Fixture(); f.start(); assertNull(f.open(MobileSection.PAYMENTS, "251"))
        assertEquals(listOf("inbox:7"), f.calls); assertEquals(listOf("251"), f.payments)
        assertNull(f.actions.state.value.route)
    }

    @Test fun eventAndSpaceDetailsUseTheAuthorizedScopeWithoutRegisteringOrBooking() = runTest {
        val f = Fixture(); f.start()
        assertNull(f.open(MobileSection.EVENTS, "actual-event"))
        assertEquals(listOf("inbox:7", "tab:0", "event:7:actual-event"), f.calls)
        assertEquals("actual-event", f.actions.state.value.event!!.event.id)
        assertNull(f.actions.state.value.receipt)
        assertTrue(f.actions.close()); f.calls.clear()
        assertNull(f.open(MobileSection.SPACES, "actual-space"))
        assertEquals(listOf("inbox:7", "tab:0", "space:7:actual-space"), f.calls)
        assertEquals("actual-space", f.actions.state.value.space!!.space.id)
        assertNull(f.actions.state.value.receipt)
    }

    @Test fun notificationInboxTargetRefetchesOnlyOnceAndDoesNotMarkRead() = runTest {
        val f = Fixture(); f.start(); assertNull(f.open(MobileSection.INBOX))
        assertEquals(listOf("inbox:7", "tab:3"), f.calls)
    }

    @Test fun unavailablePaymentIntegrationReturnsAnHonestNoticeWithoutOpeningCheckout() = runTest {
        val f = Fixture(); f.start()
        val router = MemberRouteApplier(f.reads, f.actions, { f.tabs += it }, { f.extras += it })
        val notice = router.open(AuthorizedNavigation(MobileDestination(scope.id, MobileSection.PAYMENTS, "251"), 1, true), scope) { true }
        assertEquals("Payments are not available in this build yet.", notice)
        assertEquals(listOf("inbox:7"), f.calls); assertEquals(listOf(4), f.tabs)
        assertTrue(f.payments.isEmpty()); assertNull(f.actions.state.value.route)
    }

    private fun item(id: String) = InventoryItem(id, "Tool", "", null, null, null, "", null, "available", null, 1)
    private fun overview() = MemberOverview(emptyList(), emptyList(), emptyList(), emptyList(), MemberPage(emptyList(), null, 0), emptyList(), null, null, true, false, null, false, emptyList())

    private inner class Reads(private val calls: MutableList<String>) : MemberGateway {
        var inboxBlock: suspend () -> MemberPage<InboxItem> = { MemberPage(emptyList(), null, 0) }
        var overviewValue = overview()
        var overviewBlock: suspend () -> MemberOverview = { overviewValue }
        var eventsBlock: suspend () -> List<MemberEvent> = { emptyList() }
        override suspend fun inventory(scope: MemberScope, query: String, category: String?, next: String?) = MemberPage<InventoryItem>(emptyList(), null, 0)
        override suspend fun categories(scope: MemberScope) = emptyList<InventoryCategory>()
        override suspend fun product(scope: MemberScope, id: String): InventoryItem { calls += "product:${scope.id}:$id"; return item(id) }
        override suspend fun overview(scope: MemberScope): MemberOverview { calls += "overview:${scope.id}"; return overviewBlock() }
        override suspend fun bookings(scope: MemberScope, period: String, next: String?) = MemberPage<MemberBooking>(emptyList(), null, 0)
        override suspend fun card(scope: MemberScope) = MemberCard(null, null, "Member", null, false, false, true)
        override suspend fun inbox(scope: MemberScope, unread: Boolean, next: String?): MemberPage<InboxItem> { calls += "inbox:${scope.id}"; return inboxBlock() }
        override suspend fun markRead(scope: MemberScope, id: String) { error("Navigation must not mark read") }
        override suspend fun markAllRead(scope: MemberScope) { error("Navigation must not mark all read") }
        override suspend fun publicEvents(scope: MemberScope): List<MemberEvent> { calls += "events:${scope.id}"; return eventsBlock() }
        override suspend fun spaces(scope: MemberScope) = emptyList<MemberSpace>()
    }

    private class Actions(private val calls: MutableList<String>) : MemberActionsGateway {
        override suspend fun submitRequest(scope: MemberScope, purpose: String, lines: List<RequestLine>, idempotencyKey: String?): SubmittedRequest = error("No mutation")
        override suspend fun requestStatus(scope: MemberScope, publicToken: String): RequestStatus = error("Unexpected read")
        override suspend fun renew(scope: MemberScope, requestId: String): LoanRenewalResult = error("No mutation")
        override suspend fun space(scope: MemberScope, publicToken: String): ActionSpace { calls += "space:${scope.id}:$publicToken"; return ActionSpace(MemberSpace(publicToken, "Studio", "", "room", 4, "", null, "instant", true), ActionForm(emptyList())) }
        override suspend fun availability(scope: MemberScope, publicToken: String, startsAtISO: String, endsAtISO: String): ActionAvailability = error("Unexpected read")
        override suspend fun book(scope: MemberScope, publicToken: String, startsAtISO: String, endsAtISO: String, answers: Map<String, JsonElement>): String = error("No mutation")
        override suspend fun reschedule(scope: MemberScope, bookingToken: String, startsAtISO: String, endsAtISO: String): MemberBooking = error("No mutation")
        override suspend fun cancelBooking(scope: MemberScope, bookingToken: String): MemberBooking = error("No mutation")
        override suspend fun event(scope: MemberScope, publicToken: String): ActionEvent { calls += "event:${scope.id}:$publicToken"; return ActionEvent(MemberEvent(publicToken, "Workshop", "", "", "", "", "0", "INR", "available", true, false, null), ActionForm(emptyList()), "public", emptyList(), false, false) }
        override suspend fun register(scope: MemberScope, eventToken: String, answers: Map<String, JsonElement>, phone: String?, email: String?, inviteCode: String?, ticketId: String?, promoCode: String?): String = error("No mutation")
        override suspend fun cancelRegistration(scope: MemberScope, registrationId: String): String = error("No mutation")
        override suspend fun registrationQR(scope: MemberScope, registrationId: String): ByteArray = error("Must not reveal ticket automatically")
        override suspend fun profile(scope: MemberScope): ActionProfile = error("Unexpected read")
        override suspend fun updateProfile(scope: MemberScope, value: ActionProfile): ActionProfile = error("No mutation")
        override suspend fun preferences(scope: MemberScope): List<ActionPreference> = error("Unexpected read")
        override suspend fun updatePreference(scope: MemberScope, feature: String, channel: String, enabled: Boolean, dailyDigest: Boolean?): List<ActionPreference> = error("No mutation")
    }
}
