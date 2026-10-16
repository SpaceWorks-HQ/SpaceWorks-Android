package org.spaceworks.app.actions

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.*
import org.spaceworks.app.network.ApiFailure

@OptIn(ExperimentalCoroutinesApi::class)
class MemberActionsStateTest {
    private val first = MemberScope(7, "first")
    private val second = MemberScope(8, "second")
    private fun makeHolder(api: FakeActionsGateway, now: () -> Long = { 0L }, unauthorized: suspend () -> Unit = {},
        capabilities: MakerCapabilitiesGateway = MakerCapabilitiesGateway { MakerCapabilities.DEMO }) =
        MemberActionsStateHolder(api, MemberStateHolder(EmptyReads()), now, unauthorized, capabilities = capabilities).apply { activate(first) }

    @Test fun delayedOldScopeDetailCannotPopulateTheNewMakerspaceOrRoute() = runTest {
        val gate = CompletableDeferred<ActionSpace>()
        val api = FakeActionsGateway().apply { spaceBlock = { _, _ -> gate.await() } }
        val holder = makeHolder(api)
        val old = async { holder.openSpace("old-space") }
        runCurrent()
        holder.activate(second)
        holder.openEvent("new-event")
        gate.complete(space("old-space"))
        old.await()
        val result = holder.state.value
        assertEquals(second, result.scope); assertEquals(ActionRoute.EVENT, result.route)
        assertEquals("new-event", result.event!!.event.id); assertNull(result.space); assertFalse(result.loading)
    }

    @Test fun delayedClosedRouteCannotRestoreItsDataOverPreferences() = runTest {
        val gate = CompletableDeferred<ActionProfile>()
        val api = FakeActionsGateway().apply { profileBlock = { gate.await() } }
        val holder = makeHolder(api)
        val old = async { holder.openProfile() }
        runCurrent()
        assertTrue(holder.close())
        holder.openPreferences()
        gate.complete(profile("Private old profile"))
        old.await()
        assertEquals(ActionRoute.PREFERENCES, holder.state.value.route)
        assertNull(holder.state.value.profile); assertNull(holder.state.value.savedProfile)
        assertNotNull(holder.state.value.preferences); assertFalse(holder.state.value.loading)
    }

    @Test fun basketLimitsTwentyProductsNinetyNineEachAndRemovesZeroWithoutLosingOtherRows() {
        val holder = makeHolder(FakeActionsGateway())
        (1..20).forEach { holder.add(item(it.toString())) }
        holder.add(item("21"))
        assertEquals(20, holder.state.value.cart.size)
        assertEquals("You can request up to 20 different items.", holder.state.value.error)
        holder.quantity("1", 500)
        assertEquals(99, holder.state.value.cart.first().quantity)
        holder.add(item("1"))
        assertEquals(99, holder.state.value.cart.first().quantity)
        holder.purpose("x".repeat(600))
        assertEquals(500, holder.state.value.purpose.length)
        holder.quantity("1", 0)
        assertEquals(19, holder.state.value.cart.size)
        assertFalse(holder.state.value.cart.any { it.item.id == "1" })
    }

    @Test fun uncertainCartRetryRetainsItsKeyAndEditedContentGetsANewKey() = runTest {
        val api = FakeActionsGateway().apply { submitBlock = { _, _, _, _ -> throw IOException("offline") } }
        val holder = makeHolder(api)
        holder.add(item("19")); holder.purpose("A shelf"); holder.openCart()
        assertFalse(holder.submitRequest()); assertFalse(holder.submitRequest())
        assertEquals(2, api.submissions.size)
        val firstKey = api.submissions[0].key
        assertFalse(firstKey.isNullOrBlank()); assertEquals(firstKey, api.submissions[1].key)
        assertNull(holder.state.value.receipt); assertEquals(1, holder.state.value.cart.single().quantity)
        holder.quantity("19", 2)
        api.submitBlock = { _, _, _, _ -> SubmittedRequest("actual-token", "pending") }
        assertTrue(holder.submitRequest())
        assertNotEquals(firstKey, api.submissions.last().key)
        assertEquals(listOf(RequestLine("19", 2)), api.submissions.last().lines)
        assertEquals("actual-token", holder.state.value.receipt!!.publicToken)
        assertEquals("pending", holder.state.value.receipt!!.status)
        assertEquals(ActionRoute.REQUEST_RECEIPT, holder.state.value.route)
        assertTrue(holder.state.value.cart.isEmpty())
    }

    @Test fun assigningUnchangedCartValuesDoesNotChangeTheReviewedAttemptKey() = runTest {
        val api = FakeActionsGateway().apply { submitBlock = { _, _, _, _ -> throw IOException("unknown outcome") } }
        val holder = makeHolder(api)
        holder.add(item("19")); holder.purpose("A shelf"); holder.openCart()
        assertFalse(holder.submitRequest())
        holder.purpose("A shelf"); holder.quantity("19", 1)
        assertFalse(holder.submitRequest())
        assertEquals(api.submissions[0].key, api.submissions[1].key)
    }

    @Test fun cancellationDoesNotStartASecondReviewedMutationOrLoseItsReceipt() = runTest {
        val gate = CompletableDeferred<SubmittedRequest>()
        val api = FakeActionsGateway().apply { submitBlock = { _, _, _, _ -> gate.await() } }
        val holder = makeHolder(api)
        holder.add(item("19")); holder.openCart()
        val saving = launch { holder.submitRequest() }
        runCurrent()
        saving.cancel()
        runCurrent()
        assertTrue(holder.state.value.busy); assertFalse(holder.close())
        holder.quantity("19", 5)
        assertFalse(holder.submitRequest()); assertEquals(1, api.submissions.size)
        assertEquals(1, holder.state.value.cart.single().quantity)
        gate.complete(SubmittedRequest("actual-token", "pending"))
        saving.join()
        assertTrue(saving.isCancelled)
        assertFalse(holder.state.value.busy); assertEquals("actual-token", holder.state.value.receipt!!.publicToken)
        assertTrue(holder.state.value.cart.isEmpty()); assertEquals(1, api.submissions.size)
    }

    @Test fun mutationCompletingAfterScopeSwitchCannotPublishItsReceiptOrClearNewCart() = runTest {
        val gate = CompletableDeferred<SubmittedRequest>()
        val api = FakeActionsGateway().apply { submitBlock = { _, _, _, _ -> gate.await() } }
        val holder = makeHolder(api)
        holder.add(item("19")); holder.openCart()
        val old = async { holder.submitRequest() }
        runCurrent()
        holder.activate(second); holder.add(item("20")); holder.openCart()
        assertFalse(holder.submitRequest()); assertEquals(1, api.submissions.size)
        gate.complete(SubmittedRequest("old-private-token", "pending"))
        assertFalse(old.await())
        assertEquals(second, holder.state.value.scope); assertEquals("20", holder.state.value.cart.single().item.id)
        assertNull(holder.state.value.receipt); assertFalse(holder.state.value.busy)
    }

    @Test fun retryAfterBlocksReadsAndWritesAcrossScopeSwitchUntilDeadline() = runTest {
        var clock = 1000L
        val api = FakeActionsGateway().apply { submitBlock = { _, _, _, _ -> throw ApiFailure(429, "Wait before retrying.", "throttled", 10) } }
        val holder = makeHolder(api, now = { clock })
        holder.add(item("19")); holder.openCart()
        assertFalse(holder.submitRequest()); assertEquals(11000L, holder.state.value.retryAtMillis)
        assertFalse(holder.submitRequest()); holder.openProfile()
        assertEquals(0, api.profileCalls)
        holder.activate(second); holder.add(item("20")); holder.openCart()
        assertEquals(11000L, holder.state.value.retryAtMillis); assertFalse(holder.submitRequest())
        assertEquals(1, api.submissions.size)
        clock = 11000L
        api.submitBlock = { _, _, _, _ -> SubmittedRequest("new-token", "pending") }
        assertTrue(holder.submitRequest()); assertEquals(2, api.submissions.size)
    }

    @Test fun serverRefusalCannotBecomeARenewalSuccessReceipt() = runTest {
        val api = FakeActionsGateway().apply { renewBlock = { _, _ -> throw ApiFailure(409, "Return this item at the desk.", "renewal_limit", null) } }
        val holder = makeHolder(api)
        holder.openRenew(loan().copy(canRenew = false, renewalRefusal = "Renewals are unavailable."))
        assertFalse(holder.renew()); assertEquals(0, api.renewCalls)
        assertEquals("Renewals are unavailable.", holder.state.value.error)
        holder.openRenew(loan())
        assertFalse(holder.renew()); assertEquals(1, api.renewCalls)
        assertEquals("Return this item at the desk.", holder.state.value.error)
        assertNull(holder.state.value.receipt); assertEquals("21", holder.state.value.loan!!.requestId)
    }

    @Test fun qrIsOnlyRequestedForKnownRegisteredRowsAndClearedOnCloseOrRefusal() = runTest {
        val api = FakeActionsGateway()
        val holder = makeHolder(api)
        holder.openRegistration(registration("pending_approval")); holder.ticketQR()
        assertEquals(0, api.qrCalls); assertNull(holder.state.value.qr)
        holder.openRegistration(registration()); holder.ticketQR()
        assertEquals(1, api.qrCalls); assertArrayEquals(byteArrayOf(1, 2, 3), holder.state.value.qr)
        api.qrBlock = { _, _ -> throw ApiFailure(404, "This ticket is unavailable.", null, null) }
        holder.ticketQR()
        assertNull(holder.state.value.qr); assertEquals("This ticket is unavailable.", holder.state.value.error)
        assertTrue(holder.close()); assertNull(holder.state.value.qr)
        holder.openRegistration(registration("waitlisted")); holder.ticketQR()
        assertEquals(2, api.qrCalls); assertNull(holder.state.value.qr)
    }

    @Test fun olderQrResponseCannotResurrectTicketAfterTheLatestRefusal() = runTest {
        val gate = CompletableDeferred<ByteArray>()
        val api = FakeActionsGateway().apply { qrBlock = { _, _ -> gate.await() } }
        val holder = makeHolder(api)
        holder.openRegistration(registration())
        val old = async { holder.ticketQR() }
        runCurrent()
        api.qrBlock = { _, _ -> throw ApiFailure(404, "Registration is no longer confirmed.", null, null) }
        holder.ticketQR()
        gate.complete(byteArrayOf(9))
        old.await()
        assertNull(holder.state.value.qr)
        assertEquals("Registration is no longer confirmed.", holder.state.value.error)
    }

    @Test fun successfulCancellationInvalidatesAnEarlierTicketAndTheLocalRegisteredState() = runTest {
        val gate = CompletableDeferred<ByteArray>()
        val api = FakeActionsGateway().apply { qrBlock = { _, _ -> gate.await() } }
        val holder = makeHolder(api)
        holder.openRegistration(registration())
        val old = async { holder.ticketQR() }
        runCurrent()
        assertTrue(holder.cancelRegistration())
        gate.complete(byteArrayOf(9))
        old.await()
        assertNull(holder.state.value.qr)
        assertEquals("cancelled", holder.state.value.registration!!.status)
        assertFalse(holder.state.value.registration!!.canCancel)
        val calls = api.qrCalls
        holder.ticketQR()
        assertEquals(calls, api.qrCalls); assertNull(holder.state.value.qr)
    }

    @Test fun profileSaveFailureKeepsDraftUntilAuthoritativeSuccess() = runTest {
        val original = profile("Meera")
        val edited = original.copy(displayName = "Meera Rao", phone = "+919999999999")
        val api = FakeActionsGateway().apply { profileBlock = { original }; saveProfileBlock = { _, _ -> throw ApiFailure(400, "Review your phone number.", null, null) } }
        val holder = makeHolder(api)
        holder.openProfile(); holder.editProfile(edited)
        assertTrue(holder.profileDirty); assertFalse(holder.saveProfile())
        assertEquals(edited, holder.state.value.profile); assertEquals(original, holder.state.value.savedProfile)
        assertTrue(holder.profileDirty); assertNull(holder.state.value.receipt)
        val authoritative = edited.copy(firstName = "Server normalized")
        api.saveProfileBlock = { _, _ -> authoritative }
        assertTrue(holder.saveProfile())
        assertEquals(authoritative, holder.state.value.profile); assertEquals(authoritative, holder.state.value.savedProfile)
        assertFalse(holder.profileDirty)
    }

    @Test fun optionalPreferencesRespectAvailabilityWithoutLockingMandatorySafetyMessages() = runTest {
        val unavailableOff = preference(enabled = false, available = false)
        val unavailableOn = preference(enabled = true, available = false)
        val api = FakeActionsGateway().apply { preferenceRows = listOf(unavailableOff) }
        val holder = makeHolder(api)
        holder.openPreferences()
        assertFalse(holder.preference(unavailableOff, true)); assertTrue(api.preferenceWrites.isEmpty())
        api.preferenceRows = listOf(unavailableOn); holder.openPreferences()
        api.preferenceBlock = { _, _, _, enabled, _ -> listOf(unavailableOn.copy(enabled = enabled)) }
        assertTrue(holder.preference(unavailableOn, false))
        assertFalse(holder.state.value.preferences!!.single().enabled)
        assertEquals(listOf("account_recovery"), holder.state.value.preferences!!.single().mandatoryEvents)
        assertEquals(1, api.preferenceWrites.size)
        val native = preference(enabled = true, available = true).copy(channel = "native_push", dailyDigest = null)
        api.preferenceRows = listOf(native); holder.openPreferences()
        assertFalse(holder.preference(native, true, true)); assertEquals(1, api.preferenceWrites.size)
    }

    @Test fun missingQuestionsInviteAndTicketCannotCreateRegistrationSuccess() = runTest {
        val required = ActionForm(listOf(ActionQuestion("safety", "Read the guide?", "yes_no", true, emptyList())))
        val ticket = ActionTicket("15", "Member", "0", "members", 2)
        val api = FakeActionsGateway().apply { eventBlock = { _, id -> event(id).copy(form = required, audience = "invite", tickets = listOf(ticket), ticketRequired = false) } }
        val holder = makeHolder(api)
        holder.openEvent("event")
        assertFalse(holder.register()); assertEquals("This question is required.", holder.state.value.fieldErrors["safety"])
        holder.answer("safety", JsonPrimitive(false))
        assertFalse(holder.register()); assertEquals(0, api.registerCalls)
        holder.ticket("15")
        assertFalse(holder.register()); assertEquals(0, api.registerCalls)
        holder.registrationFields(invite = "invitation")
        assertTrue(holder.register()); assertEquals(1, api.registerCalls)
        assertEquals("waitlisted", holder.state.value.receipt!!.status)
        assertNull(holder.state.value.registration); assertNull(holder.state.value.qr)
    }

    @Test fun bookingFailureKeepsReviewedAnswersAndTimesAndNeverClaimsConfirmation() = runTest {
        val form = ActionForm(listOf(ActionQuestion("safety", "Read the guide?", "yes_no", true, emptyList())))
        val api = FakeActionsGateway().apply { spaceBlock = { _, id -> space(id).copy(form = form) }; bookBlock = { _, _, _, _, _ -> throw ApiFailure(409, "That time overlaps a booking.", null, null) } }
        val holder = makeHolder(api)
        holder.openSpace("space"); holder.times(START, END)
        assertFalse(holder.book()); assertEquals(0, api.bookCalls)
        holder.answer("safety", JsonPrimitive(false)); holder.checkAvailability()
        assertFalse(holder.book()); assertEquals(1, api.bookCalls)
        assertEquals(JsonPrimitive(false), holder.state.value.answers["safety"])
        assertEquals(START, holder.state.value.startsAt); assertEquals(END, holder.state.value.endsAt)
        assertNull(holder.state.value.receipt); assertEquals("That time overlaps a booking.", holder.state.value.error)
    }

    @Test fun cancellingReadClearsItsSpinnerAndAllowsAnExplicitRetry() = runTest {
        val gate = CompletableDeferred<ActionEvent>()
        val api = FakeActionsGateway().apply { eventBlock = { _, _ -> gate.await() } }
        val holder = makeHolder(api)
        val loading = launch { holder.openEvent("event") }
        runCurrent(); assertTrue(holder.state.value.loading)
        loading.cancel(); loading.join()
        assertFalse(holder.state.value.loading); assertNull(holder.state.value.event)
        api.eventBlock = { _, id -> event(id) }; holder.openEvent("event")
        assertEquals("event", holder.state.value.event!!.event.id); assertFalse(holder.state.value.loading)
    }

    @Test fun unauthorizedCleansPrivateStateAndAccountResetOnlyOccursOnClear() = runTest {
        val api = FakeActionsGateway()
        var unauthorized = 0
        lateinit var holder: MemberActionsStateHolder
        holder = makeHolder(api, unauthorized = { unauthorized++; holder.clear() })
        holder.openRegistration(registration()); holder.ticketQR()
        holder.activate(second)
        assertEquals(0, api.accountResets); assertNull(holder.state.value.qr)
        holder.openRegistration(registration()); holder.ticketQR()
        api.profileBlock = { throw ApiFailure(401, "Sign in again.", null, null) }
        holder.openProfile()
        assertEquals(1, unauthorized); assertEquals(1, api.accountResets)
        assertNull(holder.state.value.scope); assertNull(holder.state.value.qr); assertNull(holder.state.value.profile)
        assertNull(holder.state.value.registration); assertNull(holder.state.value.receipt)
    }

    @Test fun failedDisabledAndOldScopeCapabilitiesCannotUnlockAccountReadsOrMutations() = runTest {
        val oldCapabilities = CompletableDeferred<MakerCapabilities>()
        var rejectNewScope = true
        val capabilities = MakerCapabilitiesGateway { scope ->
            if (scope == first) oldCapabilities.await()
            else if (rejectNewScope) throw ApiFailure(403, "Your makerspace access changed. Choose again.", null, null)
            else MakerCapabilities.NONE
        }
        val api = FakeActionsGateway()
        val holder = makeHolder(api, capabilities = capabilities)
        val old = launch { holder.refreshCapabilities() }
        runCurrent(); assertTrue(holder.state.value.capabilities.loading)
        holder.activate(second)
        holder.openProfile(); holder.openPreferences()
        assertEquals(0, api.profileCalls); assertEquals(0, api.preferenceReads)
        assertNull(holder.state.value.capabilities.value)
        oldCapabilities.complete(MakerCapabilities.DEMO); old.join()
        assertTrue(old.isCancelled)
        assertNull(holder.state.value.capabilities.value)
        holder.editProfile(profile("Private draft"))
        assertFalse(holder.saveProfile()); assertEquals(0, api.saveProfileCalls)
        assertFalse(holder.preference(preference(true, true), false)); assertTrue(api.preferenceWrites.isEmpty())
        rejectNewScope = false
        holder.openProfile(); holder.openPreferences()
        assertEquals(MakerCapabilities.NONE, holder.state.value.capabilities.value)
        assertEquals(0, api.profileCalls); assertEquals(0, api.preferenceReads)
        assertFalse(holder.saveProfile()); assertEquals(0, api.saveProfileCalls)
        assertFalse(holder.preference(preference(true, true), false)); assertTrue(api.preferenceWrites.isEmpty())
    }

    @Test fun disabledDigestCapabilityStillAllowsOptionalMessagesWithoutSendingDigestField() = runTest {
        val cell = preference(enabled = true, available = true).copy(dailyDigest = false)
        val api = FakeActionsGateway().apply { preferenceRows = listOf(cell) }
        val holder = makeHolder(api, capabilities = MakerCapabilitiesGateway { MakerCapabilities(true, true, false) })
        holder.openPreferences()
        assertFalse(holder.preference(cell, true, true)); assertTrue(api.preferenceWrites.isEmpty())
        api.preferenceBlock = { _, _, _, enabled, _ -> listOf(cell.copy(enabled = enabled)) }
        assertTrue(holder.preference(cell, false))
        assertEquals(1, api.preferenceWrites.size)
        assertFalse(api.preferenceWrites.single().enabled)
        assertNull("A disabled digest capability must omit the digest transport field", api.preferenceWrites.single().digest)
        assertEquals(false, holder.state.value.preferences!!.single().dailyDigest)
        assertEquals(listOf("account_recovery"), holder.state.value.preferences!!.single().mandatoryEvents)
    }

    private data class Submission(val scope: MemberScope, val purpose: String, val lines: List<RequestLine>, val key: String?)
    private data class PreferenceWrite(val feature: String, val channel: String, val enabled: Boolean, val digest: Boolean?)
    private class FakeActionsGateway : MemberActionsGateway, ActionAccountReset {
        var accountResets = 0; var profileCalls = 0; var saveProfileCalls = 0; var preferenceReads = 0; var qrCalls = 0; var renewCalls = 0; var registerCalls = 0; var bookCalls = 0
        val submissions = mutableListOf<Submission>()
        val preferenceWrites = mutableListOf<PreferenceWrite>()
        var submitBlock: suspend (MemberScope, String, List<RequestLine>, String?) -> SubmittedRequest = { _, _, _, _ -> SubmittedRequest("actual-token", "pending") }
        var renewBlock: suspend (MemberScope, String) -> LoanRenewalResult = { _, id -> LoanRenewalResult(id, END, 1) }
        var spaceBlock: suspend (MemberScope, String) -> ActionSpace = { _, id -> MemberActionsStateTest.space(id) }
        var eventBlock: suspend (MemberScope, String) -> ActionEvent = { _, id -> MemberActionsStateTest.event(id) }
        var profileBlock: suspend (MemberScope) -> ActionProfile = { MemberActionsStateTest.profile("Meera") }
        var saveProfileBlock: suspend (MemberScope, ActionProfile) -> ActionProfile = { _, value -> value }
        var qrBlock: suspend (MemberScope, String) -> ByteArray = { _, _ -> byteArrayOf(1, 2, 3) }
        var bookBlock: suspend (MemberScope, String, String, String, Map<String, JsonElement>) -> String = { _, _, _, _, _ -> "pending" }
        var preferenceRows = listOf(preference(enabled = true, available = true))
        var preferenceBlock: suspend (MemberScope, String, String, Boolean, Boolean?) -> List<ActionPreference> = { _, _, _, _, _ -> preferenceRows }
        override fun resetAccount() { accountResets++ }
        override suspend fun submitRequest(scope: MemberScope, purpose: String, lines: List<RequestLine>, idempotencyKey: String?): SubmittedRequest {
            submissions += Submission(scope, purpose, lines, idempotencyKey); return submitBlock(scope, purpose, lines, idempotencyKey)
        }
        override suspend fun requestStatus(scope: MemberScope, publicToken: String) = RequestStatus("pending", START, emptyList())
        override suspend fun renew(scope: MemberScope, requestId: String): LoanRenewalResult { renewCalls++; return renewBlock(scope, requestId) }
        override suspend fun space(scope: MemberScope, publicToken: String) = spaceBlock(scope, publicToken)
        override suspend fun availability(scope: MemberScope, publicToken: String, startsAtISO: String, endsAtISO: String) =
            ActionAvailability(publicToken, startsAtISO, endsAtISO, "Asia/Kolkata", listOf(ActionWindow(startsAtISO, endsAtISO)), null)
        override suspend fun book(scope: MemberScope, publicToken: String, startsAtISO: String, endsAtISO: String, answers: Map<String, JsonElement>): String { bookCalls++; return bookBlock(scope, publicToken, startsAtISO, endsAtISO, answers) }
        override suspend fun reschedule(scope: MemberScope, bookingToken: String, startsAtISO: String, endsAtISO: String) = booking(bookingToken).copy(startsAt = startsAtISO, endsAt = endsAtISO)
        override suspend fun cancelBooking(scope: MemberScope, bookingToken: String) = booking(bookingToken).copy(status = "cancelled", canCancel = false)
        override suspend fun event(scope: MemberScope, publicToken: String) = eventBlock(scope, publicToken)
        override suspend fun register(scope: MemberScope, eventToken: String, answers: Map<String, JsonElement>, phone: String?, email: String?, inviteCode: String?, ticketId: String?, promoCode: String?): String { registerCalls++; return "waitlisted" }
        override suspend fun cancelRegistration(scope: MemberScope, registrationId: String) = "cancelled"
        override suspend fun registrationQR(scope: MemberScope, registrationId: String): ByteArray { qrCalls++; return qrBlock(scope, registrationId) }
        override suspend fun profile(scope: MemberScope): ActionProfile { profileCalls++; return profileBlock(scope) }
        override suspend fun updateProfile(scope: MemberScope, value: ActionProfile): ActionProfile { saveProfileCalls++; return saveProfileBlock(scope, value) }
        override suspend fun preferences(scope: MemberScope): List<ActionPreference> { preferenceReads++; return preferenceRows }
        override suspend fun updatePreference(scope: MemberScope, feature: String, channel: String, enabled: Boolean, dailyDigest: Boolean?): List<ActionPreference> {
            preferenceWrites += PreferenceWrite(feature, channel, enabled, dailyDigest); return preferenceBlock(scope, feature, channel, enabled, dailyDigest)
        }
    }

    private class EmptyReads : MemberGateway {
        override suspend fun inventory(scope: MemberScope, query: String, category: String?, next: String?) = MemberPage<InventoryItem>(emptyList(), null, 0)
        override suspend fun categories(scope: MemberScope) = emptyList<InventoryCategory>()
        override suspend fun product(scope: MemberScope, id: String) = item(id)
        override suspend fun overview(scope: MemberScope) = MemberOverview(emptyList(), emptyList(), emptyList(), emptyList(), MemberPage(emptyList(), null, 0), emptyList(), null, null, true, false, null, false, emptyList())
        override suspend fun bookings(scope: MemberScope, period: String, next: String?) = MemberPage<MemberBooking>(emptyList(), null, 0)
        override suspend fun card(scope: MemberScope) = MemberCard(null, null, "", null, false, false, true)
        override suspend fun inbox(scope: MemberScope, unread: Boolean, next: String?) = MemberPage<InboxItem>(emptyList(), null, 0)
        override suspend fun markRead(scope: MemberScope, id: String) {}
        override suspend fun markAllRead(scope: MemberScope) {}
        override suspend fun publicEvents(scope: MemberScope) = emptyList<MemberEvent>()
        override suspend fun spaces(scope: MemberScope) = emptyList<MemberSpace>()
    }
    companion object {
        private const val START = "2026-10-20T10:00:00+05:30"
        private const val END = "2026-10-20T11:00:00+05:30"
        private fun item(id: String) = InventoryItem(id, "Tool$id", "", null, null, null, "", null, "Ask your makerspace.", null, null)
        private fun loan() = MemberLoan("21", "Clamp", START, END, false, true, null, 0)
        private fun booking(id: String) = MemberBooking(id, "Studio", "Asia/Kolkata", START, END, "confirmed", true, START)
        private fun registration(status: String = "registered") = MemberRegistration("31", "Make a shelf", START, END, status, true, START, null, null, false, null)
        private fun profile(name: String) = ActionProfile(name, null, null, null)
        private fun preference(enabled: Boolean, available: Boolean) = ActionPreference("events", "email", enabled, available, listOf("account_recovery"), null)
        private fun space(id: String) = ActionSpace(MemberSpace(id, "Studio", "", "meeting", 4, "Workshop", null, "approve", false), ActionForm(emptyList()))
        private fun event(id: String) = ActionEvent(MemberEvent(id, "Build a shelf", "", START, END, "Workshop", "0", "INR", "Available", true, false, null), ActionForm(emptyList()), "members", emptyList(), false, false)
    }
}
