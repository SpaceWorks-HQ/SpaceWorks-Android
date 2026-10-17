package org.spaceworks.app.account

import org.junit.Assert.*
import org.junit.Test

class DemoAccountDeletionTest {
    @Test fun everyKnownAndUnknownBlockerIsVisibleWithItsActualMakerspaceAndSafeFixTarget() {
        val holder = DemoAccountDeletion(); holder.review()
        assertEquals(DemoDeletionStage.BLOCKERS, holder.state.value.stage)
        assertEquals(13, holder.state.value.blockers.size)
        assertEquals(setOf(7, 8, null), holder.state.value.blockers.map { it.makerspaceID }.toSet())
        val unknown = holder.state.value.blockers.last()
        assertEquals("Additional review is required by this makerspace.", unknown.detail)
        assertEquals(DemoDeletionFix.SUPPORT, unknown.fix)
        assertEquals(DemoDeletionFix.PAYMENTS, holder.state.value.blockers.first().fix)
        holder.authenticate(DemoDeletionProof.PASSWORD, "DemoPass!123")
        assertFalse(holder.confirm("DELETE")); assertNull(holder.state.value.proofExpiresAt)
    }
    @Test fun proofIsFreshAndConfirmationExactBeforeOnlyLocalDemoCompletion() {
        val holder = DemoAccountDeletion { 1000 }
        holder.choose(DemoDeletionScenario.READY); holder.review()
        holder.authenticate(DemoDeletionProof.PASSWORD, "wrong")
        assertEquals(DeletionCopy.PROOF_INVALID, holder.state.value.error)
        holder.authenticate(DemoDeletionProof.PASSWORD, "DemoPass!123")
        assertEquals(301000L, holder.state.value.proofExpiresAt)
        assertFalse(holder.confirm("delete")); assertFalse(holder.confirm(" DELETE"))
        assertTrue(holder.confirm("DELETE")); assertEquals(DemoDeletionStage.FINISHED, holder.state.value.stage)
        assertNull(holder.state.value.proofExpiresAt); assertTrue(holder.state.value.blockers.isEmpty())
        assertFalse(holder.confirm("DELETE"))
    }
    @Test fun expiredProofAndLeavingScreenRequireFreshAuthenticationForAllMethods() {
        var clock = 1000L
        for (method in DemoDeletionProof.entries) {
            val holder = DemoAccountDeletion { clock }
            holder.choose(DemoDeletionScenario.READY); holder.review()
            holder.authenticate(method, if (method == DemoDeletionProof.PASSWORD) "DemoPass!123" else "123456")
            clock += 300000; assertFalse(holder.confirm("DELETE"))
            assertEquals(DemoDeletionStage.REAUTHENTICATE, holder.state.value.stage)
            assertEquals(DeletionCopy.PROOF_EXPIRED, holder.state.value.error)
            holder.authenticate(method, if (method == DemoDeletionProof.PASSWORD) "DemoPass!123" else "123456")
            holder.back(); assertNull(holder.state.value.proofExpiresAt); assertFalse(holder.confirm("DELETE"))
        }
    }
    @Test fun conflictReplacesBlockersAndErasesProofWithoutClaimingDeletion() {
        val holder = DemoAccountDeletion { 1000 }
        holder.choose(DemoDeletionScenario.CONFLICT); holder.review()
        holder.authenticate(DemoDeletionProof.PHONE, "123456")
        assertFalse(holder.confirm("DELETE")); assertTrue(holder.state.value.conflict)
        assertEquals(listOf("active_loans"), holder.state.value.blockers.map { it.code })
        assertEquals(DemoDeletionStage.BLOCKERS, holder.state.value.stage); assertNull(holder.state.value.proofExpiresAt)
        assertFalse(holder.confirm("DELETE")); holder.review()
        assertEquals(DemoDeletionStage.BLOCKERS, holder.state.value.stage)
        assertEquals(listOf("active_loans"), holder.state.value.blockers.map { it.code }); holder.clear()
        assertEquals(DemoDeletionState(), holder.state.value)
    }
}
