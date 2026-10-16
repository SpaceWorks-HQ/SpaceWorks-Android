package org.spaceworks.app.actions

import kotlinx.serialization.json.JsonElement
import org.spaceworks.app.member.MemberBooking
import org.spaceworks.app.member.MemberEvent
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.member.MemberSpace

/** Local immutable screen inputs/results; never serialized as transport DTOs. */
data class RequestLine(val productId: String, val quantity: Int)
data class SubmittedRequest(val publicToken: String, val status: String)
data class RequestedProduct(val name: String, val quantity: Int)
data class RequestStatus(val status: String, val createdAt: String, val items: List<RequestedProduct>)
data class LoanRenewalResult(val requestId: String, val dueAt: String, val renewalCount: Int)

/** Questions project the existing frontend customFormTypes.ts schema; unknown forms fail closed. */
data class ActionQuestion(val id: String, val label: String, val type: String, val required: Boolean, val options: List<String>)
data class ActionForm(val questions: List<ActionQuestion>, val supported: Boolean = true)
data class ActionSpace(val space: MemberSpace, val form: ActionForm)
data class ActionTicket(val id: String, val name: String, val amount: String, val audience: String, val spotsLeft: Int?)
data class ActionEvent(
    val event: MemberEvent, val form: ActionForm, val audience: String,
    val tickets: List<ActionTicket>, val promoEnabled: Boolean, val ticketRequired: Boolean,
)
data class ActionWindow(val startsAt: String, val endsAt: String)
data class ActionBookedWindow(val startsAt: String, val endsAt: String, val bookerName: String?)
/** Null booked windows mean undisclosed availability, not an empty/free calendar. */
data class ActionAvailability(
    val publicToken: String, val startsAt: String, val endsAt: String, val timezone: String,
    val opening: List<ActionWindow>, val booked: List<ActionBookedWindow>?,
)
data class ActionProfile(val displayName: String?, val firstName: String?, val lastName: String?, val phone: String?)
data class ActionPreference(
    val feature: String, val channel: String, val enabled: Boolean, val available: Boolean,
    val mandatoryEvents: List<String>, val dailyDigest: Boolean?,
)

interface MemberActionsGateway {
    suspend fun submitRequest(scope: MemberScope, purpose: String, lines: List<RequestLine>, idempotencyKey: String? = null): SubmittedRequest
    suspend fun requestStatus(scope: MemberScope, publicToken: String): RequestStatus
    suspend fun renew(scope: MemberScope, requestId: String): LoanRenewalResult
    suspend fun space(scope: MemberScope, publicToken: String): ActionSpace
    suspend fun availability(scope: MemberScope, publicToken: String, startsAtISO: String, endsAtISO: String): ActionAvailability
    suspend fun book(scope: MemberScope, publicToken: String, startsAtISO: String, endsAtISO: String, answers: Map<String, JsonElement>): String
    suspend fun reschedule(scope: MemberScope, bookingToken: String, startsAtISO: String, endsAtISO: String): MemberBooking
    suspend fun cancelBooking(scope: MemberScope, bookingToken: String): MemberBooking
    suspend fun event(scope: MemberScope, publicToken: String): ActionEvent
    suspend fun register(scope: MemberScope, eventToken: String, answers: Map<String, JsonElement>, phone: String? = null,
                         email: String? = null, inviteCode: String? = null, ticketId: String? = null, promoCode: String? = null): String
    suspend fun cancelRegistration(scope: MemberScope, registrationId: String): String
    /** Actual generated image/svg+xml bytes only. Caller must use a safe native renderer, never a WebView. */
    suspend fun registrationQR(scope: MemberScope, registrationId: String): ByteArray
    suspend fun profile(scope: MemberScope): ActionProfile
    suspend fun updateProfile(scope: MemberScope, value: ActionProfile): ActionProfile
    suspend fun preferences(scope: MemberScope): List<ActionPreference>
    suspend fun updatePreference(scope: MemberScope, feature: String, channel: String, enabled: Boolean, dailyDigest: Boolean? = null): List<ActionPreference>
}
