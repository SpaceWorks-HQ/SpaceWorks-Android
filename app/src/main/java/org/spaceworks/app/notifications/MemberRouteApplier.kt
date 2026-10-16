package org.spaceworks.app.notifications

import org.spaceworks.app.member.*
import org.spaceworks.app.actions.*

/** Executes read-only navigation after LinkStateHolder has freshly authorized a membership.
 * No request/booking/payment/attendance mutation is triggered by a link. */
class MemberRouteApplier(private val reads:MemberStateHolder,private val actions:MemberActionsStateHolder,
    private val onTab:(Int)->Unit,private val onHomeExtra:(String)->Unit,
    private val onPayments:((String?)->Unit)?=null) {
    suspend fun open(value:AuthorizedNavigation,scope:MemberScope,current:()->Boolean):String? {
        fun valid()=current() && reads.state.value.scope==scope && actions.state.value.scope==scope && value.destination.makerspaceId==scope.id
        if(!valid())return null
        val route=value.destination
        if(value.notification) {
            reads.refreshInbox()
            if(!valid())return null
            val inbox=reads.state.value.inbox
            if(inbox.error!=null || inbox.value==null){onTab(3);return "Couldn't refresh this update. Try again from Inbox."}
        }
        if(!valid())return null
        if(actions.state.value.busy || actions.state.value.route!=null)return "Finish your current action before opening this update."
        return when(route.section) {
            MobileSection.HOME->{onTab(0);reads.refreshOverview();null}
            MobileSection.INBOX->{onTab(3);if(!value.notification)reads.refreshInbox();null}
            MobileSection.PROFILE->{onTab(4);null}
            MobileSection.INVENTORY->{onTab(1);if(route.resourceId!=null)reads.selectProduct(route.resourceId)else {reads.closeProduct();reads.refreshInventory()};null}
            MobileSection.ACTIVITY,MobileSection.BOOKINGS,MobileSection.REGISTRATION->{
                onTab(2);reads.refreshOverview()
                if(!valid())return null
                if(actions.state.value.busy || actions.state.value.route!=null)return "Finish your current action before opening this update."
                val overview=reads.state.value.overview
                val rows=overview.value
                if(overview.error!=null || rows==null)return "Couldn't refresh this update. Try again from Activity."
                if(route.resourceId==null)return null
                when(route.section) {
                    MobileSection.BOOKINGS->{val booking=rows.bookings.items.firstOrNull {it.id==route.resourceId};if(booking==null)"This booking is no longer available. Refresh Activity." else {actions.openBooking(booking);null}}
                    MobileSection.REGISTRATION->{val registration=rows.registrations.firstOrNull {it.id==route.resourceId};if(registration==null)"This registration is no longer available. Refresh Activity." else {actions.openRegistration(registration);null}}
                    else->null
                }
            }
            MobileSection.EVENTS->{onTab(0);if(route.resourceId!=null)actions.openEvent(route.resourceId)else {reads.refreshEvents();if(valid())onHomeExtra("events")};null}
            MobileSection.SPACES->{onTab(0);if(route.resourceId!=null)actions.openSpace(route.resourceId)else {reads.refreshSpaces();if(valid())onHomeExtra("spaces")};null}
            MobileSection.PAYMENTS->{if(onPayments==null){onTab(4);"Payments are not available in this build yet."}else {onPayments.invoke(route.resourceId);null}}
        }
    }
}
