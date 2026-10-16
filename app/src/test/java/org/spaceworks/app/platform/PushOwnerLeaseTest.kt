package org.spaceworks.app.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PushOwnerLeaseTest {
    private val owner = PushOwnerIdentity("https://workshop.invalid/", 4)

    @Test fun oldControllerPauseAndFinallyCannotDisableOrPublishOverNewController() {
        val coordinator = PushOwnerLeaseCoordinator()
        val old = coordinator.claim(owner)
        val current = coordinator.claim(owner)
        assertNotEquals(old.sequence, current.sequence)
        var gate = "new-enabled"
        assertFalse(coordinator.disable(old))
        assertFalse(coordinator.enable(old))
        assertFalse(coordinator.publish(old, requireEnabled = false) { gate = "old-disabled" })
        assertFalse(coordinator.publish(old) { gate = "old-token" })
        assertTrue(coordinator.acceptsToken(current))
        assertEquals("new-enabled", gate)
        assertTrue(coordinator.disable(current))
        assertFalse(coordinator.acceptsToken(current))
        assertTrue(coordinator.owns(current))
        assertTrue(coordinator.publish(current, requireEnabled = false) { gate = "current-disabled" })
        assertEquals("current-disabled", gate)
    }

    @Test fun oldStopFinishesBeforeNewTokenStartsAndItsLateResultCannotPublish() = runTest {
        val coordinator = PushOwnerLeaseCoordinator()
        val old = coordinator.claim(owner)
        assertTrue(coordinator.disable(old))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val effects = mutableListOf<String>()
        val stop = async {
            coordinator.serialized(old, requireEnabled = false) {
                effects += "old-stop-start"; entered.complete(Unit); release.await()
                effects += "old-stop-finish"; "old-result"
            }
        }
        entered.await()
        val current = coordinator.claim(owner.copy(installationEpoch = 5))
        val token = async { coordinator.serialized(current) { effects += "new-token"; "new-result" } }
        runCurrent()
        assertEquals(listOf("old-stop-start"), effects)
        assertFalse(token.isCompleted)
        release.complete(Unit)
        assertNull(stop.await())
        assertEquals("new-result", token.await())
        assertEquals(listOf("old-stop-start", "old-stop-finish", "new-token"), effects)
        assertTrue(coordinator.acceptsToken(current))
    }

    @Test fun queuedOldSdkWorkIsDiscardedBeforeAnySideEffectAfterAnotherOwnerClaims() = runTest {
        val coordinator = PushOwnerLeaseCoordinator()
        val old = coordinator.claim(owner)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val blocker = async { coordinator.serialized(old) { entered.complete(Unit); release.await(); "old" } }
        entered.await()
        var queuedCalls = 0
        val queued = async { coordinator.serialized(old, requireEnabled = false) { queuedCalls++; "wrong" } }
        runCurrent()
        val replacement = coordinator.claim(owner.copy(origin = "https://other.invalid/"))
        release.complete(Unit)
        assertNull(blocker.await()); assertNull(queued.await()); assertEquals(0, queuedCalls)
        assertTrue(coordinator.acceptsToken(replacement))
    }

    @Test fun callerCancellationCannotReleaseSdkLockWhileStopSideEffectStillRuns() = runTest {
        val coordinator = PushOwnerLeaseCoordinator()
        val old = coordinator.claim(owner); coordinator.disable(old)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val effects = mutableListOf<String>()
        val stop = launch {
            coordinator.serialized(old, requireEnabled = false) {
                entered.complete(Unit); release.await(); effects += "stopped"
            }
        }
        entered.await(); stop.cancel()
        val current = coordinator.claim(owner)
        val token = async { coordinator.serialized(current) { effects += "token"; "new" } }
        runCurrent(); assertTrue(effects.isEmpty()); assertFalse(token.isCompleted)
        release.complete(Unit); stop.join()
        assertEquals("new", token.await()); assertEquals(listOf("stopped", "token"), effects)
    }

    @Test fun epochAndOriginReplacementDropOldTokensAndDisabledCurrentCannotAcquire() = runTest {
        val coordinator = PushOwnerLeaseCoordinator()
        val first = coordinator.claim(owner)
        val second = coordinator.claim(owner.copy(installationEpoch = 5))
        val third = coordinator.claim(owner.copy(origin = "https://other.invalid/", installationEpoch = 5))
        var calls = 0
        assertNull(coordinator.serialized(first) { calls++; "first" })
        assertNull(coordinator.serialized(second) { calls++; "second" })
        assertFalse(coordinator.disable(second)); assertTrue(coordinator.acceptsToken(third))
        coordinator.disable(third)
        assertNull(coordinator.serialized(third) { calls++; "disabled" })
        assertEquals(0, calls)
        assertEquals("stop", coordinator.serialized(third, requireEnabled = false) { "stop" })
    }

    @Test fun initiallyDisabledLeaseRequiresItsOwnExplicitEnable() {
        val coordinator = PushOwnerLeaseCoordinator()
        val lease = coordinator.claim(owner, enabled = false)
        assertTrue(coordinator.owns(lease)); assertFalse(coordinator.acceptsToken(lease))
        assertTrue(coordinator.enable(lease)); assertTrue(coordinator.acceptsToken(lease))
        val newer = coordinator.claim(owner, enabled = false)
        assertFalse(coordinator.enable(lease)); assertFalse(coordinator.acceptsToken(newer))
        assertTrue(coordinator.enable(newer)); assertTrue(coordinator.acceptsToken(newer))
    }

    @Test fun vacantCleanupCreatesDisabledLeaseAndExactOwnerAlwaysReusesIt() {
        val coordinator = PushOwnerLeaseCoordinator()
        val cleanup = requireNotNull(coordinator.cleanupLease(owner))
        assertEquals(owner, cleanup.owner)
        assertTrue(coordinator.owns(cleanup)); assertFalse(coordinator.acceptsToken(cleanup))
        assertSame(cleanup, coordinator.cleanupLease(owner.copy()))
        assertTrue(coordinator.enable(cleanup))
        assertSame("Cleanup lookup must not replace a controller's existing lease", cleanup,
            coordinator.cleanupLease(owner.copy()))
        assertTrue("Looking up cleanup must not silently disable current delivery", coordinator.acceptsToken(cleanup))
    }

    @Test fun cleanupCannotClaimDifferentOriginOrInstallationOverExistingLease() {
        for (enabled in listOf(false, true)) {
            val coordinator = PushOwnerLeaseCoordinator()
            val current = coordinator.claim(owner, enabled = enabled)
            assertNull(coordinator.cleanupLease(owner.copy(origin = "https://other.invalid/")))
            assertNull(coordinator.cleanupLease(owner.copy(installationEpoch = owner.installationEpoch + 1)))
            assertNull(coordinator.cleanupLease(owner.copy(installationEpoch = owner.installationEpoch - 1)))
            assertTrue(coordinator.owns(current))
            assertEquals(enabled, coordinator.acceptsToken(current))
            assertSame(current, coordinator.cleanupLease(owner))
        }
    }
}
