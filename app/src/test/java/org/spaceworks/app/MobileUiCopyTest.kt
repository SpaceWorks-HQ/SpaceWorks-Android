package org.spaceworks.app

import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.InventoryItem
import org.spaceworks.app.platform.MobileCopyKey
import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.ui.*

class MobileUiCopyTest {
    @Test fun knownWireStatusesUseResourcesAndUnknownServerStatusSurvives() {
        val seen=mutableListOf<MobileCopyKey>()
        val resolver=MobileText {key,_->seen+=key;"localized-status"}
        assertEquals("localized-status",memberStatus("registered",resolver))
        assertEquals(listOf(UiStrings.MOBILE_REGISTERED),seen)
        seen.clear()
        assertEquals("Future status",memberStatus("future_status",resolver))
        assertTrue(seen.isEmpty())
    }
    @Test fun quantitiesHaveSingularAndPluralEnglishFallbacks() {
        assertEquals("1 item",UiStrings.MOBILE_VALUE_ITEMS.english(1))
        assertEquals("0 items",UiStrings.MOBILE_VALUE_ITEMS.english(0))
        assertEquals("2 items",UiStrings.MOBILE_VALUE_ITEMS.english(2))
        assertEquals("Renewed 1 time",UiStrings.MOBILE_RENEWED_VALUE_TIMES.english(1))
        assertEquals("Renewed 2 times",UiStrings.MOBILE_RENEWED_VALUE_TIMES.english(2))
        assertEquals("Estimated time: 1 minute",UiStrings.MOBILE_ESTIMATED_TIME_VALUE_MINUTES.english(1))
        assertEquals("Try again in 1 second.",UiStrings.MOBILE_TRY_AGAIN_IN_VALUE_SECONDS.english(1L))
    }
    @Test fun exactStockCountIsFormattedWithoutInventingHiddenOrUnknownStock() {
        val base=InventoryItem("19","Server name","Server description",null,null,null,"Server terms",null,"Available","exact_count",3)
        var args:List<Any> = emptyList()
        val text=MobileText {_,values->args=values.toList();"localized-stock"}
        assertEquals("localized-stock",memberAvailability(base,text))
        assertEquals(listOf(3),args)
        assertEquals("Available",memberAvailability(base.copy(availabilityMode="hidden")))
        assertEquals("Unexpected stock",memberAvailability(base.copy(availability="Unexpected stock")))
        assertEquals("Ask your makerspace about availability.",memberAvailability(base.copy(availability="Ask your makerspace about availability.",availableCount=null)))
    }
}
