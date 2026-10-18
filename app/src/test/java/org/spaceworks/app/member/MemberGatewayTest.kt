package org.spaceworks.app.member

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.network.ApiSession
import org.spaceworks.app.network.MemoryTokenStore
import org.spaceworks.app.network.TokenPair
import java.util.concurrent.TimeUnit

class MemberGatewayTest {
    private val scope = MemberScope(7, "maker-space")
    private suspend fun withGateway(block: suspend (GeneratedMemberGateway, MockWebServer) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val tokens = MemoryTokenStore().apply { replace(TokenPair("member-access", "member-refresh")) }
            // A stale global selection must not replace the immutable request's selected scope.
            block(GeneratedMemberGateway(ApiSession(server.url("/"), tokens) { "999" }), server)
        }
    }
    private fun response(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun request(server: MockWebServer): RecordedRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    private fun scoped(request: RecordedRequest) {
        assertEquals("Bearer member-access", request.getHeader("Authorization"))
        assertEquals("7", request.getHeader("X-Makerspace-Id"))
        for (name in listOf("Cookie", "Origin", "Referer", "X-Publishable-Key", "X-Nonce")) assertNull(name, request.getHeader(name))
    }

    @Test fun generatedInventoryCategoriesAndDetailKeepActualIDsAndUnknownAvailability() = runBlocking {
        withGateway { gateway, server ->
            server.enqueue(response("""{"count":3,"next":"?page=2","previous":null,"results":[${product("null")},${product("""{"mode":"status_only","label":"Unavailable","count":999}""")},${product("""{"mode":"exact_count","label":"Limited","count":2}""") }]}"""))
            val inventory = gateway.inventory(scope, "tool & clamp", "wood")
            val query = request(server); scoped(query)
            assertEquals("/api/v1/public/maker-space/inventory/", query.requestUrl!!.encodedPath)
            assertEquals("tool & clamp", query.requestUrl!!.queryParameter("q")); assertEquals("wood", query.requestUrl!!.queryParameter("category"))
            assertEquals("1", query.requestUrl!!.queryParameter("page"))
            assertEquals("19", inventory.items.first().id)
            assertEquals("https://images.example/tool.png", inventory.items.first().imageUrl)
            assertEquals("Ask your makerspace about availability.", inventory.items.first().availability)
            assertNull(inventory.items.first().availableCount)
            assertEquals("Unavailable", inventory.items[1].availability); assertNull(inventory.items[1].availableCount)
            assertEquals(2, inventory.items[2].availableCount)
            assertEquals("?page=2", inventory.next)
            server.enqueue(response("""[{"id":5,"name":"Woodwork","slug":"wood","display_order":1,"icon":"hammer","product_count":99}]"""))
            assertEquals(InventoryCategory("5", "Woodwork", "wood", 99), gateway.categories(scope).single())
            val categories = request(server); scoped(categories); assertEquals("/api/v1/public/maker-space/inventory/categories/", categories.path)
            server.enqueue(response(product("null")))
            assertEquals("Wear eye protection.", gateway.product(scope, "19").borrowingTerms)
            val detail = request(server); scoped(detail); assertEquals("/api/v1/public/maker-space/inventory/19/", detail.path)
        }
    }

    @Test fun paginationUsesOnlyValidatedSameOriginPageAndCannotChangeGeneratedScopeOrQuery() = runBlocking {
        withGateway { gateway, server ->
            server.enqueue(response("""{"count":0,"next":null,"previous":null,"results":[]}"""))
            gateway.inventory(scope, "own query", "wood", server.url("/untrusted/path?page=2&q=other&category=metal").toString())
            val next = request(server); scoped(next)
            assertEquals("/api/v1/public/maker-space/inventory/", next.requestUrl!!.encodedPath)
            assertEquals("2", next.requestUrl!!.queryParameter("page")); assertEquals("own query", next.requestUrl!!.queryParameter("q"))
            assertEquals("wood", next.requestUrl!!.queryParameter("category"))
            for (url in listOf("https://outside.example/?page=2", "//outside.example/?page=2", "?page=0", "?page=-1", "?page=2&page=3", "?page=2147483648", "?page=2#fragment", "?q=no-page")) {
                try { withTimeout(5000) { gateway.inventory(scope, next = url) }; fail("Invalid page was accepted") }
                catch (_: IllegalArgumentException) { }
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun typedActivityAndBookingsPreserveAuthorityUnknownStatusesAndPerCurrencyDues() = runBlocking {
        withGateway { gateway, server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/api/v1/member/makerspaces/7/activity" -> response(activity())
                    "/api/v1/public/maker-space/my-bookings/" -> response(bookingPage())
                    else -> response("{}", 404)
                }
            }
            val overview = gateway.overview(scope)
            repeat(2) { scoped(request(server)) }
            assertEquals("21", overview.loans.single().requestId); assertTrue(overview.loans.single().overdue)
            assertEquals("2026-10-15T10:00+05:30", overview.loans.single().dueAt)
            assertFalse(overview.loans.single().canRenew); assertEquals("Visit the front desk.", overview.loans.single().renewalRefusal)
            assertEquals("pending_review_v2", overview.requests.single().status)
            assertEquals("31", overview.registrations.single().id); assertEquals("new_unknown_state", overview.registrations.single().status)
            assertNull(overview.registrations.single().checkinToken)
            assertEquals("12.50", overview.dues!!.amount); assertEquals(mapOf("INR" to "12.50", "USD" to "2.00"), overview.dues.outstandingByCurrency)
            assertEquals(BOOKING_ID, overview.bookings.items.single().id); assertEquals("?page=2", overview.bookings.next)
            assertEquals("confirmed", overview.bookings.items.single().status)
            assertTrue(overview.membershipActive); assertTrue(overview.waiverRequired); assertEquals("waiver_required", overview.restrictionCode)
            assertEquals("Loan due", overview.notices.single().title)
            assertEquals(PRINT_ID, overview.serviceRequests.first().id); assertNull(overview.serviceRequests.last().id)
            assertTrue(overview.term!!.blocksBorrowing)
        }
    }

    @Test fun unknownDuesCurrencyIsNotCoercedToAnEmptyMapOrZero() = runBlocking {
        withGateway { gateway, server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.requestUrl!!.encodedPath.endsWith("/activity")) {
                    response(activity(dues = """{"dues_amount":"12.50","outstanding_by_currency":null}"""))
                } else response(bookingPage())
            }
            val overview = gateway.overview(scope)
            assertEquals("12.50", overview.dues!!.amount); assertNull(overview.dues.outstandingByCurrency)
        }
    }

    @Test fun bookingPeriodAndPaginationAreGeneratedTypedParameters() = runBlocking {
        withGateway { gateway, server ->
            server.enqueue(response(bookingPage()))
            val page = gateway.bookings(scope, "past", "?page=3")
            assertEquals(BOOKING_ID, page.items.single().id)
            val request = request(server); scoped(request)
            assertEquals("past", request.requestUrl!!.queryParameter("period")); assertEquals("3", request.requestUrl!!.queryParameter("page"))
            try { gateway.bookings(scope, "arbitrary"); fail("Unknown period accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cardQRIsRemovedForInactiveFlagsAndNotIssuedWhileOtherRefusalsRemainErrors() = runBlocking {
        withGateway { gateway, server ->
            server.enqueue(response(card()))
            assertEquals("data:image/png;base64,fixture", gateway.card(scope).qrImage)
            scoped(request(server))
            for ((active, qrActive) in listOf(false to true, true to false, false to false)) {
                server.enqueue(response(card(active, qrActive)))
                assertNull(gateway.card(scope).qrImage); request(server)
            }
            server.enqueue(response("""{"detail":"No card."}""", 404))
            val missing = gateway.card(scope); assertTrue(missing.notIssued); assertNull(missing.qrImage); request(server)
            server.enqueue(response("""{"detail":"Card access was refused.","code":"forbidden"}""", 403))
            try { gateway.card(scope); fail("Refused card should not reuse a prior card") }
            catch (failure: ApiFailure) { assertEquals(403, failure.status) }
        }
    }

    @Test fun personalInboxReadAndReadAllUseExactGeneratedOperationsAndUnsafeLinksStayInInbox() = runBlocking {
        withGateway { gateway, server ->
            server.enqueue(response("""{"count":1,"next":"?page=2","previous":null,"results":[$INBOX]}"""))
            val page = gateway.inbox(scope, true)
            assertEquals("41", page.items.single().id); assertNull(page.items.single().link); assertNull(page.items.single().readAt)
            val list = request(server); scoped(list)
            assertEquals("/api/v1/notifications/inbox/7", list.requestUrl!!.encodedPath)
            assertEquals("true", list.requestUrl!!.queryParameter("unread")); assertEquals("1", list.requestUrl!!.queryParameter("page"))
            server.enqueue(response(INBOX))
            gateway.markRead(scope, "41")
            val one = request(server); scoped(one); assertEquals("POST", one.method); assertEquals("/api/v1/notifications/inbox/7/41/read", one.path); assertEquals(0L, one.bodySize)
            server.enqueue(response("""{"updated":1}"""))
            gateway.markAllRead(scope)
            val all = request(server); scoped(all); assertEquals("POST", all.method); assertEquals("/api/v1/notifications/inbox/7/read-all", all.path); assertEquals(0L, all.bodySize)
        }
    }

    @Test fun invalidActionIDsDoNotCallTheServerAndMutationRefusalRetainsRetryAfter() = runBlocking {
        withGateway { gateway, server ->
            for (id in listOf("0", "-1", "+1", " 1", "1.0", "anything", "2147483648")) {
                try { gateway.product(scope, id); fail("Bad product ID accepted") } catch (_: IllegalArgumentException) { }
                try { gateway.markRead(scope, id); fail("Bad inbox ID accepted") } catch (_: IllegalArgumentException) { }
            }
            assertEquals(0, server.requestCount)
            server.enqueue(response("""{"detail":"Please wait.","code":"throttled"}""", 429).setHeader("Retry-After", "120"))
            try { gateway.markAllRead(scope); fail("Failed mutation succeeded") }
            catch (failure: ApiFailure) { assertEquals(429, failure.status); assertEquals(120L, failure.retryAfterSeconds) }
        }
    }

    @Test fun publicPrefixedEventsAndSpacesKeepRealUUIDsDecimalsImagesAndAuthenticatedScope() = runBlocking {
        withGateway { gateway, server ->
            server.enqueue(response("[$EVENT]"))
            val event = gateway.publicEvents(scope).single()
            assertEquals(EVENT_ID, event.id); assertEquals("123.45", event.paymentAmount); assertEquals("INR", event.currency)
            assertEquals("https://images.example/event.png", event.imageUrl); assertTrue(event.registrationOpen)
            val events = request(server); assertEquals("/api/v1/public/maker-space/events/", events.path)
            scoped(events)
            server.enqueue(response("[$SPACE]"))
            val space = gateway.spaces(scope).single()
            assertEquals(SPACE_ID, space.id); assertEquals("bench", space.kind); assertEquals("approve", space.approvalMode)
            assertFalse(space.showAvailability)
            val spaces = request(server); assertEquals("/api/v1/public/maker-space/spaces/", spaces.path)
            scoped(spaces)
        }
    }

    private fun product(availability: String) = """{"id":19,"name":"Clamp","description":"Useful clamp","category_id":5,"category_name":"Woodwork","category_slug":"wood","borrowing_terms":"Wear eye protection.","availability":$availability,"image_url":"https://images.example/tool.png"}"""
    private fun bookingPage() = """{"count":1,"next":"?page=2","previous":null,"results":[{"public_token":"$BOOKING_ID","space_name":"Bench","timezone":"Asia/Kolkata","starts_at":"2026-10-16T10:00:00+05:30","ends_at":"2026-10-16T11:00:00+05:30","status":"confirmed","cancellation_cutoff_at":"2026-10-16T09:00:00+05:30","can_cancel":true,"confirmation_available":false}]}"""
    private fun activity(dues: String = """{"dues_amount":"12.50","outstanding_by_currency":{"INR":"12.50","USD":"2.00"}}""") = """{
      "active_hardware_loans":[{"request_id":21,"renewal_count":1,"can_renew":false,"renewal_refusal":"Visit the front desk.","label":"Clamp loan","checked_out_at":"2026-10-01T10:00:00+05:30","due_at":"2026-10-15T10:00:00+05:30","overdue":true}],
      "recent_presence_sessions":[],"currently_checked_in":false,"accountability":{"membership_active":true,"waiver_acceptance_required":true,"restriction_code":"waiver_required"},
      "request_history":[{"status":"pending_review_v2","created_at":"2026-10-01T10:00:00Z","item_count":2,"returned_quantity":0,"damaged_quantity":0,"missing_quantity":0}],
      "loan_history":[{"label":"Old clamp","checked_out_at":"2026-09-01T10:00:00Z","returned_at":"2026-09-02T10:00:00Z","due_at":null,"returned_late":false}],
      "event_registrations":[{"registration_id":31,"checkin_token":null,"event_title":"Make a shelf","starts_at":"2026-10-20T10:00:00Z","ends_at":"2026-10-20T11:00:00Z","status":"new_unknown_state","cancellation_cutoff_at":"2026-10-19T10:00:00Z","can_cancel":false,"confirmation_available":false,"waitlist_position":null,"feedback_available":false,"feedback_path":null,"certificate":null}],
      "membership_dues":$dues,"membership_term":{"plan_name":"Monthly","starts_at":"2026-10-01T00:00:00Z","ends_at":"2026-11-01T00:00:00Z","current":true,"blocks_borrowing":true},
      "notices":[{"level":"warning","event":"loan_due","title":"Loan due","body":"Return your clamp."}],
      "print_requests":[{"public_token":"$PRINT_ID","status":"queued","title":"Bracket","created_at":"2026-10-01T10:00:00Z","accepted_at":null,"started_at":null,"completed_at":null,"estimated_minutes":30,"queue_position":2,"queue_approved_ahead":1,"queue_awaiting_review_ahead":0}],
      "machine_service_requests":[{"machine_type":"laser","title":"Panel","status":"pending","created_at":"2026-10-01T10:00:00Z","queue_position":null}]
    }"""
    private fun card(active: Boolean = true, qrActive: Boolean = true) = """{"id":9,"card_number":1009,"printed_name":"Meera","membership_id":70,"is_active":$active,"photo_set":false,"photo_consent_at":null,"qr_active":$qrActive,"print_count":0,"last_printed_at":null,"issued_at":"2026-10-01T00:00:00Z","revoked_at":null,"revoked_reason":"","created_at":"2026-10-01T00:00:00Z","updated_at":"2026-10-01T00:00:00Z","qr_image":"data:image/png;base64,fixture"}"""

    companion object {
        private const val BOOKING_ID = "00000000-0000-0000-0000-000000000071"
        private const val EVENT_ID = "00000000-0000-0000-0000-000000000072"
        private const val SPACE_ID = "00000000-0000-0000-0000-000000000073"
        private const val PRINT_ID = "00000000-0000-0000-0000-000000000074"
        private val INBOX = """{"id":41,"feature":"hardware_requests","event":"approved","title":"Request approved","summary":"Your request is ready.","link":"https://outside.example/steal","read_at":null,"created_at":"2026-10-01T10:00:00Z"}"""
        private val EVENT = """{"registration_audience":"members","category":null,"tags":[],"ticket_types":[],"promo_codes_enabled":false,"ticket_selection_required":false,"public_token":"$EVENT_ID","title":"Make a shelf","description":"Build together.","starts_at":"2026-10-20T10:00:00Z","ends_at":"2026-10-20T11:00:00Z","location":"Workshop","location_kind":"indoor","custom_form":null,"capacity":12,"payment_amount":"123.45","currency":"INR","availability":"Available","registration_requires_approval":false,"effective_registration_cutoff_at":null,"registration_open":true,"image_url":"https://images.example/event.png","status":"published","organizers":[],"series":null,"participating_organizations":[],"show_registration_count":false}"""
        private val SPACE = """{"public_token":"$SPACE_ID","name":"Bench","kind":"bench","description":"A project bench.","capacity":2,"location":"Workshop","image_url":"","approval_mode":"approve","custom_form":null,"show_public_availability":false,"show_public_booker_names":false}"""
    }
}
