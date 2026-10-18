package org.spaceworks.app.actions

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.network.ApiSession
import org.spaceworks.app.network.MemoryTokenStore
import org.spaceworks.app.network.TokenPair
import java.util.concurrent.TimeUnit

class MemberActionsGatewayTest {
    private val scope = MemberScope(7, "maker-space")
    private val start = "2026-10-20T10:00:00+05:30"
    private val end = "2026-10-20T11:00:00+05:30"
    private suspend fun withGateway(block: suspend (GeneratedMemberActionsGateway, MockWebServer) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val tokens = MemoryTokenStore().apply { replace(TokenPair("member-access", "member-refresh")) }
            block(GeneratedMemberActionsGateway(ApiSession(server.url("/"), tokens) { "999" }), server)
        }
    }
    private fun response(value: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(value)
    private fun request(server: MockWebServer): RecordedRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).also {
        assertEquals("Bearer member-access", it.getHeader("Authorization")); assertEquals("7", it.getHeader("X-Makerspace-Id"))
        for (name in listOf("Cookie", "Origin", "Referer", "X-Nonce", "X-Publishable-Key")) assertNull(name, it.getHeader(name))
    }
    private fun body(request: RecordedRequest) = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
    private suspend fun invalid(block: suspend () -> Unit) {
        try { block(); fail("Invalid input called the API") } catch (_: IllegalArgumentException) { }
    }

    @Test fun cartSubmitUsesGeneratedItemsOnlyAndReturnsTheActualTrackingToken() = runBlocking {
        withGateway { gateway, server ->
            server.enqueue(response("""{"public_token":"$REQUEST","status":"pending"}""", 201))
            val result = gateway.submitRequest(scope, "Build a shelf", listOf(RequestLine("19", 2)), "reviewed-cart-attempt")
            assertEquals(SubmittedRequest(REQUEST, "pending"), result)
            val request = request(server)
            assertEquals("POST", request.method); assertEquals("/api/v1/public/maker-space/requests", request.path)
            assertEquals("reviewed-cart-attempt", request.getHeader("Idempotency-Key"))
            val json = body(request)
            assertEquals(setOf("items", "requested_for"), json.keys)
            assertEquals(JsonPrimitive("Build a shelf"), json["requested_for"])
            assertEquals(Json.parseToJsonElement("""[{"product_id":19,"quantity":2}]"""), json["items"])
        }
    }

    @Test fun cartRejectsInvalidQuantitiesDuplicateProductsAndOversizedPurposeBeforeTransport() = runBlocking {
        withGateway { gateway, server ->
            for (quantity in listOf(-1, 0, 100)) invalid { gateway.submitRequest(scope, "Purpose", listOf(RequestLine("19", quantity))) }
            invalid { gateway.submitRequest(scope, "Purpose", emptyList()) }
            invalid { gateway.submitRequest(scope, "Purpose", listOf(RequestLine("19", 1), RequestLine("19", 1))) }
            invalid { gateway.submitRequest(scope, "Purpose", listOf(RequestLine("19", 1), RequestLine("019", 1))) }
            invalid { gateway.submitRequest(scope, "x".repeat(501), listOf(RequestLine("19", 1))) }
            invalid { gateway.submitRequest(scope, "Purpose", listOf(RequestLine("0", 1))) }
            invalid { gateway.submitRequest(scope, "Purpose", listOf(RequestLine("19", 1)), "bad\r\nkey") }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun trackingAndRenewalUseRealReferencesWithoutInventingRequestHistoryIDs() = runBlocking {
        withGateway { gateway, server ->
            server.enqueue(response("""{"status":"partially_returned","created_at":"2026-10-16T10:00:00Z","items":[{"product_name":"Clamp","requested_quantity":2}]}"""))
            val status = gateway.requestStatus(scope, REQUEST)
            assertEquals("partially_returned", status.status); assertEquals(RequestedProduct("Clamp", 2), status.items.single())
            assertEquals("/api/v1/public/requests/$REQUEST/status", request(server).path)
            server.enqueue(response("""{"id":21,"return_due_at":"2026-10-25T10:00:00Z","renewal_count":2}"""))
            val result = gateway.renew(scope, "21")
            assertEquals("21", result.requestId); assertEquals(2, result.renewalCount)
            val renew = request(server)
            assertEquals("POST", renew.method); assertEquals("/api/v1/member/makerspaces/7/loans/21/renew", renew.path); assertEquals(JsonObject(emptyMap()), body(renew))
        }
    }

    @Test fun renewalRefusalAndRateLimitPreserveServerCopyCodeAndRetryDeadline() = runBlocking {
        withGateway { gateway, server ->
            server.enqueue(response("""{"detail":"Return this item at the desk.","code":"renewal_limit"}""", 409))
            try { gateway.renew(scope, "21"); fail("Refused renewal succeeded") }
            catch (failure: ApiFailure) { assertEquals(409, failure.status); assertEquals("renewal_limit", failure.code); assertEquals("Return this item at the desk.", failure.detail) }
            request(server)
            server.enqueue(response("""{"detail":"Please wait.","code":"throttled"}""", 429).setHeader("Retry-After", "90"))
            try { gateway.cancelRegistration(scope, "31"); fail("Throttled cancellation succeeded") }
            catch (failure: ApiFailure) { assertEquals(429, failure.status); assertEquals(90L, failure.retryAfterSeconds) }
        }
    }

    @Test fun spaceDetailsKeepActualQuestionsAndUnknownAvailabilityIsNotFreeTime() = runBlocking {
        withGateway { gateway, server ->
            server.enqueue(response("[$SPACE_JSON]"))
            val space = gateway.space(scope, SPACE)
            assertEquals(SPACE, space.space.id); assertEquals("approve", space.space.approvalMode)
            assertTrue(space.form.supported); assertEquals("safety", space.form.questions.single().id)
            assertEquals("yes_no", space.form.questions.single().type); assertTrue(space.form.questions.single().required)
            assertEquals("/api/v1/public/maker-space/spaces/", request(server).path)
            server.enqueue(response(availability("null")))
            val result = gateway.availability(scope, SPACE, start, end)
            assertNull(result.booked); assertEquals("Asia/Kolkata", result.timezone); assertEquals(1, result.opening.size)
            val query = request(server)
            assertEquals("/api/v1/public/maker-space/spaces/$SPACE/availability/", query.requestUrl!!.encodedPath)
            assertEquals("2026-10-20T10:00+05:30", query.requestUrl!!.queryParameter("starts_at"))
            assertEquals("2026-10-20T11:00+05:30", query.requestUrl!!.queryParameter("ends_at"))
        }
    }

    @Test fun availabilityPreservesOpeningWindowsBookerPrivacyAndOffsets() = runBlocking {
        withGateway { gateway, server ->
            server.enqueue(response(availability("""[{"starts_at":"2026-10-20T05:00:00Z","ends_at":"2026-10-20T05:15:00Z","booker_name":null}]""")))
            val result = gateway.availability(scope, SPACE, start, end)
            assertEquals(1, result.booked!!.size); assertNull(result.booked.single().bookerName)
            assertEquals("2026-10-20T05:00Z", result.booked.single().startsAt)
        }
    }

    @Test fun bookingUsesGeneratedTimesAndCustomAnswersAndOnlyClaimsTheReturnedStatus() = runBlocking {
        withGateway { gateway, server ->
            server.enqueue(response("""{"status":"pending"}""", 201))
            assertEquals("pending", gateway.book(scope, SPACE, start, end, mapOf("safety" to JsonPrimitive(false), "people" to JsonPrimitive(0))))
            val request = request(server); assertEquals("POST", request.method); assertEquals("/api/v1/public/maker-space/spaces/$SPACE/book/", request.path)
            val json = body(request)
            assertEquals(setOf("starts_at", "ends_at", "custom_answers"), json.keys)
            assertEquals(JsonPrimitive(false), json["custom_answers"]!!.jsonObject["safety"])
            assertEquals(JsonPrimitive(0), json["custom_answers"]!!.jsonObject["people"])
            assertFalse(json.containsKey("website")); assertFalse(json.containsKey("certification_override_reason"))
        }
    }

    @Test fun bookingCancellationAndRescheduleUseActualBookingUUIDAndAuthoritativeRows() = runBlocking {
        withGateway { gateway, server ->
            server.enqueue(response(booking("confirmed")))
            val moved = gateway.reschedule(scope, BOOKING, start, end)
            assertEquals(BOOKING, moved.id); assertEquals("Asia/Kolkata", moved.timezone)
            val move = request(server); assertEquals("/api/v1/public/maker-space/my-bookings/$BOOKING/reschedule/", move.path)
            assertEquals(setOf("starts_at", "ends_at"), body(move).keys)
            server.enqueue(response(booking("cancelled")))
            val cancelled = gateway.cancelBooking(scope, BOOKING)
            assertEquals("cancelled", cancelled.status)
            val cancel = request(server); assertEquals("POST", cancel.method); assertEquals(JsonObject(emptyMap()), body(cancel))
            assertEquals("/api/v1/public/maker-space/my-bookings/$BOOKING/cancel/", cancel.path)
        }
    }

    @Test fun eventDetailCarriesPublishedTicketAudienceFormAndPromoMetadata() = runBlocking {
        withGateway { gateway, server ->
            server.enqueue(response(EVENT_JSON))
            val event = gateway.event(scope, EVENT)
            assertEquals(EVENT, event.event.id); assertEquals("invite", event.audience)
            assertEquals("123.45", event.event.paymentAmount); assertTrue(event.promoEnabled); assertTrue(event.ticketRequired)
            assertEquals(ActionTicket("15", "Member ticket", "100.00", "members", 2), event.tickets.single())
            assertTrue(event.form.supported); assertEquals("safety", event.form.questions.single().id)
            assertEquals("/api/v1/public/maker-space/events/$EVENT/", request(server).path)
        }
    }

    @Test fun registrationSerializesRealOptionalContactTicketAndCodesWithoutFakeRegistrationID() = runBlocking {
        withGateway { gateway, server ->
            server.enqueue(response("""{"status":"waitlisted"}""", 201))
            val status = gateway.register(scope, EVENT, mapOf("safety" to JsonPrimitive(false)), "+919999999999", "meera@example.com", "invitation", "15", "MAKER")
            assertEquals("waitlisted", status)
            val request = request(server); assertEquals("/api/v1/public/maker-space/events/$EVENT/register/", request.path)
            val json = body(request)
            assertEquals(setOf("custom_answers", "phone", "email", "invite_code", "ticket_type_id", "promo_code"), json.keys)
            assertEquals(JsonPrimitive("meera@example.com"), json["email"]); assertEquals(JsonPrimitive(15), json["ticket_type_id"])
            assertEquals(JsonPrimitive(false), json["custom_answers"]!!.jsonObject["safety"])
            assertFalse(json.containsKey("website")); assertFalse(json.containsKey("registration_id")); assertFalse(json.containsKey("payment_status"))
        }
    }

    @Test fun registrationCancellationUsesActualNumericRegistrationAndQRIsGeneratedSVG() = runBlocking {
        withGateway { gateway, server ->
            server.enqueue(response("""{"status":"cancelled"}"""))
            assertEquals("cancelled", gateway.cancelRegistration(scope, "31"))
            val cancel = request(server); assertEquals("/api/v1/member/makerspaces/7/event-registrations/31/cancel/", cancel.path); assertEquals(0L, cancel.bodySize)
            val svg = """<svg xmlns="http://www.w3.org/2000/svg"><path d="M0 0h1v1z"/></svg>"""
            server.enqueue(MockResponse().setHeader("Content-Type", "image/svg+xml").setBody(svg))
            assertArrayEquals(svg.toByteArray(), gateway.registrationQR(scope, "31"))
            assertEquals("/api/v1/member/makerspaces/7/event-registrations/31/qr", request(server).path)
        }
    }

    @Test fun qrRejectsHtmlAndOversizedImagesInsteadOfReturningAnActionableCode() = runBlocking {
        withGateway { gateway, server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Sign in</html>"))
            invalid { gateway.registrationQR(scope, "31") }
            server.enqueue(MockResponse().setHeader("Content-Type", "image/svg+xml").setBody(Buffer().write(ByteArray(512 * 1024 + 1))))
            invalid { gateway.registrationQR(scope, "31") }
        }
    }

    @Test fun unknownLengthStreamCannotGrowBeyondTheTicketByteLimit() = runBlocking {
        withGateway { gateway, server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "image/svg+xml")
                .setChunkedBody(Buffer().write(ByteArray(512 * 1024 + 1)), 8192))
            invalid { gateway.registrationQR(scope, "31") }
            assertEquals("/api/v1/member/makerspaces/7/event-registrations/31/qr", request(server).path)
        }
    }

    @Test fun cancellingDeliveredStreamingTicketInterruptsItsSlowBodyWithoutWaitingForTheTail() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "image/svg+xml")
                .setBody("<svg>" + "x".repeat(4096) + "</svg>").throttleBody(1, 1, TimeUnit.SECONDS))
            val tokens = MemoryTokenStore().apply { replace(TokenPair("member-access", "member-refresh")) }
            val session = ApiSession(server.url("/"), tokens) { "999" }
            val gateway = GeneratedMemberActionsGateway(session)
            val loading = async(Dispatchers.IO) { gateway.registrationQR(scope, "31") }
            request(server)
            // Retrofit's @Streaming callback returns after delivering headers/body ownership.
            // An unstreamed converter would remain in the dispatcher while consuming the tail.
            withTimeout(5000) {
                while (session.client.dispatcher.runningCallsCount() != 0) delay(5)
            }
            assertFalse("The slow ticket must still be reading", loading.isCompleted)
            withTimeout(3000) { loading.cancelAndJoin() }
            assertTrue(loading.isCancelled)
        }
    }

    @Test fun accountProfileUsesOnlyContactFieldsAndPatchPreservesUneditedNullFields() = runBlocking {
        withGateway { gateway, server ->
            server.enqueue(response("""{"display_name":"Meera","phone":"+919999999999"}"""))
            val before = gateway.profile(scope)
            assertNull(before.firstName); assertEquals("Meera", before.displayName)
            assertEquals("/api/v1/member/makerspaces/7/account/profile", request(server).path)
            server.enqueue(response("""{"display_name":"Meera Rao","first_name":"Meera","last_name":"Rao","phone":""}"""))
            val after = gateway.updateProfile(scope, ActionProfile("Meera Rao", null, null, ""))
            assertEquals("Rao", after.lastName)
            val patch = request(server); assertEquals("PATCH", patch.method)
            assertEquals(Json.parseToJsonElement("""{"display_name":"Meera Rao","phone":""}"""), body(patch))
        }
    }

    @Test fun preferenceUpdatesPreserveAvailabilityMandatoryEventsAndReturnedDigestState() = runBlocking {
        withGateway { gateway, server ->
            server.enqueue(response(preferences(true)))
            val before = gateway.preferences(scope).single()
            assertFalse(before.available); assertTrue(before.enabled); assertEquals(listOf("account_recovery"), before.mandatoryEvents)
            assertNull(before.dailyDigest)
            assertEquals("/api/v1/notifications/preferences/7", request(server).path)
            server.enqueue(response(preferences(false)))
            val after = gateway.updatePreference(scope, "events", "email", false, true).single()
            assertFalse(after.enabled); assertEquals(listOf("account_recovery"), after.mandatoryEvents)
            val patch = request(server); assertEquals("PATCH", patch.method)
            assertEquals(Json.parseToJsonElement("""{"feature":"events","channel":"email","enabled":false,"daily_digest":true}"""), body(patch))
            val count = server.requestCount
            invalid { gateway.updatePreference(scope, "invented", "email", true) }
            invalid { gateway.updatePreference(scope, "events", "telegram", true) }
            invalid { gateway.updatePreference(scope, "events", "native_push", true, true) }
            assertEquals(count, server.requestCount)
        }
    }

    @Test fun invalidReferencesAndIntervalsNeverReachGeneratedOperations() = runBlocking {
        withGateway { gateway, server ->
            invalid { gateway.cancelRegistration(scope, BOOKING) }
            invalid { gateway.renew(scope, "0") }
            invalid { gateway.requestStatus(scope, "1-1-1-1-1") }
            invalid { gateway.cancelBooking(scope, "https://outside.example") }
            invalid { gateway.book(scope, SPACE, end, start, emptyMap()) }
            invalid { gateway.register(scope, EVENT, emptyMap(), ticketId = "0") }
            invalid { gateway.updateProfile(scope, ActionProfile("x".repeat(201), null, null, null)) }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun customQuestionsSupportEveryPublishedTypeAndPreserveFalseAndZero() {
        val types = listOf("short_text", "paragraph", "number", "date", "single_choice", "multi_choice", "dropdown", "yes_no")
        val schema = JsonArray(types.map { type -> buildJsonObject {
            put("id", type); put("label", type); put("type", type); put("required", true)
            put("options", JsonArray(if (type in setOf("single_choice", "multi_choice", "dropdown")) listOf(JsonPrimitive("A"), JsonPrimitive("B")) else emptyList()))
        } })
        val form = ActionForms.project(schema)
        assertTrue(form.supported); assertEquals(8, form.questions.size)
        val answers = mapOf("short_text" to JsonPrimitive("Maker"), "paragraph" to JsonPrimitive("A project"), "number" to JsonPrimitive(0),
            "date" to JsonPrimitive("2026-10-20"), "single_choice" to JsonPrimitive("A"), "multi_choice" to JsonArray(listOf(JsonPrimitive("B"))),
            "dropdown" to JsonPrimitive("A"), "yes_no" to JsonPrimitive(false))
        assertTrue(ActionForms.errors(form, answers).isEmpty())
        assertEquals(8, ActionForms.errors(form, emptyMap()).size)
        assertTrue(ActionForms.errors(form, answers + ("yes_no" to JsonPrimitive("false"))).containsKey("yes_no"))
        assertTrue(ActionForms.errors(form, answers + ("number" to JsonPrimitive("NaN"))).containsKey("number"))
        assertTrue(ActionForms.errors(form, answers + ("multi_choice" to JsonArray(listOf(JsonPrimitive("C"))))).containsKey("multi_choice"))
        assertTrue(ActionForms.errors(form, answers + ("date" to JsonPrimitive("2026-02-30"))).containsKey("date"))
        assertTrue(ActionForms.errors(form, answers + ("unknown" to JsonPrimitive("answer"))).containsKey("_form"))
    }

    @Test fun unsupportedOrMalformedFormCannotSilentlyDropARequiredQuestion() {
        assertTrue(ActionForms.project(null).supported)
        for (schema in listOf("{}", """[{"id":"q","label":"Question","type":"signature","required":true,"options":[]}]""",
            """[{"id":"q","label":"Question","type":"short_text","options":[]}]""",
            """[{"id":"q","label":"Question","type":"dropdown","required":true,"options":[1]}]""",
            "[$QUESTION,$QUESTION]")) {
            val form = ActionForms.project(Json.parseToJsonElement(schema))
            assertFalse(form.supported); assertTrue(ActionForms.errors(form, emptyMap()).containsKey("_form"))
        }
    }

    private fun availability(rows: String) = """{"public_token":"$SPACE","starts_at":"2026-10-20T04:30:00Z","ends_at":"2026-10-20T05:30:00Z","timezone":"Asia/Kolkata","opening_intervals":[{"starts_at":"2026-10-20T04:00:00Z","ends_at":"2026-10-20T12:00:00Z"}],"availability":$rows}"""
    private fun booking(status: String) = """{"public_token":"$BOOKING","space_name":"Studio","timezone":"Asia/Kolkata","starts_at":"2026-10-20T04:30:00Z","ends_at":"2026-10-20T05:30:00Z","status":"$status","cancellation_cutoff_at":"2026-10-19T04:30:00Z","can_cancel":false,"confirmation_available":false}"""
    private fun preferences(enabled: Boolean) = """{"cells":[{"feature":"events","channel":"email","enabled":$enabled,"available":false,"mandatory_events":["account_recovery"]}]}"""
    companion object {
        private const val REQUEST = "00000000-0000-0000-0000-000000000081"
        private const val SPACE = "00000000-0000-0000-0000-000000000082"
        private const val BOOKING = "00000000-0000-0000-0000-000000000083"
        private const val EVENT = "00000000-0000-0000-0000-000000000084"
        private const val QUESTION = """{"id":"safety","label":"Read the safety guide?","type":"yes_no","required":true,"options":[]}"""
        private val SPACE_JSON = """{"public_token":"$SPACE","name":"Studio","kind":"meeting","description":"A quiet room.","capacity":4,"location":"Workshop","image_url":"","approval_mode":"approve","custom_form":[$QUESTION],"show_public_availability":false,"show_public_booker_names":false}"""
        private val EVENT_JSON = """{"registration_audience":"invite","category":null,"tags":[],"ticket_types":[{"id":15,"name":"Member ticket","amount":"100.00","audience":"members","spots_left":2}],"promo_codes_enabled":true,"ticket_selection_required":true,"public_token":"$EVENT","title":"Build a shelf","description":"Make together.","starts_at":"2026-10-20T04:30:00Z","ends_at":"2026-10-20T05:30:00Z","location":"Workshop","location_kind":"indoor","custom_form":[$QUESTION],"capacity":12,"payment_amount":"123.45","currency":"INR","availability":"Available","registration_requires_approval":true,"effective_registration_cutoff_at":null,"registration_open":true,"image_url":null,"status":"published","organizers":[],"series":null,"participating_organizations":[],"show_registration_count":false}"""
    }
}
