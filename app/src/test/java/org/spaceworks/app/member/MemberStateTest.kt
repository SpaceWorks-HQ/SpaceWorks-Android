package org.spaceworks.app.member

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.network.ApiFailure

@OptIn(ExperimentalCoroutinesApi::class)
class MemberStateTest {
    private val first = MemberScope(7, "first")
    private val second = MemberScope(8, "second")
    @Test fun leavingAReadScreenClearsBusyStateAndAllowsAReentryRefresh() = runTest {
        val api=FakeGateway();val holder=MemberStateHolder(api);holder.activate(first)
        val gate=CompletableDeferred<MemberCard>();api.cardBlock={gate.await()}
        val reading=async {holder.refreshCard()};runCurrent()
        assertTrue(holder.state.value.card.loading);reading.cancelAndJoin()
        assertFalse(holder.state.value.card.loading);assertNull(holder.state.value.card.value);assertNull(holder.state.value.card.error)
        api.cardBlock={card()};holder.refreshCard();assertEquals("valid-qr",holder.state.value.card.value!!.qrImage)
    }

    @Test fun delayedOldScopeCannotOverwriteTheSelectedMakerspace() = runTest {
        val gate = CompletableDeferred<MemberPage<InventoryItem>>()
        val api = FakeGateway().apply { inventoryBlock = { scope, _, _, _ ->
            if (scope == first) gate.await() else page(item("8", "Second makerspace"))
        } }
        val holder = MemberStateHolder(api)
        val old = async { holder.activate(first) }
        runCurrent()
        holder.activate(second)
        gate.complete(page(item("7", "Old makerspace")))
        old.await()
        assertEquals(second, holder.state.value.scope)
        assertEquals(listOf("8"), holder.state.value.inventory.value!!.items.map { it.id })
    }

    @Test fun delayedSearchCannotRestoreAnOldQueryOrCategory() = runTest {
        val gate = CompletableDeferred<MemberPage<InventoryItem>>()
        val api = FakeGateway().apply { inventoryBlock = { _, query, category, _ ->
            if (query == "old") gate.await() else page(item(query.ifEmpty { "initial" }, category ?: "All"))
        } }
        val holder = MemberStateHolder(api)
        holder.activate(first)
        val old = async { holder.search("old", "wood") }
        runCurrent()
        holder.search("new", "metal")
        gate.complete(page(item("old", "Wood")))
        old.await()
        assertEquals("new", holder.state.value.query)
        assertEquals("metal", holder.state.value.category)
        assertEquals("new", holder.state.value.inventory.value!!.items.single().id)
    }

    @Test fun newerRevocationWinsOverAnOlderValidCardResponse() = runTest {
        val api = FakeGateway()
        val holder = MemberStateHolder(api)
        holder.activate(first)
        assertEquals("valid-qr", holder.state.value.card.value!!.qrImage)
        val gate = CompletableDeferred<MemberCard>()
        api.cardBlock = { gate.await() }
        val old = async { holder.refreshCard() }
        runCurrent()
        assertNull(holder.state.value.card.value)
        assertTrue(holder.state.value.card.loading)
        api.cardBlock = { card().copy(isActive = false, qrImage = "must-not-display") }
        holder.refreshCard()
        gate.complete(card())
        old.await()
        assertFalse(holder.state.value.card.value!!.isActive)
        assertNull(holder.state.value.card.value!!.qrImage)
    }

    @Test fun cardRefusalClearsPreviouslyDisplayedQrAndDoesNotRetainAnImage() = runTest {
        val api = FakeGateway()
        val holder = MemberStateHolder(api)
        holder.activate(first)
        api.cardBlock = { throw ApiFailure(403, "Membership is restricted.", null, null) }
        holder.refreshCard()
        assertNull(holder.state.value.card.value)
        assertFalse(holder.state.value.card.loading)
        assertEquals("Membership is restricted.", holder.state.value.card.error)
    }

    @Test fun unknownPerCurrencyDuesRemainUnknownEvenWhenLegacyAmountExists() = runTest {
        val api = FakeGateway().apply { overviewBlock = { overview().copy(dues = MemberDuesSummary("12.50", null)) } }
        val holder = MemberStateHolder(api)
        holder.activate(first)
        assertEquals("12.50", holder.state.value.overview.value!!.dues!!.amount)
        assertNull(holder.state.value.overview.value!!.dues!!.outstandingByCurrency)
    }

    @Test fun inventoryPaginationPreventsConcurrentLoadsAndDuplicateRows() = runTest {
        val gate = CompletableDeferred<MemberPage<InventoryItem>>()
        var moreCalls = 0
        val api = FakeGateway().apply { inventoryBlock = { _, _, _, next ->
            if (next == null) page(item("1"), next = "page-two") else { moreCalls++; gate.await() }
        } }
        val holder = MemberStateHolder(api)
        holder.activate(first)
        val loading = async { holder.moreInventory() }
        runCurrent()
        holder.moreInventory()
        assertEquals(1, moreCalls)
        gate.complete(MemberPage(listOf(item("1"), item("2")), null, 2))
        loading.await()
        assertEquals(listOf("1", "2"), holder.state.value.inventory.value!!.items.map { it.id })
        assertNull(holder.state.value.inventory.value!!.next)
    }

    @Test fun inboxMutationsWaitForServerThenRefreshTheCurrentFilter() = runTest {
        val gate = CompletableDeferred<Unit>()
        var read = false
        var allCalls = 0
        var inboxCalls = 0
        val api = FakeGateway().apply {
            inboxBlock = { _, unread, _ ->
                inboxCalls++
                val row = inboxRow().copy(readAt = if (read) "2026-10-16T10:00:00Z" else null)
                MemberPage(if (unread && read) emptyList() else listOf(row), null, if (unread && read) 0 else 1)
            }
            readBlock = { _, id -> assertEquals("91", id); gate.await(); read = true }
            readAllBlock = { allCalls++; read = true }
        }
        val holder = MemberStateHolder(api)
        holder.activate(first)
        holder.filterInbox(true)
        val marking = async { holder.markRead("91") }
        runCurrent()
        assertTrue(holder.state.value.inboxBusy)
        assertEquals(1, holder.state.value.inbox.value!!.items.size)
        holder.markAllRead() // A second mutation must not race the pending server response.
        assertEquals(0, allCalls)
        gate.complete(Unit)
        marking.await()
        assertTrue(holder.state.value.inbox.value!!.items.isEmpty())
        assertFalse(holder.state.value.inboxBusy)
        holder.filterInbox(false)
        assertNotNull(holder.state.value.inbox.value!!.items.single().readAt)
        read = false
        val before = inboxCalls
        holder.markAllRead()
        assertEquals(1, allCalls)
        assertEquals(before + 1, inboxCalls)
        assertNotNull(holder.state.value.inbox.value!!.items.single().readAt)
    }

    @Test fun retryAfterBlocksFurtherReadsAndMutationsUntilTheDeadline() = runTest {
        var clock = 1000L
        var fail = false
        var calls = 0
        var mutations = 0
        val api = FakeGateway().apply {
            inventoryBlock = { _, _, _, _ -> calls++; if (fail) throw ApiFailure(429, "Try later.", null, 5); page(item("1")) }
            readBlock = { _, _ -> mutations++ }
        }
        val holder = MemberStateHolder(api, now = { clock })
        holder.activate(first)
        fail = true
        holder.refreshInventory()
        assertEquals(6000L, holder.state.value.retryAtMillis)
        holder.refreshInventory()
        holder.search("different")
        holder.markRead("91")
        assertEquals(2, calls)
        assertEquals(0, mutations)
        fail = false; clock = 6000L
        holder.refreshInventory()
        holder.markRead("91")
        assertEquals(3, calls)
        assertEquals(1, mutations)
    }

    @Test fun unauthorizedCurrentSessionRunsCleanupAndCannotRestorePrivateData() = runTest {
        val api = FakeGateway()
        var unauthorized = 0
        lateinit var holder: MemberStateHolder
        holder = MemberStateHolder(api, onUnauthorized = { unauthorized++; holder.clear() })
        holder.activate(first)
        api.cardBlock = { throw ApiFailure(401, "Sign in again.", null, null) }
        holder.refreshCard()
        assertEquals(1, unauthorized)
        assertNull(holder.state.value.scope)
        assertNull(holder.state.value.card.value)
        assertNull(holder.state.value.overview.value)
        assertNull(holder.state.value.inventory.value)
    }

    @Test fun oldScopeUnauthorizedResponseDoesNotSignOutTheNewScope() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeGateway()
        var unauthorized = 0
        val holder = MemberStateHolder(api, onUnauthorized = { unauthorized++ })
        holder.activate(first)
        api.cardBlock = { scope -> if (scope == first) { gate.await(); throw ApiFailure(401, "Expired.", null, null) }; card() }
        val old = async { holder.refreshCard() }
        runCurrent()
        holder.activate(second)
        gate.complete(Unit)
        old.await()
        assertEquals(0, unauthorized)
        assertEquals(second, holder.state.value.scope)
        assertEquals("valid-qr", holder.state.value.card.value!!.qrImage)
    }

    @Test fun overviewRefreshDiscardsAnOlderBookingPageAndItsBusyState() = runTest {
        val gate = CompletableDeferred<MemberPage<MemberBooking>>()
        val api = FakeGateway().apply {
            overviewBlock = { overview().copy(bookings = MemberPage(listOf(booking("old")), "page-two", 2)) }
            bookingsBlock = { _, _, _ -> gate.await() }
        }
        val holder = MemberStateHolder(api)
        holder.activate(first)
        val more = async { holder.moreBookings() }
        runCurrent()
        assertTrue(holder.state.value.bookingMoreBusy)
        api.overviewBlock = { overview().copy(bookings = MemberPage(listOf(booking("fresh")), null, 1)) }
        holder.refreshOverview()
        assertFalse(holder.state.value.bookingMoreBusy)
        gate.complete(MemberPage(listOf(booking("stale-more")), null, 2))
        more.await()
        assertEquals(listOf("fresh"), holder.state.value.overview.value!!.bookings.items.map { it.id })
        assertFalse(holder.state.value.bookingMoreBusy)
    }

    @Test fun parallelShorterThrottleCannotReplaceEarlierServerDeadline() = runTest {
      for((seconds,expectedDeadline) in listOf(120L to 121000L,Long.MAX_VALUE to Long.MAX_VALUE)) {
        val inventoryEntered=CompletableDeferred<Unit>();val inventoryRelease=CompletableDeferred<Unit>()
        val cardEntered=CompletableDeferred<Unit>();val cardRelease=CompletableDeferred<Unit>()
        val api=FakeGateway()
        val holder=MemberStateHolder(api,now={1000L});holder.activate(first)
        api.apply {
            inventoryBlock={_,_,_,_->inventoryEntered.complete(Unit);inventoryRelease.await();throw ApiFailure(429,"Long wait",null,seconds)}
            cardBlock={cardEntered.complete(Unit);cardRelease.await();throw ApiFailure(429,"Zero wait",null,0)}
        }
        val inventory=async{holder.refreshInventory()};val card=async{holder.refreshCard()}
        inventoryEntered.await();cardEntered.await()
        inventoryRelease.complete(Unit);inventory.await();val longest=holder.state.value.retryAtMillis
        cardRelease.complete(Unit);card.await()
        assertEquals(expectedDeadline,longest)
        assertEquals("A later parallel zero wait cannot shorten the existing server deadline",expectedDeadline,holder.state.value.retryAtMillis)
      }
    }

    private class FakeGateway : MemberGateway {
        var inventoryBlock: suspend (MemberScope, String, String?, String?) -> MemberPage<InventoryItem> = { _, _, _, _ -> page(item("1")) }
        var overviewBlock: suspend (MemberScope) -> MemberOverview = { MemberStateTest.overview() }
        var bookingsBlock: suspend (MemberScope, String, String?) -> MemberPage<MemberBooking> = { _, _, _ -> MemberPage(emptyList(), null, 0) }
        var cardBlock: suspend (MemberScope) -> MemberCard = { MemberStateTest.card() }
        var inboxBlock: suspend (MemberScope, Boolean, String?) -> MemberPage<InboxItem> = { _, _, _ -> MemberPage(emptyList(), null, 0) }
        var readBlock: suspend (MemberScope, String) -> Unit = { _, _ -> }
        var readAllBlock: suspend (MemberScope) -> Unit = { }
        override suspend fun inventory(scope: MemberScope, query: String, category: String?, next: String?) = inventoryBlock(scope, query, category, next)
        override suspend fun categories(scope: MemberScope) = emptyList<InventoryCategory>()
        override suspend fun product(scope: MemberScope, id: String) = item(id)
        override suspend fun overview(scope: MemberScope) = overviewBlock(scope)
        override suspend fun bookings(scope: MemberScope, period: String, next: String?) = bookingsBlock(scope, period, next)
        override suspend fun card(scope: MemberScope) = cardBlock(scope)
        override suspend fun inbox(scope: MemberScope, unread: Boolean, next: String?) = inboxBlock(scope, unread, next)
        override suspend fun markRead(scope: MemberScope, id: String) = readBlock(scope, id)
        override suspend fun markAllRead(scope: MemberScope) = readAllBlock(scope)
        override suspend fun publicEvents(scope: MemberScope) = emptyList<MemberEvent>()
        override suspend fun spaces(scope: MemberScope) = emptyList<MemberSpace>()
    }

    companion object {
        private fun item(id: String, title: String = "Tool") = InventoryItem(id, title, "", null, null, null, "", null, "Ask your makerspace.", null, null)
        private fun page(item: InventoryItem, next: String? = null) = MemberPage(listOf(item), next, if (next == null) 1 else 2)
        private fun card() = MemberCard("card-1", 12, "Member", "valid-qr", true, true)
        private fun inboxRow() = InboxItem("91", "Booking updated", "Details", "booking", "updated", null, null, "2026-10-16T09:00:00Z")
        private fun booking(id: String) = MemberBooking(id, "Studio", "Asia/Kolkata", "2026-10-17T09:00:00Z", "2026-10-17T10:00:00Z", "confirmed", true, "2026-10-16T09:00:00Z")
        private fun overview() = MemberOverview(emptyList(), emptyList(), emptyList(), emptyList(), MemberPage(emptyList(), null, 0), emptyList(), null, null, true, false, null, false, emptyList())
    }
}
