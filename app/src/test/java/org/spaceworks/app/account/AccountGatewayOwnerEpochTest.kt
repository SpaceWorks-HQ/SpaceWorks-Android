package org.spaceworks.app.account

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.network.ApiSession
import org.spaceworks.app.network.MemoryTokenStore
import org.spaceworks.app.network.TokenPair
import org.spaceworks.app.network.TokenSnapshot
import org.spaceworks.app.network.TokenStore

/** No sleeps or background replacement threads: callbacks expose the exact first-send gaps. */
class AccountGatewayOwnerEpochTest {
    private val scope = MemberScope(7, "workshop")
    private val replacement = TokenPair("replacement-access", "replacement-refresh")

    @Test(timeout = 10_000)
    fun globalRecoveryMutationCannotBorrowReplacementAfterLastOwnerProof() = runBlocking {
        refuseFirstSend(global = true, replaceInCallFactory = false)
    }

    @Test(timeout = 10_000)
    fun scopedLeaveMutationCannotBorrowReplacementAfterLastOwnerProof() = runBlocking {
        refuseFirstSend(global = false, replaceInCallFactory = false)
    }

    @Test(timeout = 10_000)
    fun globalRecoveryCallObserverRefusesReplacementCapturedByCallFactory() = runBlocking {
        refuseFirstSend(global = true, replaceInCallFactory = true)
    }

    @Test(timeout = 10_000)
    fun scopedLeaveCallObserverRefusesReplacementCapturedByCallFactory() = runBlocking {
        refuseFirstSend(global = false, replaceInCallFactory = true)
    }

    @Test(timeout = 10_000)
    fun exportGrantCannotStartPublicGetWhenReplacementArrivesAtWorkerObserver() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val tokens = SwapStore(replacement)
            tokens.replace(TokenPair("original-access", "original-refresh"))
            val original = tokens.snapshot()
            val job = "84000000-0000-4000-8000-000000000001"
            var proofs = 0
            val gateway = GeneratedAccountGateway(ApiSession(server.url("/"), tokens), {
                // checked, generated grant api(), post-grant guard, then IO worker guard.
                // Its epoch read consumes one; public observeOwner consumes two and must
                // cancel before enqueue even though this public endpoint has no bearer.
                if (++proofs == 4) tokens.replaceOnSnapshot(2)
                true
            }, { scope })
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"url":"/api/v1/data-exports/download/$job/private-fixture-token","expires_at":"2099-01-01T00:00:00Z"}"""
            ))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/zip")
                .setBody(Buffer().write(byteArrayOf(80, 75, 5, 6))))
            var completed = false
            try {
                withTimeout(3_000) { gateway.exportArchive(scope, job) }
                completed = true
            } catch (failure: Exception) {
                assertFalse("A timeout is not proof of ownership refusal", failure is TimeoutCancellationException)
                assertTrue("Unexpected rejection: ${failure.javaClass.name}",
                    failure is CancellationException || failure is IOException || failure is ApiFailure)
            }
            assertFalse("An old export must not publish after account replacement", completed)
            assertTrue("Fixture did not replace at the public observer boundary", tokens.replaced)
            assertEquals(original.epoch + 1, tokens.snapshot().epoch)
            assertEquals(replacement, tokens.read())
            assertEquals("Only the owning account's grant may reach the deployment", 1, server.requestCount)
            val grant = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("/api/v1/member/makerspaces/7/account/exports/$job/download", grant.path)
            assertEquals("Bearer original-access", grant.getHeader("Authorization"))
            assertEquals("7", grant.getHeader("X-Makerspace-Id"))
            assertNull("The private one-use grant must never leave in a public GET", server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    private suspend fun refuseFirstSend(global: Boolean, replaceInCallFactory: Boolean) {
        MockWebServer().use { server ->
            server.start()
            val tokens = SwapStore(replacement)
            tokens.replace(TokenPair("original-access", "original-refresh"))
            val original = tokens.snapshot()
            var proofs = 0
            val gateway = GeneratedAccountGateway(ApiSession(server.url("/"), tokens), {
                // checked() proves ownership first; api() makes the final proof immediately
                // before constructing the generated request. Simulate a concurrent store write
                // after that proof has been computed, without depending on thread scheduling.
                if (++proofs == 2) {
                    if (replaceInCallFactory) tokens.replaceOnSnapshot(2)
                    else tokens.installReplacement()
                }
                true
            }, { if (global) null else scope })
            // A broken implementation completes promptly; it must fail the assertions rather
            // than merely time out waiting for an intentionally absent server response.
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                if (global) """{"recovery_codes":["fixture-only-code"]}"""
                else """{"status":"revoked","renewal_cancelled_at":null}"""
            ))
            var completed = false
            try {
                withTimeout(3_000) {
                    if (global) gateway.regenerateRecovery(null, "fixture-current-password")
                    else gateway.leave(scope, "Fixture member leaving")
                }
                completed = true
            } catch (failure: Exception) {
                // Depending on the rejecting layer, ownership refusal is cancellation, a
                // cancelled OkHttp call, or the existing local expired-session envelope.
                assertFalse("A timeout is not proof of ownership refusal", failure is TimeoutCancellationException)
                assertTrue("Unexpected rejection: ${failure.javaClass.name}",
                    failure is CancellationException || failure is IOException || failure is ApiFailure)
            }
            assertFalse("A replaced owner must never complete the old security mutation", completed)
            assertTrue("Fixture did not reach the intended replacement boundary", tokens.replaced)
            assertTrue("Both pre-operation and final generated-call guards must run", proofs >= 2)
            assertEquals(original.epoch + 1, tokens.snapshot().epoch)
            assertEquals("New credentials must survive rejection", replacement, tokens.read())
            assertEquals("Neither an old nor a new-account mutation may reach the deployment", 0, server.requestCount)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    private class SwapStore(private val replacement: TokenPair) : TokenStore {
        private val delegate = MemoryTokenStore()
        private var countdown: Int? = null
        var replaced = false
            private set

        @Synchronized fun installReplacement() {
            check(!replaced)
            delegate.replace(replacement)
            replaced = true
        }

        @Synchronized fun replaceOnSnapshot(count: Int) {
            check(count > 0 && countdown == null && !replaced)
            countdown = count
        }

        @Synchronized override fun snapshot(): TokenSnapshot {
            countdown?.let { remaining ->
                if (remaining == 1) {
                    countdown = null
                    installReplacement()
                } else countdown = remaining - 1
            }
            return delegate.snapshot()
        }
        @Synchronized override fun read() = delegate.read()
        @Synchronized override fun replace(pair: TokenPair?) = delegate.replace(pair)
        @Synchronized override fun rotateIfCurrent(expected: TokenSnapshot, pair: TokenPair?) =
            delegate.rotateIfCurrent(expected, pair)
    }
}
