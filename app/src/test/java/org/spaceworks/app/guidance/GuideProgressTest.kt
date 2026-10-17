package org.spaceworks.app.guidance

import org.junit.Assert.*
import org.junit.Test

class GuideProgressTest {
    private val scope = GuideScope("https://workshop.invalid/", "private-user-41", 7)

    @Test fun dismissSurvivesRemountAndDoesNotRequireAnAccountOrNetworkAction() {
        val store = MemoryGuideStore()
        val first = GuideProgress(scope, store)
        assertFalse(first.dismissed.value)
        first.dismiss(); assertTrue(first.dismissed.value)
        assertTrue(GuideProgress(scope.copy(), store).dismissed.value)
        first.dismiss(); assertTrue(GuideProgress(scope, store).dismissed.value)
    }
    @Test fun makerUserOriginAndFeatureVersionHaveIndependentFirstUseFlags() {
        val store = MemoryGuideStore(); GuideProgress(scope, store).dismiss()
        for (other in listOf(scope.copy(makerspaceID = 8), scope.copy(userID = "private-user-42"),
            scope.copy(origin = "https://other.invalid/"), scope.copy(version = 2))) {
            assertNotEquals(GuideProgress.key(scope), GuideProgress.key(other))
            assertFalse(GuideProgress(other, store).dismissed.value)
        }
    }
    @Test fun resettingCurrentTipsDoesNotResetAnotherMakerOrAccount() {
        val store = MemoryGuideStore()
        val current = GuideProgress(scope, store)
        val other = GuideProgress(scope.copy(makerspaceID = 8), store)
        current.dismiss(); other.dismiss(); current.resetTips()
        assertFalse(current.dismissed.value)
        assertFalse(GuideProgress(scope, store).dismissed.value)
        assertTrue(GuideProgress(other.scope, store).dismissed.value)
    }
    @Test fun onlyVersionedHashedBooleanFlagsAreWrittenNeverRawIdentityOrDrafts() {
        val values = mutableMapOf<String, Boolean>()
        val progress = GuideProgress(scope, object : GuideStore {
            override fun get(key: String) = values[key] ?: false
            override fun set(key: String, value: Boolean) { values[key] = value }
        })
        progress.dismiss(); progress.resetTips()
        assertEquals(1, values.size)
        assertTrue(values.keys.single().matches(Regex("usage-guide\\.[0-9a-f]{64}")))
        assertFalse(values.keys.single().contains(scope.origin))
        assertFalse(values.keys.single().contains(scope.userID))
        assertEquals(false, values.values.single())
        assertEquals("GuideScope(redacted)", scope.toString())
    }
    @Test fun lengthFramingPreventsAmbiguousScopeConcatenationAndDemoStoreIsIsolated() {
        assertNotEquals(GuideProgress.key(GuideScope("https://a.invalid/", "b:c", 7)),
            GuideProgress.key(GuideScope("https://a.invalid/:b", "c", 7)))
        val live = MemoryGuideStore(); val demo = MemoryGuideStore()
        GuideProgress(scope, demo).dismiss()
        assertFalse(GuideProgress(scope, live).dismissed.value)
        GuideProgress(scope, live).dismiss(); GuideProgress(scope, demo).resetTips()
        assertTrue(GuideProgress(scope, live).dismissed.value)
    }
    @Test fun missingIdentityOrInventedMakerAndInvalidVersionCannotCreateProgressScope() {
        for (invalid in listOf({ scope.copy(origin = "") }, { scope.copy(userID = "") },
            { scope.copy(makerspaceID = 0) }, { scope.copy(makerspaceID = -1) }, { scope.copy(version = 0) })) {
            assertThrows(IllegalArgumentException::class.java) { invalid() }
        }
    }

    @Test fun bundleOwnedDemoFlagsSurviveGuideRemountButClearOnlyTheirOwnBundle() {
        val bundle = MemoryGuideStore()
        val anotherBundle = MemoryGuideStore()
        GuideProgress(scope, bundle).dismiss()
        GuideProgress(scope.copy(makerspaceID = 8), bundle).dismiss()
        GuideProgress(scope, anotherBundle).dismiss()
        // Introduction replay can unmount both the guide and member shell. Reusing the
        // bundle store preserves progress when entirely new holders mount afterwards.
        assertTrue(GuideProgress(scope.copy(), bundle).dismissed.value)
        assertTrue(GuideProgress(scope.copy(makerspaceID = 8), bundle).dismissed.value)
        bundle.clear()
        assertFalse(GuideProgress(scope, bundle).dismissed.value)
        assertFalse(GuideProgress(scope.copy(makerspaceID = 8), bundle).dismissed.value)
        assertTrue(GuideProgress(scope, anotherBundle).dismissed.value)
    }
}
