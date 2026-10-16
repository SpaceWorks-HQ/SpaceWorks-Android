package org.spaceworks.app.member

import org.junit.Assert.assertEquals
import org.junit.Test
import org.spaceworks.app.ui.memberAvailability

class MemberPresentationTest {
    @Test fun exactCountsUseServerLabelsAndHiddenCountsNeverBecomeStockClaims() {
        val item=InventoryItem("1","Tool","",null,null,null,"",null,"Available","exact_count",3)
        assertEquals("3 available",memberAvailability(item))
        assertEquals("2 left",memberAvailability(item.copy(availability="Limited",availableCount=2)))
        assertEquals("Unavailable",memberAvailability(item.copy(availability="Unavailable",availableCount=999)))
        assertEquals("Ask your makerspace about availability.",memberAvailability(item.copy(availability="Ask your makerspace about availability.",availabilityMode="hidden",availableCount=999)))
        assertEquals("Available",memberAvailability(item.copy(availabilityMode="status_only",availableCount=999)))
    }
}
