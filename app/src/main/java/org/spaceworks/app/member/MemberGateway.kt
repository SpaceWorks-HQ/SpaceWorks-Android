package org.spaceworks.app.member

/** Account-selected scope. Generated operations and the server still authorize every request. */
data class MemberScope(val id: Int, val slug: String) {
    init { require(id > 0 && slug.isNotBlank()) { "Choose a valid makerspace." } }
}

/** Immutable presentation state only. These classes are never serialized as API payloads. */
data class MemberPage<T>(val items: List<T>, val next: String?, val count: Int)
data class InventoryCategory(val id: String, val name: String, val slug: String, val productCount: Int)
data class InventoryItem(
    val id: String, val name: String, val description: String,
    val categoryId: String?, val categoryName: String?, val categorySlug: String?,
    val borrowingTerms: String, val imageUrl: String?, val availability: String,
    val availabilityMode: String?, val availableCount: Int?,
)
data class MemberLoan(
    val requestId: String, val label: String, val checkedOutAt: String, val dueAt: String?,
    val overdue: Boolean, val canRenew: Boolean, val renewalRefusal: String?, val renewalCount: Int,
)
data class MemberLoanHistoryRow(
    val label: String, val checkedOutAt: String, val returnedAt: String?, val dueAt: String?, val returnedLate: Boolean,
)
/** The actual history contract supplies no request identifier or action token. */
data class MemberRequestRow(
    val status: String, val createdAt: String, val itemCount: Int,
    val returnedQuantity: Int, val damagedQuantity: Int, val missingQuantity: Int,
)
/** id is the actual booking public_token. The contract does not supply the underlying space ID. */
data class MemberBooking(
    val id: String, val spaceName: String, val timezone: String, val startsAt: String, val endsAt: String,
    val status: String, val canCancel: Boolean, val cancellationCutoffAt: String,
)
/** id is registration_id; checkinToken is a ticket token, not an event public_token. */
data class MemberRegistration(
    val id: String, val title: String, val startsAt: String, val endsAt: String, val status: String,
    val canCancel: Boolean, val cancellationCutoffAt: String, val checkinToken: String?,
    val waitlistPosition: Int?, val feedbackAvailable: Boolean, val feedbackPath: String?,
)
data class MemberNoticeRow(val level: String, val event: String, val title: String, val body: String)
/** Null per-currency balances mean unknown, never a fabricated zero balance. */
data class MemberDuesSummary(val amount: String, val outstandingByCurrency: Map<String, String>?)
data class MemberTermSummary(val planName: String, val startsAt: String, val endsAt: String, val current: Boolean, val blocksBorrowing: Boolean)
/** Printing has a real public_token; machine service history has no identifier. */
data class MemberServiceRequest(
    val id: String?, val title: String, val status: String, val createdAt: String, val kind: String,
    val queuePosition: Int?, val estimatedMinutes: Int?,
)
data class MemberOverview(
    val notices: List<MemberNoticeRow>, val loans: List<MemberLoan>, val loanHistory: List<MemberLoanHistoryRow>,
    val requests: List<MemberRequestRow>, val bookings: MemberPage<MemberBooking>, val registrations: List<MemberRegistration>,
    val dues: MemberDuesSummary?, val term: MemberTermSummary?, val membershipActive: Boolean,
    val waiverRequired: Boolean, val restrictionCode: String?, val currentlyCheckedIn: Boolean,
    val serviceRequests: List<MemberServiceRequest>,
)
/** qrImage is null whenever either active flag is false; clear prior screen state before every fetch. */
data class MemberCard(
    val id: String?, val cardNumber: Int?, val printedName: String, val qrImage: String?,
    val isActive: Boolean, val qrActive: Boolean, val notIssued: Boolean = false,
)
data class InboxItem(
    val id: String, val title: String, val summary: String, val feature: String, val event: String,
    val link: String?, val readAt: String?, val createdAt: String,
)
/** id is the published event UUID, suitable for its real generated detail/registration operations. */
data class MemberEvent(
    val id: String, val title: String, val description: String, val startsAt: String, val endsAt: String,
    val location: String, val paymentAmount: String, val currency: String, val availability: String,
    val registrationOpen: Boolean, val requiresApproval: Boolean, val imageUrl: String?,
)
data class MemberSpace(
    val id: String, val name: String, val description: String, val kind: String, val capacity: Int,
    val location: String, val imageUrl: String?, val approvalMode: String, val showAvailability: Boolean,
)

interface MemberGateway {
    suspend fun inventory(scope: MemberScope, query: String = "", category: String? = null, next: String? = null): MemberPage<InventoryItem>
    suspend fun categories(scope: MemberScope): List<InventoryCategory>
    suspend fun product(scope: MemberScope, id: String): InventoryItem
    suspend fun overview(scope: MemberScope): MemberOverview
    suspend fun bookings(scope: MemberScope, period: String = "upcoming", next: String? = null): MemberPage<MemberBooking>
    suspend fun card(scope: MemberScope): MemberCard
    suspend fun inbox(scope: MemberScope, unread: Boolean = false, next: String? = null): MemberPage<InboxItem>
    suspend fun markRead(scope: MemberScope, id: String)
    suspend fun markAllRead(scope: MemberScope)
    suspend fun publicEvents(scope: MemberScope): List<MemberEvent>
    suspend fun spaces(scope: MemberScope): List<MemberSpace>
}
