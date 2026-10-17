package org.spaceworks.app.notifications

import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.MobileCopyException
import org.spaceworks.app.platform.failure
import org.spaceworks.app.copy.CoreStrings
import org.spaceworks.app.copy.apiFailure

import org.spaceworks.app.member.*
import org.spaceworks.app.actions.*

/** Executes read-only navigation after LinkStateHolder has freshly authorized a membership.
 * No request/booking/payment/attendance mutation is triggered by a link. */
class MemberRouteApplier(private val reads:MemberStateHolder,private val actions:MemberActionsStateHolder,
    private val onTab:(Int)->Unit,private val onHomeExtra:(String)->Unit,
    private val onPayments:((String?)->Unit)?=null,private val text:MobileText=MobileText.ENGLISH) {
    constructor(reads:MemberStateHolder,actions:MemberActionsStateHolder,onTab:(Int)->Unit,onHomeExtra:(String)->Unit,onPayments:(String?)->Unit)
        :this(reads,actions,onTab,onHomeExtra,onPayments,MobileText.ENGLISH)

    suspend fun open(value:AuthorizedNavigation,scope:MemberScope,current:()->Boolean):String? {
        fun valid()=current() && reads.state.value.scope==scope && actions.state.value.scope==scope && value.destination.makerspaceId==scope.id
        if(!valid())return null
        val route=value.destination
        if(value.notification) {
            reads.refreshInbox()
            if(!valid())return null
            val inbox=reads.state.value.inbox
            if(inbox.error!=null || inbox.value==null){onTab(3);return text(CoreStrings.CORE_COULDN_T_REFRESH_THIS_UPDATE_TRY_AGAIN_FROM_INBOX)}
        }
        if(!valid())return null
        if(actions.state.value.busy || actions.state.value.route!=null)return text(CoreStrings.CORE_FINISH_YOUR_CURRENT_ACTION_BEFORE_OPENING_THIS_UPDATE)
        return when(route.section) {
            MobileSection.HOME->{onTab(0);reads.refreshOverview();null}
            MobileSection.INBOX->{onTab(3);if(!value.notification)reads.refreshInbox();null}
            MobileSection.PROFILE->{onTab(4);null}
            MobileSection.INVENTORY->{onTab(1);if(route.resourceId!=null)reads.selectProduct(route.resourceId)else {reads.closeProduct();reads.refreshInventory()};null}
            MobileSection.ACTIVITY,MobileSection.BOOKINGS,MobileSection.REGISTRATION->{
                onTab(2);reads.refreshOverview()
                if(!valid())return null
                if(actions.state.value.busy || actions.state.value.route!=null)return text(CoreStrings.CORE_FINISH_YOUR_CURRENT_ACTION_BEFORE_OPENING_THIS_UPDATE)
                val overview=reads.state.value.overview
                val rows=overview.value
                if(overview.error!=null || rows==null)return text(CoreStrings.CORE_COULDN_T_REFRESH_THIS_UPDATE_TRY_AGAIN_FROM_ACTIVITY)
                if(route.resourceId==null)return null
                when(route.section) {
                    MobileSection.BOOKINGS->{val booking=rows.bookings.items.firstOrNull {it.id==route.resourceId};if(booking==null)text(CoreStrings.CORE_THIS_BOOKING_IS_NO_LONGER_AVAILABLE_REFRESH_ACTIVITY) else {actions.openBooking(booking);null}}
                    MobileSection.REGISTRATION->{val registration=rows.registrations.firstOrNull {it.id==route.resourceId};if(registration==null)text(CoreStrings.CORE_THIS_REGISTRATION_IS_NO_LONGER_AVAILABLE_REFRESH_ACTIVITY) else {actions.openRegistration(registration);null}}
                    else->null
                }
            }
            MobileSection.EVENTS->{onTab(0);if(route.resourceId!=null)actions.openEvent(route.resourceId)else {reads.refreshEvents();if(valid())onHomeExtra("events")};null}
            MobileSection.SPACES->{onTab(0);if(route.resourceId!=null)actions.openSpace(route.resourceId)else {reads.refreshSpaces();if(valid())onHomeExtra("spaces")};null}
            MobileSection.PAYMENTS->{if(onPayments==null){onTab(4);text(CoreStrings.CORE_PAYMENTS_ARE_NOT_AVAILABLE_IN_THIS_BUILD_YET)}else {onPayments.invoke(route.resourceId);null}}
        }
    }
}
