package org.spaceworks.app.platform

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
        assertEquals("hardware_requests", legacy.feature); assertEquals("approved", legacy.event)
        val current = PushHint.from(mapOf("v" to "2", "makerspace_id" to "8", "event_id" to "evt-9",
            "inbox_row_id" to "42", "link" to "/member/inbox", "unrecognized" to "ignored"))!!
        assertEquals(2, current.version); assertEquals(42, current.inboxRowId); assertEquals("/member/inbox", current.link)
        assertEquals("evt-9", current.eventId)
        assertNull(PushHint.from(emptyMap<String, Any?>()))
    }

    @Test fun legacyAndUnknownPushVersionsCannotSupplyActionableV2Fields() {
        val extended = mapOf("makerspace_id" to "7", "event_id" to "evt-9", "inbox_row_id" to "42",
            "link" to "/member/inbox", "feature" to "events", "event" to "registered")
        for (version in listOf(null, "1", "0", "3", "999", "bad", "true", "2.0", "+2", " 2")) {
            val hint = PushHint.from(if (version == null) extended else extended + ("v" to version))!!
            assertEquals(7, hint.makerspaceId)
            assertNull("version=$version", hint.eventId)
            assertNull("version=$version", hint.inboxRowId)
            assertNull("version=$version", hint.link)
            if (version != null && version != "1") {
                assertNull(hint.feature); assertNull(hint.event)
            }
        }
        assertEquals(1, PushHint.from(extended)!!.version)
        assertEquals(0, PushHint.from(extended + ("v" to "bad"))!!.version)
    }

    @Test fun pushIDsRejectBooleansFractionsSignedAndOverflowValues() {
        for (value in listOf(null, true, false, "true", "false", "", "0", "-1", "+1", " 7", "7 ",
                "1.0", "1e2", "organization:7", "2147483648", 2147483648L, 7.0, -1)) {
            assertNull("makerspace_id=$value", PushHint.from(mapOf("makerspace_id" to value)))
            val hint = PushHint.from(mapOf("v" to "2", "makerspace_id" to "7", "inbox_row_id" to value))!!
            assertNull("inbox_row_id=$value", hint.inboxRowId)
        }
        assertEquals(7, PushHint.from(mapOf("makerspace_id" to 7))!!.makerspaceId)
        assertEquals(42, PushHint.from(mapOf("v" to 2, "makerspace_id" to 7, "inbox_row_id" to 42L))!!.inboxRowId)
        assertNull(PushHint.from(mapOf("v" to true, "makerspace_id" to 7, "link" to "/member/inbox"))!!.link)
    }

    @Test fun v2PushMetadataIsBoundedAndNeverConvertsAnObjectIntoAnEventID() {
        val base = mapOf("v" to "2", "makerspace_id" to "7")
        for (value in listOf("", " ", "a\nb", "a\u0000b", "a\u202Eb", "a".repeat(257), true, 42)) {
            assertNull("event_id=$value", PushHint.from(base + ("event_id" to value))!!.eventId)
        }
        assertEquals("a".repeat(256), PushHint.from(base + ("event_id" to "a".repeat(256)))!!.eventId)
    }

    @Test fun pushLinksRejectExternalTraversalAndAmbiguousEncodings() {
        listOf("https://evil.example/", "javascript:alert(1)", "//evil.example", "/\\evil.example", "/api/../admin",
            "/member/%2e%2e/admin", "/%2f%2fevil.example", "/member/%5cadmin", "/member/%252e%252e/admin",
            "/member/inbox#outside", "/member/inbox#", "/member/%0aadmin", "not-a-path", "/%zz",
            "/member/./inbox", "/member/inbox?unread=true", "/member/inbox?", "/a\nb", "/a\u0000b",
            "/a\u202Eb", "/%61dmin", "/a b", "/" + "a".repeat(2048), "/" + "é".repeat(1024)).forEach { value ->
            assertNull(value, PushHint.safeInAppPath(value))
        }
        assertEquals("/member/inbox", PushHint.safeInAppPath("/member/inbox"))
        assertEquals("/" + "a".repeat(2047), PushHint.safeInAppPath("/" + "a".repeat(2047)))
        assertEquals("/" + "é".repeat(1023), PushHint.safeInAppPath("/" + "é".repeat(1023)))
        val hint = PushHint.from(mapOf("v" to "2", "makerspace_id" to "7", "link" to "https://evil.example"))!!
        assertNull(hint.link) // Fall back to this authorized makerspace's inbox; never open the URL.
    }

    @Test fun tokenRotationPublishesLatestNonemptyTokenWithoutLoggingOrAccountAssumptions() {
        NativePushEvents.updateDelivery(true,setOf(7))
        NativePushEvents.clearToken()
        NativePushEvents.updateToken("first-token")
        NativePushEvents.updateToken("rotated-token")
        NativePushEvents.updateToken("")
        assertEquals("rotated-token", NativePushEvents.token.value)
        NativePushEvents.updateDelivery(false,emptySet()); assertNull(NativePushEvents.token.value)
        NativePushEvents.updateToken("late-token"); assertNull(NativePushEvents.token.value)
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
    @Test fun foregroundHintsRequireEnabledMembershipAndNeverReplayAcrossLogout()=kotlinx.coroutines.test.runTest {
        NativePushEvents.updateDelivery(true,setOf(7))
        val received=mutableListOf<PushHint>()
        val collection=backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)){NativePushEvents.hints.collect {received+=it}}
        val hint=PushHint(7,null,null,null,null,null,1)
        NativePushEvents.receive(hint);NativePushEvents.receive(hint.copy(makerspaceId=8))
        NativePushEvents.updateDelivery(false,emptySet());NativePushEvents.receive(hint)
        assertEquals(listOf(hint),received);collection.cancel()
        NativePushEvents.updateDelivery(true,setOf(7))
        val newAccount=mutableListOf<PushHint>()
        val next=backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)){NativePushEvents.hints.collect {newAccount+=it}}
        assertTrue(newAccount.isEmpty());next.cancel();NativePushEvents.updateDelivery(false,emptySet())
    }

}
