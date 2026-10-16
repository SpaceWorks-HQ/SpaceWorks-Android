package org.spaceworks.app.member

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

class DemoMemberTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun leavingProfileCannotAbandonTheSelectedDemoScenarioRefresh()=runTest {
        val holder=MemberStateHolder(DemoMemberGateway());holder.activate(MemberScope(1,"first"))
        val changing=async {holder.preview(MemberPreviewScenario.UNKNOWN_DUES)}
        runCurrent();changing.cancel();changing.join()
        assertNotNull(holder.state.value.overview.value);assertNull(holder.state.value.overview.value!!.dues)
        assertFalse(holder.state.value.overview.loading);assertFalse(holder.state.value.inventory.loading)
    }
    @Test fun inventorySearchCategoryAndPagesStayInSelectedMakerspace()=runTest {
        val api=DemoMemberGateway();val first=MemberScope(1,"fab-lab-kochi");val second=MemberScope(2,"maker-space-bengaluru")
        val page=api.inventory(first);assertEquals(4,page.count);assertEquals(2,page.items.size)
        val next=api.inventory(first,next=page.next);assertEquals(4,(page.items+next.items).map {it.id}.distinct().size)
        assertTrue(api.inventory(first,"drill","tools").items.single().name.contains("drill",true))
        assertTrue(api.inventory(first,"drill","essentials").items.isEmpty())
        assertTrue(api.inventory(second).items.none {it.id in page.items.map {item->item.id}})
    }
    @Test fun cardRefusalsAndUnknownDuesAreHonestPreviewStates()=runTest {
        val api=DemoMemberGateway();val scope=MemberScope(1,"fab-lab-kochi")
        assertTrue(api.card(scope).qrImage!!.startsWith("data:image/png;base64,"))
        api.scenario(MemberPreviewScenario.REVOKED_CARD);assertNull(api.card(scope).qrImage);assertFalse(api.card(scope).isActive)
        api.scenario(MemberPreviewScenario.NO_CARD);assertTrue(api.card(scope).notIssued)
        api.scenario(MemberPreviewScenario.UNKNOWN_DUES);assertNull(api.overview(scope).dues)
        api.scenario(MemberPreviewScenario.EMPTY);assertTrue(api.overview(scope).dues!!.outstandingByCurrency!!.isEmpty())
    }
    @Test fun inboxReadStateIsIsolatedByMakerspaceAndAuthoritativeAfterMutation()=runTest {
        val api=DemoMemberGateway();val first=MemberScope(1,"first");val second=MemberScope(2,"second")
        api.markRead(first,"1");assertEquals(2,api.inbox(first,true).count);assertEquals(3,api.inbox(second,true).count)
        api.markAllRead(first);assertTrue(api.inbox(first,true).items.isEmpty());assertEquals(3,api.inbox(second,true).count)
    }
}
