package org.spaceworks.app.platform

import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlatformAdaptersTest {
    @Test fun integrityBindsOpaqueProofToExactServerChallenge() = runTest {
        var calls = 0
        val provider = PlayIntegrityProofProvider(12345L) { project, hash ->
            calls++
            assertEquals(12345L, project)
            assertEquals("LdAL134CIs7YgmZUganB2fkHMJ0W4F7QB6HqY5KEd6k", hash)
            "opaque-google-proof"
        }
        assertEquals("opaque-google-proof", provider.proof("challenge"))
        assertEquals(1, calls)
        val hash = PlayIntegrityProofProvider.requestHash("fresh challenge + / Unicode ☀")
        assertEquals(43, hash.length)
        assertFalse(hash.any { it in "=+/\r\n" })
        assertNotEquals(hash, PlayIntegrityProofProvider.requestHash("different challenge"))
    }

    @Test fun missingIntegrityConfigurationNeverCallsSdkOrFallsBackToDevelopment() = runTest {
        var calls = 0
        for (project in listOf(null, 0L, -1L)) {
            val provider = PlayIntegrityProofProvider(project) { _, _ -> calls++; "unsafe" }
            try { provider.proof("challenge"); fail("Expected unavailable configuration") }
            catch (failure: PlatformUnavailable) { assertTrue(failure.message!!.contains("not configured")) }
        }
        assertEquals(0, calls)
        try { PlayIntegrityProofProvider(1L) { _, _ -> "" }.proof("challenge"); fail("Expected empty proof rejection") }
        catch (_: IllegalStateException) { }
    }

    @Test fun sdkTaskPublishesSuccessAndOriginalFailure() = runTest {
        val success = TaskCompletionSource<String>()
        val waiting = async { success.task.awaitPlatformTask() }
        runCurrent(); success.setResult("token")
        assertEquals("token", waiting.await())
        val failure = TaskCompletionSource<String>()
        val expected = IllegalStateException("provider refused")
        failure.setException(expected)
        try { failure.task.awaitPlatformTask(); fail("Expected SDK failure") }
        catch (actual: IllegalStateException) {
            // Coroutine debug stack recovery can copy exceptions while preserving the original as cause.
            assertEquals(expected.javaClass, actual.javaClass)
            assertEquals(expected.message, actual.message)
            assertTrue(actual === expected || actual.cause === expected)
        }
    }

    @Test fun cancelledScreenIgnoresLateSdkCallback() = runTest {
        val source = TaskCompletionSource<String>()
        var delivered = false
        val waiting = launch { source.task.awaitPlatformTask(); delivered = true }
        runCurrent(); waiting.cancel(); waiting.join()
        source.setResult("late-token"); runCurrent()
        assertFalse(delivered)
        assertTrue(waiting.isCancelled)
    }

    @Test fun integrityPreservesCoroutineCancellation() = runTest {
        val provider = PlayIntegrityProofProvider(1L) { _, _ -> throw CancellationException("Left sign-in") }
        try { provider.proof("challenge"); fail("Expected cancellation") }
        catch (_: CancellationException) { }
    }

    @Test fun v1AndV2PushAreHintsScopedToOneMakerspace() {
        val legacy = PushHint.from(mapOf("makerspace_id" to "7", "feature" to "hardware_requests", "event" to "approved"))!!
        assertEquals(1, legacy.version); assertEquals(7, legacy.makerspaceId); assertNull(legacy.link)
        val current = PushHint.from(mapOf("v" to "2", "makerspace_id" to "8", "event_id" to "evt-9",
            "inbox_row_id" to "42", "link" to "/member/inbox", "unrecognized" to "ignored"))!!
        assertEquals(2, current.version); assertEquals(42, current.inboxRowId); assertEquals("/member/inbox", current.link)
        assertNull(PushHint.from(emptyMap()))
        assertNull(PushHint.from(mapOf("makerspace_id" to "0")))
        assertNull(PushHint.from(mapOf("makerspace_id" to "organization:7")))
    }

    @Test fun pushLinksRejectExternalTraversalAndAmbiguousEncodings() {
        listOf("https://evil.example/", "javascript:alert(1)", "//evil.example", "/\\evil.example", "/api/../admin",
            "/member/%2e%2e/admin", "/%2f%2fevil.example", "/member/%5cadmin", "/member/%252e%252e/admin",
            "/member/inbox#outside", "/member/%0aadmin", "not-a-path", "/%zz").forEach { value ->
            assertNull(value, PushHint.safeInAppPath(value))
        }
        assertEquals("/member/inbox?unread=true", PushHint.safeInAppPath("/member/inbox?unread=true"))
        val hint = PushHint.from(mapOf("makerspace_id" to "7", "link" to "https://evil.example"))!!
        assertNull(hint.link) // Fall back to this authorized makerspace's inbox; never open the URL.
    }

    @Test fun tokenRotationPublishesLatestNonemptyTokenWithoutLoggingOrAccountAssumptions() {
        NativePushEvents.clearToken()
        NativePushEvents.updateToken("first-token")
        NativePushEvents.updateToken("rotated-token")
        NativePushEvents.updateToken("")
        assertEquals("rotated-token", NativePushEvents.token.value)
        NativePushEvents.clearToken(); assertNull(NativePushEvents.token.value)
    }

    @Test fun notificationPermissionIsOptionalAndOnlyRuntimeOnAndroid13OrLater() {
        assertFalse(NotificationAccess.needsRuntimePermission(26, false))
        assertFalse(NotificationAccess.needsRuntimePermission(32, false))
        assertTrue(NotificationAccess.needsRuntimePermission(33, false))
        assertFalse(NotificationAccess.needsRuntimePermission(36, true))
    }

    @Test fun qrScanOnlyEmitsOneValidValueUntilExplicitNewReviewSession() {
        val gate = QrScanGate()
        assertNull(gate.accept(null)); assertNull(gate.accept("  ")); assertNull(gate.accept("x".repeat(4097)))
        assertEquals("makerspace-qr", gate.accept("  makerspace-qr\n"))
        assertNull(gate.accept("makerspace-qr")); assertNull(gate.accept("different-person"))
        assertEquals("different-person", QrScanGate().accept("different-person"))
    }
}
