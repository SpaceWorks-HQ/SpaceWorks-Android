package org.spaceworks.app.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.network.*
import org.spaceworks.generated.model.*

class AuthCooldownOverflowTest {
    @Test fun maximalServerWaitBlocksSecondActualAuthLoginWithoutOverflow() = runTest {
        val api = ThrottledLogin(Long.MAX_VALUE)
        val holder = AuthStateHolder(api, MemoryTokenStore(), now = { 1000L })
        holder.initialize(); holder.signIn("member", "fixture-password")
        assertEquals(Long.MAX_VALUE, holder.state.value.retryAtMillis)
        holder.signIn("member", "fixture-password")
        assertEquals("An overflow must never allow a second login", 1, api.logins)
        assertNull(holder.state.value.user)
    }
    @Test fun ordinaryWaitMissingDefaultAndExplicitZeroKeepActualAuthBehavior() = runTest {
        for ((header, expected) in listOf(5L to 6000L, null to 61000L, 0L to 1000L)) {
            var clock = 1000L
            val api = ThrottledLogin(header)
            val holder = AuthStateHolder(api, MemoryTokenStore(), now = { clock })
            holder.initialize(); holder.signIn("member", "fixture-password")
            assertEquals(expected, holder.state.value.retryAtMillis)
            holder.signIn("member", "fixture-password")
            assertEquals(if (header == 0L) 2 else 1, api.logins)
            clock = expected
            holder.signIn("member", "fixture-password")
            assertEquals(if (header == 0L) 3 else 2, api.logins)
        }
    }
    private class ThrottledLogin(private val wait: Long?) : AuthGateway {
        var logins = 0
        override suspend fun config() = PublicConfig(false, 100)
        override suspend fun login(username: String, password: String): DeviceLoginResult {
            logins++; throw ApiFailure(429, "Wait for the server.", "throttled", wait)
        }
        override suspend fun enroll(challenge: String): MFAEnrollment = error("Unused")
        override suspend fun verify(challenge: String, code: String, enrollment: Boolean): MFACompleted = error("Unused")
        override suspend fun me(): AuthUserPayload = error("Unused")
        override suspend fun logout(): Unit = error("Unused")
    }
}
