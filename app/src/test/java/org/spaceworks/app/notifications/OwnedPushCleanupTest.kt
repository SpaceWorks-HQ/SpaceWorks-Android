package org.spaceworks.app.notifications

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.auth.*
import org.spaceworks.app.network.*
import org.spaceworks.generated.model.EnvironmentEnum
import org.spaceworks.generated.model.PublicConfig

/** Real generated DELETE/logout transport connected to private-state clearing and captured push owner. */
class OwnedPushCleanupTest {
    @Test(timeout = 15_000)
    fun logoutDeletesOldAssociationThenRevokesOldGrantAfterAcceptedIdentityClears() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val fixture = Fixture(server); fixture.initialize()
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(json("""{"detail":"Signed out."}"""))
            withTimeout(5_000) { fixture.holder.signOut() }
            val removal = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            val logout = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("DELETE", removal.method)
            assertEquals("/api/v1/integrations/push/devices/71", removal.path)
            assertEquals("7", removal.getHeader("X-Makerspace-Id"))
            assertEquals("POST", logout.method); assertEquals("/api/v1/auth/device/logout", logout.path)
            assertNull(logout.getHeader("X-Makerspace-Id"))
            listOf(removal, logout).forEach {
                assertEquals("Bearer original-access", it.getHeader("Authorization"))
                listOf("Cookie", "Origin", "Referer").forEach { name -> assertNull(it.getHeader(name)) }
            }
            assertEquals(listOf(fixture.originalEpoch), fixture.cleanupEpochs)
            assertEquals(1, fixture.provider.stops)
            assertEquals(listOf(fixture.account), fixture.provider.stopOwners)
            assertEquals(1, fixture.nativeClears)
            assertTrue(fixture.persistence.records(fixture.account.key).isEmpty())
            assertTrue(fixture.persistence.consent(fixture.account.key))
            assertNull(fixture.tokens.read()); assertNull(fixture.holder.state.value.user)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test(timeout = 15_000)
    fun replacementDuringDeleteCannotLogoutOrStopSdkForNewInstallation() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val fixture = Fixture(server); fixture.initialize()
            val replacement = TokenPair("new-access", "new-refresh")
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "DELETE") {
                        assertEquals("Bearer original-access", request.getHeader("Authorization"))
                        fixture.tokens.replace(replacement)
                        fixture.selection = 8
                        return MockResponse().setResponseCode(204)
                    }
                    return MockResponse().setResponseCode(500).setBody("Unexpected replacement-account request")
                }
            }
            try {
                withTimeout(5_000) { fixture.holder.signOut() }
                fail("Replacement during cleanup must refuse the old remote logout")
            } catch (failure: CancellationException) {
                assertFalse(failure is kotlinx.coroutines.TimeoutCancellationException)
            }
            val removal = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("DELETE", removal.method)
            assertEquals("/api/v1/integrations/push/devices/71", removal.path)
            assertEquals(3, server.requestCount) // config, original /me, owned DELETE only.
            assertNull("No remote logout may be sent as the replacement account", server.takeRequest(100, TimeUnit.MILLISECONDS))
            assertEquals(replacement, fixture.tokens.read()); assertEquals(8, fixture.selection)
            assertEquals(listOf(fixture.account), fixture.provider.stopOwners)
            assertEquals("The captured old owner must not stop the replacement SDK", 0, fixture.provider.stops)
            assertEquals(0, fixture.nativeClears)
        }
    }

    @Test(timeout = 10_000)
    fun anonymousHolderCannotBorrowForeignInstalledPairForSignOutOrClearItsSelection() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val tokens = MemoryTokenStore(); val session = ApiSession(server.url("/"), tokens)
            var nativeClears = 0; var pushCleanups = 0; var selection: Int? = 8
            val live = GeneratedAuthGateway(session, "org.spaceworks.fixture", EnvironmentEnum.development,
                DevelopmentAttestation(true, true), onNativeSignOut = { nativeClears++ },
                onBeforeOwnedSignOut = { pushCleanups++ })
            // Initialization is anonymous and local here; every attempted logout still reaches
            // the real generated gateway, so zero HTTP calls is a meaningful refusal assertion.
            val api = object : AuthGateway by live {
                override suspend fun config() = PublicConfig(false, 100)
            }
            val holder = AuthStateHolder(api, tokens, readSelection = { selection }, saveSelection = { selection = it })
            session.bindIdentityEpoch { holder.acceptedInstallationEpoch }
            holder.initialize(); assertNull(holder.state.value.user)
            val foreign = TokenPair("foreign-access", "foreign-refresh")
            tokens.replace(foreign); val installed = tokens.snapshot()
            withTimeout(3_000) { holder.signOut() }
            assertEquals(foreign, tokens.read()); assertEquals(installed.epoch, tokens.snapshot().epoch)
            assertEquals(8, selection); assertEquals(0, pushCleanups + nativeClears)
            assertEquals(0, server.requestCount); assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    private class MemoryPersistence : PushPersistence {
        private val consents = mutableMapOf<String, Boolean>()
        private val saved = mutableMapOf<String, List<PushRecord>>()
        override suspend fun consent(account: String) = consents[account] ?: false
        override suspend fun consent(account: String, value: Boolean) { consents[account] = value }
        override suspend fun records(account: String) = saved[account].orEmpty()
        override suspend fun records(account: String, value: List<PushRecord>) { saved[account] = value }
        override suspend fun clear(account: String) { consents.remove(account); saved.remove(account) }
    }

    /** Mirrors the production wrapper's owner predicate; no Firebase or platform globals involved. */
    private class OwnedProvider(private val tokens: MemoryTokenStore) : PushProvider {
        var stops = 0
        val stopOwners = mutableListOf<PushAccount>()
        override suspend fun token(): String = error("Cleanup must not acquire an SDK token")
        override suspend fun stop(): Unit = error("Owned cleanup must pass its captured account/epoch")
        override suspend fun stop(account: PushAccount, expectedEpoch: Long?) {
            stopOwners += account
            requireNotNull(expectedEpoch)
            assertEquals(account.installationEpoch, expectedEpoch)
            if (tokens.snapshot().epoch == expectedEpoch) stops++
        }
    }

    private class Fixture(private val server: MockWebServer) {
        val tokens = MemoryTokenStore().apply { replace(TokenPair("original-access", "original-refresh")) }
        val originalEpoch = tokens.snapshot().epoch
        val account = PushAccount("fixture-account", setOf(7), installationEpoch = originalEpoch)
        val persistence = MemoryPersistence()
        val provider = OwnedProvider(tokens)
        private val session = ApiSession(server.url("/"), tokens) { selection?.toString() }
        private val push = PushStateHolder(GeneratedPushGateway(session, EnvironmentEnum.development), provider, persistence)
        val cleanupEpochs = mutableListOf<Long>()
        var nativeClears = 0
        var selection: Int? = 7
        lateinit var holder: AuthStateHolder
            private set
        init {
            val gateway = GeneratedAuthGateway(session, "org.spaceworks.fixture", EnvironmentEnum.development,
                DevelopmentAttestation(true, true), onNativeSignOut = { nativeClears++ },
                onBeforeOwnedSignOut = { expected ->
                    cleanupEpochs += expected
                    assertNull(holder.acceptedInstallationEpoch)
                    assertNull(holder.state.value.user)
                    push.disable(forgetConsent = false, expectedEpoch = expected)
                    Unit
                })
            holder = AuthStateHolder(gateway, tokens, readSelection = { selection }, saveSelection = { selection = it },
                onSessionEnded = { push.pause() })
            session.bindIdentityEpoch { holder.acceptedInstallationEpoch }
        }
        suspend fun initialize() {
            server.enqueue(json("""{"email_enabled":false,"public_image_max_bytes":100}"""))
            server.enqueue(json(USER))
            holder.initialize()
            assertEquals(originalEpoch, holder.acceptedInstallationEpoch)
            assertEquals("/api/v1/config", requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).path)
            assertEquals("/api/v1/auth/me", requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).path)
            push.activate(account, permission = true)
            persistence.consent(account.key, true)
            persistence.records(account.key, listOf(PushRecord(7, 71, "fixture-token-fingerprint")))
        }
    }

    private companion object {
        fun json(value: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(value)
        const val USER = """{"session_authority":"member","id":1,"username":"member","email":"member@example.invalid","display_name":"Member","phone":"","email_verified":true,"role":"member","is_superuser":false,"must_change_password":false,"makerspaces":[{"id":7,"slug":"workshop","role":"member","role_id":1,"role_ids":[1],"role_slugs":["member"],"role_names":["Member"],"role_name":"Member","role_slug":"member","source":"membership","actions":[],"can_configure_machine_types":false,"is_machine_only":false,"can_refer":false,"can_verify":false,"verified_at":null,"referrals_enabled":false}]}"""
    }
}
