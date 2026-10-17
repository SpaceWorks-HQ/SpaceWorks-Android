package org.spaceworks.app.platform

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Canonical origin and accepted installation only; never credentials, accounts or UI owners. */
data class PushOwnerIdentity(val origin: String, val installationEpoch: Long) {
    init { require(origin.isNotBlank() && installationEpoch >= 0) }
}

/** Reference identity plus a monotonic sequence distinguishes two controllers of the same account. */
class PushOwnerLease internal constructor(val owner: PushOwnerIdentity, val sequence: Long)

/** One process-shared instance owns SDK side effects and synchronous local delivery publication. */
class PushOwnerLeaseCoordinator {
    private val sdk = Mutex()
    private var sequence = 0L
    private var current: PushOwnerLease? = null
    private var enabled = false

    @Synchronized fun claim(owner: PushOwnerIdentity, enabled: Boolean = true): PushOwnerLease {
        check(sequence != Long.MAX_VALUE)
        return PushOwnerLease(owner, ++sequence).also { current = it; this.enabled = enabled }
    }
    /** Cleanup may reuse its exact owner, but cannot take over another installation or origin. */
    @Synchronized fun cleanupLease(owner: PushOwnerIdentity): PushOwnerLease? {
        val existing=current
        return if(existing==null)claim(owner,enabled=false)else existing.takeIf {it.owner==owner}
    }
    @Synchronized fun owns(lease: PushOwnerLease) = current === lease
    @Synchronized fun acceptsToken(lease: PushOwnerLease) = current === lease && enabled

    @Synchronized fun enable(expected: PushOwnerLease): Boolean {
        if (current !== expected) return false
        enabled = true
        return true
    }

    /** Retain the disabled lease so its serialized stop may finish, until another claim supersedes it. */
    @Synchronized fun disable(expected: PushOwnerLease): Boolean {
        if (current !== expected) return false
        enabled = false
        return true
    }

    /** Keep ownership checking and a local gate/token write atomic against another controller claim. */
    @Synchronized fun publish(expected: PushOwnerLease, requireEnabled: Boolean = true, write: () -> Unit): Boolean {
        if (current !== expected || (requireEnabled && !enabled)) return false
        write()
        return true
    }

    /**
     * Check after acquiring the shared SDK lock, then retain it until the actual SDK operation
     * finishes. Caller cancellation cannot release it while deleteToken remains in flight.
     * Recheck before returning any result; an older operation cannot publish into a newer lease.
     */
    suspend fun <T> serialized(lease: PushOwnerLease, requireEnabled: Boolean = true, work: suspend () -> T): T? =
        sdk.withLock {
            if (!valid(lease, requireEnabled)) return@withLock null
            withContext(NonCancellable) {
                val result = work()
                if (valid(lease, requireEnabled)) result else null
            }
        }

    @Synchronized private fun valid(lease: PushOwnerLease, requireEnabled: Boolean) =
        current === lease && (!requireEnabled || enabled)
}
