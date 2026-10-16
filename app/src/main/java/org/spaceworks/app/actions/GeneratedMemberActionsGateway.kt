package org.spaceworks.app.actions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import org.spaceworks.app.auth.requireBody
import org.spaceworks.app.member.MemberBooking
import org.spaceworks.app.member.MemberEvent
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.member.MemberSpace
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.network.ApiSession
import org.spaceworks.generated.api.*
import org.spaceworks.generated.model.*
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Every route, body and response is generated from the pinned owner contract. */
class GeneratedMemberActionsGateway(private val session: ApiSession) : MemberActionsGateway {
    override suspend fun submitRequest(scope: MemberScope, purpose: String, lines: List<RequestLine>, idempotencyKey: String?): SubmittedRequest {
        require(purpose.length <= 500 && lines.size in 1..20 && lines.map { it.productId }.distinct().size == lines.size) { "Review the request and its items." }
        val items = lines.map { require(it.quantity in 1..99) { "Choose a quantity from 1 to 99." }; RequestItemInput(positiveID(it.productId), it.quantity) }
        require(items.map { it.productId }.distinct().size == items.size) { "Each product can appear only once in the request." }
        require(idempotencyKey == null || (idempotencyKey.isNotBlank() && idempotencyKey.length <= 256 && idempotencyKey.all { it in '!'..'~' })) { "The request reference is invalid." }
        val result = api(PublicRequestsApi::class.java, scope).apiV1PublicRequestsCreate(
            makerspaceSlug = scope.slug, requestSubmit = RequestSubmit(items = items, requestedFor = purpose), idempotencyKey = idempotencyKey).requireBody()
        return SubmittedRequest(result.publicToken.toString(), result.status)
    }
    override suspend fun requestStatus(scope: MemberScope, publicToken: String): RequestStatus {
        val result = api(PublicRequestsApi::class.java, scope).apiV1PublicRequestsStatusRetrieve(publicToken = uuid(publicToken)).requireBody()
        return RequestStatus(result.status, result.createdAt.toString(), result.items.map { RequestedProduct(it.productName, it.requestedQuantity) })
    }
    override suspend fun renew(scope: MemberScope, requestId: String): LoanRenewalResult {
        val result = api(LoansApi::class.java, scope).apiV1MemberMakerspacesLoansRenewCreate(id = positiveID(requestId), makerspaceId = scope.id, requestBody = emptyMap()).requireBody()
        return LoanRenewalResult(result.id.toString(), result.returnDueAt.toString(), result.renewalCount)
    }
    override suspend fun space(scope: MemberScope, publicToken: String): ActionSpace {
        val id = uuid(publicToken)
        val result = api(PublicBookingsApi::class.java, scope).apiV1PublicSpacesList(scope.slug).requireBody().firstOrNull { it.publicToken == id }
            ?: throw ApiFailure(404, "This space is unavailable. Refresh and try again.", "not_found", null)
        return ActionSpace(MemberSpace(result.publicToken.toString(), result.name, result.description, result.kind.value, result.capacity,
            result.location, result.imageUrl.takeIf(String::isNotBlank), result.approvalMode.value, result.showPublicAvailability), ActionForms.project(result.customForm))
    }
    override suspend fun availability(scope: MemberScope, publicToken: String, startsAtISO: String, endsAtISO: String): ActionAvailability {
        val (start, end) = interval(startsAtISO, endsAtISO)
        val result = api(PublicBookingsApi::class.java, scope).apiV1PublicSpacesAvailabilityRetrieve(
            makerspaceSlug = scope.slug, publicToken = uuid(publicToken), startsAt = start, endsAt = end).requireBody()
        return ActionAvailability(result.publicToken.toString(), result.startsAt.toString(), result.endsAt.toString(), result.timezone,
            result.openingIntervals.map { ActionWindow(it.startsAt.toString(), it.endsAt.toString()) },
            result.availability?.map { ActionBookedWindow(it.startsAt.toString(), it.endsAt.toString(), it.bookerName) })
    }
    override suspend fun book(scope: MemberScope, publicToken: String, startsAtISO: String, endsAtISO: String, answers: Map<String, JsonElement>): String {
        val (start, end) = interval(startsAtISO, endsAtISO)
        val result = api(PublicBookingsApi::class.java, scope).apiV1PublicSpacesBookCreate(makerspaceSlug = scope.slug,
            publicToken = uuid(publicToken), publicBookingInput = PublicBookingInput(startsAt = start, endsAt = end, customAnswers = answerObject(answers))).requireBody()
        return result.status.value
    }
    override suspend fun reschedule(scope: MemberScope, bookingToken: String, startsAtISO: String, endsAtISO: String): MemberBooking {
        val (start, end) = interval(startsAtISO, endsAtISO)
        return booking(api(MemberBookingsApi::class.java, scope).apiV1PublicMyBookingsRescheduleCreate(makerspaceSlug = scope.slug,
            publicToken = uuid(bookingToken), bookingReschedule = BookingReschedule(startsAt = start, endsAt = end)).requireBody())
    }
    override suspend fun cancelBooking(scope: MemberScope, bookingToken: String): MemberBooking = booking(
        api(MemberBookingsApi::class.java, scope).apiV1PublicMyBookingsCancelCreate(scope.slug, uuid(bookingToken), emptyMap()).requireBody())

    override suspend fun event(scope: MemberScope, publicToken: String): ActionEvent {
        val result = api(PublicEventsApi::class.java, scope).apiV1PublicEventsRetrieve(scope.slug, uuid(publicToken)).requireBody()
        val tickets = result.ticketTypes.map {
            val id = it.id?.takeIf { value -> value > 0 } ?: throw invalidForm()
            ActionTicket(id.toString(), it.name ?: throw invalidForm(), it.amount ?: throw invalidForm(), it.audience ?: throw invalidForm(), it.spotsLeft)
        }
        return ActionEvent(MemberEvent(result.publicToken.toString(), result.title, result.description, result.startsAt.toString(), result.endsAt.toString(),
            result.location, result.paymentAmount.toPlainString(), result.currency, result.availability.value, result.registrationOpen,
            result.registrationRequiresApproval, result.imageUrl?.toString()), ActionForms.project(result.customForm), result.registrationAudience.value,
            tickets, result.promoCodesEnabled, result.ticketSelectionRequired)
    }
    override suspend fun register(scope: MemberScope, eventToken: String, answers: Map<String, JsonElement>, phone: String?, email: String?,
                                  inviteCode: String?, ticketId: String?, promoCode: String?): String {
        require(phone == null || phone.length <= 64) { "Review the phone number." }
        require(inviteCode == null || inviteCode.length <= 128) { "Review the invitation code." }
        require(promoCode == null || promoCode.length <= 128) { "Review the promo code." }
        val result = api(PublicEventsApi::class.java, scope).apiV1PublicEventsRegisterCreate(makerspaceSlug = scope.slug, publicToken = uuid(eventToken),
            publicEventRegistrationInput = PublicEventRegistrationInput(customAnswers = answerObject(answers), phone = phone,
                email = email?.let { PublicEventRegistrationInputEmail.StringValue(it) }, inviteCode = inviteCode,
                ticketTypeId = ticketId?.let(::positiveID), promoCode = promoCode)).requireBody()
        return result.status.value
    }
    override suspend fun cancelRegistration(scope: MemberScope, registrationId: String): String =
        api(MemberEventsApi::class.java, scope).apiV1MemberMakerspacesEventRegistrationsCancelCreate(positiveID(registrationId), scope.id).requireBody().status.value

    override suspend fun registrationQR(scope: MemberScope, registrationId: String): ByteArray = coroutineScope {
        val id = positiveID(registrationId)
        suspendCancellableCoroutine { continuation ->
            val activeCall = AtomicReference<Call?>()
            val reader = launch(Dispatchers.IO) {
                try {
                    val service = session.scopedApi(MemberActivityApi::class.java, scope.id) { call ->
                        activeCall.set(call)
                        if (!continuation.isActive) call.cancel()
                    }
                    val body = service.apiV1MemberMakerspacesEventRegistrationsQrRetrieve(id, scope.id).requireBody()
                    val bytes = body.use {
                        val type = it.contentType()
                        require(type?.type == "image" && type.subtype == "svg+xml") { "The event check-in code is unavailable." }
                        require(it.contentLength() <= QR_LIMIT) { "The event check-in code is too large." }
                        val buffer = ByteArray(8192)
                        val output = ByteArrayOutputStream()
                        val input = it.byteStream()
                        while (true) {
                            val read = input.read(buffer, 0, minOf(buffer.size, QR_LIMIT - output.size() + 1))
                            if (read < 0) break
                            require(read <= QR_LIMIT - output.size()) { "The event check-in code is too large." }
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray().also { value -> require(value.isNotEmpty()) { "The event check-in code is unavailable." } }
                    }
                    continuation.resume(bytes)
                } catch (failure: Exception) {
                    continuation.resumeWithException(failure)
                } finally {
                    activeCall.set(null)
                }
            }
            // Call.cancel is thread-safe. The IO reader alone owns/closes the body, avoiding
            // Okio read/close races; coroutineScope waits for that cleanup before returning.
            continuation.invokeOnCancellation { activeCall.get()?.cancel(); reader.cancel() }
        }
    }
    override suspend fun profile(scope: MemberScope): ActionProfile = profile(
        api(MemberAccountApi::class.java, scope).apiV1MemberMakerspacesAccountProfileRetrieve(scope.id).requireBody())
    override suspend fun updateProfile(scope: MemberScope, value: ActionProfile): ActionProfile {
        require(value.displayName == null || value.displayName.length <= 200)
        require(value.firstName == null || value.firstName.length <= 150)
        require(value.lastName == null || value.lastName.length <= 150)
        require(value.phone == null || value.phone.length <= 32)
        return profile(api(MemberAccountApi::class.java, scope).apiV1MemberMakerspacesAccountProfilePartialUpdate(scope.id,
            PatchedAccountProfile(displayName = value.displayName, firstName = value.firstName, lastName = value.lastName, phone = value.phone)).requireBody())
    }
    override suspend fun preferences(scope: MemberScope): List<ActionPreference> = preferences(
        api(PersonalNotificationsApi::class.java, scope).apiV1NotificationsPreferencesRetrieve(scope.id).requireBody())
    override suspend fun updatePreference(scope: MemberScope, feature: String, channel: String, enabled: Boolean, dailyDigest: Boolean?): List<ActionPreference> {
        val family = requireNotNull(Feature9d8Enum.entries.firstOrNull { it.value == feature }) { "This notification family is unavailable." }
        val delivery = requireNotNull(Channel673Enum.entries.firstOrNull { it.value == channel }) { "This notification channel is unavailable." }
        require(dailyDigest == null || channel == "email") { "Daily digests apply to optional email messages." }
        return preferences(api(PersonalNotificationsApi::class.java, scope).apiV1NotificationsPreferencesPartialUpdate(scope.id,
            PatchedPreferenceWrite(feature = family, channel = delivery, enabled = enabled, dailyDigest = dailyDigest)).requireBody())
    }

    private fun <T> api(type: Class<T>, scope: MemberScope): T = session.scopedApi(type, scope.id)
    private fun booking(value: org.spaceworks.generated.model.MemberBooking) = MemberBooking(value.publicToken.toString(), value.spaceName, value.timezone,
        value.startsAt.toString(), value.endsAt.toString(), value.status.value, value.canCancel, value.cancellationCutoffAt.toString())
    private fun profile(value: AccountProfile) = ActionProfile(value.displayName, value.firstName, value.lastName, value.phone)
    private fun preferences(value: PreferenceResponse) = value.cells.map { ActionPreference(it.feature.value, it.channel.value, it.enabled, it.available, it.mandatoryEvents, it.dailyDigest) }
    private fun interval(start: String, end: String): Pair<OffsetDateTime, OffsetDateTime> {
        val from = OffsetDateTime.parse(start); val until = OffsetDateTime.parse(end)
        require(until.isAfter(from)) { "End time must be after start time." }; return from to until
    }
    private fun positiveID(value: String): Int {
        require(value.isNotEmpty() && value.all { it in '0'..'9' }) { "The identifier is invalid." }
        return requireNotNull(value.toIntOrNull()?.takeIf { it > 0 }) { "The identifier is invalid." }
    }
    private fun uuid(value: String): UUID {
        require(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(value)) { "The record reference is invalid." }
        return UUID.fromString(value)
    }
    private fun answerObject(answers: Map<String, JsonElement>): JsonObject? {
        require(answers.size <= 100 && answers.keys.all { it.isNotBlank() && it.length <= 256 }) { "Review the additional answers." }
        val result = JsonObject(answers)
        require(result.toString().toByteArray().size <= 128 * 1024) { "The additional answers are too large." }
        return result.takeIf { answers.isNotEmpty() }
    }
    private fun invalidForm() = ApiFailure(0, "This event form is unavailable. Refresh or contact your makerspace.", "invalid_event_form", null)
    companion object { private const val QR_LIMIT = 512 * 1024 }
}

/** Presentation rules project the established frontend JSON form; no handwritten transport model. */
object ActionForms {
    private val types = setOf("short_text", "paragraph", "number", "date", "single_choice", "multi_choice", "dropdown", "yes_no")
    private val choices = setOf("single_choice", "multi_choice", "dropdown")
    fun project(schema: JsonElement?): ActionForm {
        if (schema == null || schema == JsonNull) return ActionForm(emptyList())
        val rows = schema as? JsonArray ?: return ActionForm(emptyList(), false)
        if (rows.size > 100) return ActionForm(emptyList(), false)
        val questions = mutableListOf<ActionQuestion>()
        for (row in rows) {
            val objectValue = row as? JsonObject ?: return ActionForm(emptyList(), false)
            fun string(key: String) = (objectValue[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val id = string("id")?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return ActionForm(emptyList(), false)
            val label = string("label")?.takeIf { it.isNotBlank() && it.length <= 1000 } ?: return ActionForm(emptyList(), false)
            val type = string("type")?.takeIf { it in types } ?: return ActionForm(emptyList(), false)
            val required = (objectValue["required"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return ActionForm(emptyList(), false)
            val rawOptions = objectValue["options"] as? JsonArray ?: return ActionForm(emptyList(), false)
            if (rawOptions.size > 100) return ActionForm(emptyList(), false)
            val options = rawOptions.map { (it as? JsonPrimitive)?.takeIf { option -> option.isString }?.content ?: return ActionForm(emptyList(), false) }
            if (options.any { it.isBlank() || it.length > 1000 } || options.distinct().size != options.size || (type in choices && options.isEmpty())) return ActionForm(emptyList(), false)
            questions += ActionQuestion(id, label, type, required, options)
        }
        return if (questions.map { it.id }.distinct().size == questions.size) ActionForm(questions) else ActionForm(emptyList(), false)
    }
    fun errors(form: ActionForm, answers: Map<String, JsonElement>): Map<String, String> {
        if (!form.supported) return mapOf("_form" to "This form is unavailable. Refresh or contact your makerspace.")
        val errors = linkedMapOf<String, String>()
        for (question in form.questions) {
            val value = answers[question.id]
            val primitive = value as? JsonPrimitive
            val empty = value == null || value == JsonNull || (primitive?.isString == true && primitive.content.isEmpty()) || (value is JsonArray && value.isEmpty())
            if (empty) { if (question.required) errors[question.id] = "This question is required."; continue }
            val string = primitive?.takeIf { it.isString }?.content
            val valid = when (question.type) {
                "short_text" -> string != null && string.length <= 500
                "paragraph" -> string != null && string.length <= 5000
                "number" -> primitive != null && primitive.booleanOrNull == null && primitive.content.toBigDecimalOrNull() != null
                "date" -> string != null && runCatching { LocalDate.parse(string) }.isSuccess
                "yes_no" -> primitive != null && !primitive.isString && primitive.booleanOrNull != null
                "single_choice", "dropdown" -> string in question.options
                "multi_choice" -> value is JsonArray && value.all { entry -> entry is JsonPrimitive && entry.isString && entry.content in question.options } && value.distinct().size == value.size
                else -> false
            }
            if (!valid) errors[question.id] = if (question.type == "number") "Enter a valid number." else "Review this answer."
        }
        if (answers.keys.any { key -> form.questions.none { it.id == key } }) errors["_form"] = "The form changed. Review the additional questions."
        return errors
    }
}
