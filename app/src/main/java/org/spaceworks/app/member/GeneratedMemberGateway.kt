package org.spaceworks.app.member

import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.MobileCopyException
import org.spaceworks.app.platform.failure
import org.spaceworks.app.copy.CoreStrings
import org.spaceworks.app.copy.apiFailure

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.spaceworks.app.auth.requireBody
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.network.ApiSession
import org.spaceworks.app.network.Pagination
import org.spaceworks.app.platform.PushHint
import org.spaceworks.generated.api.MemberActivityApi
import org.spaceworks.generated.api.MemberBookingsApi
import org.spaceworks.generated.api.MemberProfileApi
import org.spaceworks.generated.api.PersonalNotificationsApi
import org.spaceworks.generated.api.PublicBookingsApi
import org.spaceworks.generated.api.PublicEventsApi
import org.spaceworks.generated.api.PublicInventoryApi
import org.spaceworks.generated.model.Inbox
import org.spaceworks.generated.model.PublicProduct
import java.util.Locale

/** Generated operations only. Each authenticated API instance captures this request's scope header. */
class GeneratedMemberGateway(private val session: ApiSession,private val text:MobileText=MobileText.ENGLISH) : MemberGateway {
    override suspend fun inventory(scope: MemberScope, query: String, category: String?, next: String?): MemberPage<InventoryItem> {
        val page = page(next)
        val body = session.scopedApi(PublicInventoryApi::class.java, scope.id).apiV1PublicInventoryList(
            makerspaceSlug = scope.slug, category = category?.takeIf { it.isNotBlank() }, page = page,
            q = query.takeIf { it.isNotBlank() }).requireBody()
        return MemberPage(body.results.map(::inventoryItem), body.next?.toString(), body.count)
    }

    override suspend fun categories(scope: MemberScope): List<InventoryCategory> =
        session.scopedApi(PublicInventoryApi::class.java, scope.id).apiV1PublicInventoryCategoriesList(scope.slug).requireBody()
            .map { InventoryCategory(it.id.toString(), it.name, it.slug, it.productCount) }

    override suspend fun product(scope: MemberScope, id: String): InventoryItem {
        val productId = positiveID(id)
        return inventoryItem(session.scopedApi(PublicInventoryApi::class.java, scope.id)
            .apiV1PublicInventoryRetrieve(productId, scope.slug).requireBody())
    }

    override suspend fun bookings(scope: MemberScope, period: String, next: String?): MemberPage<MemberBooking> {
        val page = page(next)
        val filter = when (period.lowercase(Locale.ROOT)) {
            "upcoming" -> MemberBookingsApi.PeriodApiV1PublicMyBookingsRetrieve.upcoming
            "past" -> MemberBookingsApi.PeriodApiV1PublicMyBookingsRetrieve.past
            else -> throw IllegalArgumentException(text(CoreStrings.CORE_CHOOSE_UPCOMING_OR_PAST_BOOKINGS))
        }
        val body = session.scopedApi(MemberBookingsApi::class.java, scope.id)
            .apiV1PublicMyBookingsRetrieve(scope.slug, page, filter).requireBody()
        return MemberPage(body.results.map {
            MemberBooking(it.publicToken.toString(), it.spaceName, it.timezone, it.startsAt.toString(), it.endsAt.toString(),
                it.status.value, it.canCancel, it.cancellationCutoffAt.toString())
        }, body.next?.toString(), body.count)
    }

    override suspend fun overview(scope: MemberScope): MemberOverview = coroutineScope {
        val activity = async { session.scopedApi(MemberActivityApi::class.java, scope.id)
            .apiV1MemberMakerspacesActivityRetrieve(scope.id).requireBody() }
        val upcoming = async { bookings(scope, "upcoming", null) }
        val body = activity.await()
        MemberOverview(
            notices = body.notices.orEmpty().map { MemberNoticeRow(it.level, it.event, it.title, it.body) },
            loans = body.activeHardwareLoans.map { MemberLoan(it.requestId.toString(), it.label, it.checkedOutAt.toString(),
                it.dueAt?.toString(), it.overdue, it.canRenew, it.renewalRefusal, it.renewalCount) },
            loanHistory = body.loanHistory.orEmpty().map { MemberLoanHistoryRow(it.label, it.checkedOutAt.toString(),
                it.returnedAt?.toString(), it.dueAt?.toString(), it.returnedLate) },
            requests = body.requestHistory.orEmpty().map { MemberRequestRow(it.status, it.createdAt.toString(), it.itemCount,
                it.returnedQuantity, it.damagedQuantity, it.missingQuantity) },
            bookings = upcoming.await(),
            registrations = body.eventRegistrations.orEmpty().map { MemberRegistration(it.registrationId.toString(), it.eventTitle,
                it.startsAt.toString(), it.endsAt.toString(), it.status, it.canCancel, it.cancellationCutoffAt.toString(),
                it.checkinToken?.toString(), it.waitlistPosition, it.feedbackAvailable, PushHint.safeInAppPath(it.feedbackPath)) },
            dues = body.membershipDues?.let { MemberDuesSummary(it.duesAmount, it.outstandingByCurrency?.toMap()) },
            term = body.membershipTerm?.let { MemberTermSummary(it.planName, it.startsAt.toString(), it.endsAt.toString(), it.current, it.blocksBorrowing) },
            membershipActive = body.accountability.membershipActive,
            waiverRequired = body.accountability.waiverAcceptanceRequired,
            restrictionCode = body.accountability.restrictionCode,
            currentlyCheckedIn = body.currentlyCheckedIn,
            serviceRequests = body.printRequests.orEmpty().map { MemberServiceRequest(it.publicToken.toString(), it.title, it.status,
                it.createdAt.toString(), "printing", it.queuePosition, it.estimatedMinutes) } +
                body.machineServiceRequests.orEmpty().map { MemberServiceRequest(null, it.title, it.status,
                    it.createdAt.toString(), it.machineType ?: "machine_service", it.queuePosition, null) },
        )
    }

    override suspend fun card(scope: MemberScope): MemberCard {
        val body = try { session.scopedApi(MemberProfileApi::class.java, scope.id)
            .apiV1MemberMakerspacesMemberCardRetrieve(scope.id).requireBody() }
        catch (failure: ApiFailure) {
            if (failure.status != 404) throw failure
            return MemberCard(null, null, "", null, isActive = false, qrActive = false, notIssued = true)
        }
        return MemberCard(body.id.toString(), body.cardNumber, body.printedName,
            body.qrImage?.takeIf { body.isActive && body.qrActive && it.isNotBlank() }, body.isActive, body.qrActive)
    }

    override suspend fun inbox(scope: MemberScope, unread: Boolean, next: String?): MemberPage<InboxItem> {
        val page = page(next)
        val body = session.scopedApi(PersonalNotificationsApi::class.java, scope.id)
            .apiV1NotificationsInboxRetrieve(scope.id, page = page, unread = unread).requireBody()
        return MemberPage(body.results.map(::inboxItem), body.next?.toString(), body.count)
    }

    override suspend fun markRead(scope: MemberScope, id: String) {
        val rowId = positiveID(id)
        session.scopedApi(PersonalNotificationsApi::class.java, scope.id).apiV1NotificationsInboxReadCreate(rowId, scope.id).requireBody()
    }

    override suspend fun markAllRead(scope: MemberScope) {
        session.scopedApi(PersonalNotificationsApi::class.java, scope.id).apiV1NotificationsInboxReadAllCreate(scope.id).requireBody()
    }

    override suspend fun publicEvents(scope: MemberScope): List<MemberEvent> =
        session.scopedApi(PublicEventsApi::class.java, scope.id).apiV1PublicEventsList(scope.slug).requireBody().map {
            MemberEvent(it.publicToken.toString(), it.title, it.description, it.startsAt.toString(), it.endsAt.toString(), it.location,
                it.paymentAmount.toPlainString(), it.currency, it.availability.value, it.registrationOpen, it.registrationRequiresApproval, it.imageUrl?.toString())
        }

    override suspend fun spaces(scope: MemberScope): List<MemberSpace> =
        session.scopedApi(PublicBookingsApi::class.java, scope.id).apiV1PublicSpacesList(scope.slug).requireBody().map {
            MemberSpace(it.publicToken.toString(), it.name, it.description, it.kind.value, it.capacity, it.location,
                it.imageUrl.takeIf(String::isNotBlank), it.approvalMode.value, it.showPublicAvailability)
        }

    /** Next links are never fetched. Validate origin, then pass only a positive page to a generated call. */
    private fun page(next: String?): Int {
        if (next == null) return 1
        require(next.length <= 4096 && next.none { it.isISOControl() || it == '\\' }) { text(CoreStrings.CORE_THE_NEXT_PAGE_IS_INVALID) }
        val url = requireNotNull(Pagination.next(session.server, next)) { text(CoreStrings.CORE_THE_NEXT_PAGE_IS_INVALID) }
        require(url.fragment == null && url.queryParameterValues("page").size == 1) { text(CoreStrings.CORE_THE_NEXT_PAGE_IS_INVALID) }
        return positiveID(requireNotNull(url.queryParameter("page")) { text(CoreStrings.CORE_THE_NEXT_PAGE_IS_INVALID) })
    }

    private fun positiveID(value: String): Int {
        require(value.isNotEmpty() && value.all { it in '0'..'9' }) { text(CoreStrings.CORE_THE_IDENTIFIER_IS_INVALID) }
        return requireNotNull(value.toIntOrNull()?.takeIf { it > 0 }) { text(CoreStrings.CORE_THE_IDENTIFIER_IS_INVALID) }
    }

    private fun inventoryItem(item: PublicProduct): InventoryItem {
        val stock = item.availability
        return InventoryItem(item.id.toString(), item.name, item.description, item.categoryId?.toString(), item.categoryName,
            item.categorySlug, item.borrowingTerms, item.imageUrl?.toString(), stock?.label?.value ?: text(CoreStrings.CORE_ASK_YOUR_MAKERSPACE_ABOUT_AVAILABILITY),
            stock?.mode?.value, stock?.takeIf { it.mode.value == "exact_count" }?.count?.takeIf { it >= 0 })
    }

    private fun inboxItem(item: Inbox) = InboxItem(item.id.toString(), item.title, item.summary, item.feature, item.event,
        PushHint.safeInAppPath(item.link), item.readAt?.toString(), item.createdAt.toString())
}
