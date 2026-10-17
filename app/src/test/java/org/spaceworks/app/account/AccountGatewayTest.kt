package org.spaceworks.app.account

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.*
import java.util.Base64
import java.util.concurrent.TimeUnit

class AccountGatewayTest {
    @Test fun zeroMembershipGlobalOperationsKeepBearerButNeverSendInventedMakerspaceHeader() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val tokens = MemoryTokenStore().apply { replace(TokenPair("global-access", "global-refresh")) }
            val gateway = GeneratedAccountGateway(ApiSession(server.url("/"), tokens) { "999" }, { true }, { null })
            fun check(path: String) {
                val value = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals(path, value.path); assertEquals("Bearer global-access", value.getHeader("Authorization"))
                assertNull(value.getHeader("X-Makerspace-Id"))
                for (header in listOf("Cookie", "Origin", "Referer")) assertNull(value.getHeader(header))
            }
            server.enqueue(response(fixture("mfa"))); assertTrue(gateway.security(null).enabled); check("/api/v1/auth/mfa")
            server.enqueue(response(fixture("grants"))); assertEquals(2, gateway.grants(null).size); check("/api/v1/auth/device/grants")
            server.enqueue(response("[${fixture("provider")}]")); assertEquals(1, gateway.providers(null).size); check("/api/v1/auth/social/providers")
            server.enqueue(response("""{"detail":"Sent."}""")); gateway.startPhone(null, "+919876543210"); check("/api/v1/auth/phone/link/start")
            server.enqueue(response("""{"phone_e164":"+919876543210","verified":true}""")); assertTrue(gateway.confirmPhone(null, "+919876543210", "123456").verified); check("/api/v1/auth/phone/link/confirm")
            server.enqueue(response(ME)); gateway.validateSession(null); check("/api/v1/auth/me")
        }
    }
    @Test fun manualPasswordUsesExactGlobalGeneratedBodyAndAwaitsFreshIdentityWithoutInventedRotation() = runBlocking {
        withGateway { gateway, server, tokens ->
            server.enqueue(response("{\"detail\":\"Password changed.\"}")); server.enqueue(response(ME))
            gateway.changePassword(scope, " current with spaces ", " new with spaces ")
            val post = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)); val me = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("POST", post.method); assertEquals("/api/v1/auth/change-password", post.path)
            assertEquals(JsonObject(mapOf("current_password" to JsonPrimitive(" current with spaces "), "new_password" to JsonPrimitive(" new with spaces "))), body(post))
            assertEquals("GET", me.method); assertEquals("/api/v1/auth/me", me.path)
            for (request in listOf(post, me)) {
                assertEquals("Bearer member-access", request.getHeader("Authorization")); assertNull(request.getHeader("X-Makerspace-Id"))
                listOf("Cookie", "Origin", "Referer").forEach { assertNull(request.getHeader(it)) }
            }
            assertEquals("member-access", tokens.read()?.access); assertEquals(2, server.requestCount)
        }
    }
    @Test fun wrongManualPasswordUsesGlobalIdentityProbeAndPreservesValidRotatedSession() = runBlocking {
        withGateway { gateway, server, tokens ->
            server.enqueue(response("{\"detail\":\"Wrong current password.\"}", 401))
            val grant = Json.parseToJsonElement(fixture("grants")).jsonArray.first()
            server.enqueue(response("""{"access":"rotated-access","refresh":"rotated-refresh","device_grant":$grant}"""))
            server.enqueue(response("{\"detail\":\"Wrong current password.\"}", 401)); server.enqueue(response(ME))
            try { gateway.changePassword(scope, "wrong", "replacement"); fail("Wrong password accepted") }
            catch (failure: ApiFailure) { assertEquals(0, failure.status); assertEquals("password_verification_failed", failure.code); assertEquals("Wrong current password.", failure.detail) }
            val requests = (1..4).map { requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            assertEquals(listOf("/api/v1/auth/change-password", "/api/v1/auth/device/refresh", "/api/v1/auth/change-password", "/api/v1/auth/me"), requests.map { it.path })
            requests.forEach { assertNull(it.getHeader("X-Makerspace-Id")); assertNull(it.getHeader("Cookie")) }
            assertEquals("rotated-access", tokens.read()?.access)
        }
    }
    @Test fun postAcknowledgementExpiredIdentityRemains401RatherThanWrongPassword() = runBlocking {
        withGateway { gateway, server, _ ->
            server.enqueue(response("{\"detail\":\"Password changed.\"}")); server.enqueue(response("{\"detail\":\"Expired.\"}", 401))
            server.enqueue(response("{\"detail\":\"Expired refresh.\"}", 400))
            try { gateway.changePassword(scope, "current", "replacement"); fail("Expired session retained") }
            catch (failure: ApiFailure) { assertEquals(401, failure.status); assertNotEquals("password_verification_failed", failure.code) }
            assertEquals(listOf("/api/v1/auth/change-password", "/api/v1/auth/me", "/api/v1/auth/device/refresh"), (1..3).map { requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path })
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun passwordAcknowledgementCannotProbeMeAsReplacementAccountAtSameMakerspace() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val tokens = MemoryTokenStore().apply { replace(TokenPair("member-access", "member-refresh")) }
            var replaced = false
            val gateway = GeneratedAccountGateway(ApiSession(server.url("/"), tokens), { true }, {
                if (server.requestCount == 1 && !replaced) { replaced = true; tokens.replace(TokenPair("new-access", "new-refresh")) }
                scope
            })
            server.enqueue(response("{\"detail\":\"Password changed.\"}"))
            try { gateway.changePassword(scope, "current", "replacement"); fail("Identity read crossed account") } catch (_: CancellationException) { }
            assertTrue(replaced); assertEquals(1, server.requestCount)
            assertEquals("/api/v1/auth/change-password", requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }
    private val scope = MemberScope(7, "workshop")
    private val grantID = "83000000-0000-4000-8000-000000000001"
    private val jobID = "84000000-0000-4000-8000-000000000001"
    private fun fixture(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream("account/$name.json")).bufferedReader().use { it.readText() }
    private fun response(value: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(value)
    private suspend fun withGateway(block: suspend (GeneratedAccountGateway, MockWebServer, MemoryTokenStore) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val tokens = MemoryTokenStore().apply { replace(TokenPair("member-access", "member-refresh")) }
            val gateway = GeneratedAccountGateway(ApiSession(server.url("/"), tokens) { "999" }, { tokens.read() != null }, { scope })
            block(gateway, server, tokens)
        }
    }
    private fun request(server: MockWebServer, method: String, path: String, authenticated: Boolean = true): RecordedRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).also {
        assertEquals(method, it.method); assertEquals(path, it.path)
        assertEquals(if (authenticated) "Bearer member-access" else null, it.getHeader("Authorization"))
        assertEquals(if (authenticated) "7" else null, it.getHeader("X-Makerspace-Id"))
        for (name in listOf("Cookie", "Origin", "Referer")) assertNull(name, it.getHeader(name))
    }
    private fun body(request: RecordedRequest) = Json.parseToJsonElement(request.body.readUtf8()).jsonObject

    @Test fun everyGeneratedAccountOperationUsesItsExactPublishedRouteBodyAndSuccessStatus() = runBlocking {
        withGateway { gateway, server, _ ->
            server.enqueue(response(fixture("sessions")))
            val sessions = gateway.sessions(scope); assertTrue(sessions.items.first().current)
            request(server, "GET", "/api/v1/member/makerspaces/7/account/sessions")
            server.enqueue(response(fixture("grants"))); assertEquals(2, gateway.grants(scope).size)
            request(server, "GET", "/api/v1/auth/device/grants")
            server.enqueue(MockResponse().setResponseCode(204)); gateway.revokeSession(scope, "opaque-session")
            request(server, "DELETE", "/api/v1/member/makerspaces/7/account/sessions/opaque-session")
            server.enqueue(MockResponse().setResponseCode(204)); gateway.revokeGrant(scope, grantID)
            request(server, "DELETE", "/api/v1/auth/device/grants/$grantID")
            server.enqueue(response(ME)); gateway.validateSession(scope); request(server, "GET", "/api/v1/auth/me")
            server.enqueue(response(fixture("mfa"))); assertTrue(gateway.security(scope).enabled)
            request(server, "GET", "/api/v1/auth/mfa")
            server.enqueue(response(fixture("mfa").replace("\"enabled\": true", "\"enabled\": false"))); assertFalse(gateway.disableMFA(scope, "current password").enabled)
            assertEquals(JsonObject(mapOf("password" to JsonPrimitive("current password"))), body(request(server, "POST", "/api/v1/auth/mfa/disable")))
            server.enqueue(response(fixture("recovery"))); assertEquals(3, gateway.regenerateRecovery(scope, "current password").size)
            assertEquals(setOf("password"), body(request(server, "POST", "/api/v1/auth/mfa/recovery")).keys)
            server.enqueue(response("""{"detail":"Sent."}""")); gateway.startPhone(scope, "+919876543210")
            assertEquals(JsonPrimitive("+919876543210"), body(request(server, "POST", "/api/v1/auth/phone/link/start"))["phone"])
            server.enqueue(response("""{"phone_e164":"+919876543210","verified":true}""")); assertTrue(gateway.confirmPhone(scope, "+919876543210", "123456").verified)
            assertEquals(setOf("phone", "code"), body(request(server, "POST", "/api/v1/auth/phone/link/confirm")).keys)
            server.enqueue(response("""{"phone_e164":"","verified":false}""")); assertFalse(gateway.unlinkPhone(scope).verified)
            request(server, "DELETE", "/api/v1/auth/phone")
            server.enqueue(response("[${fixture("provider")}]")); assertEquals("google", gateway.providers(scope).single().provider)
            request(server, "GET", "/api/v1/auth/social/providers")
            server.enqueue(MockResponse().setResponseCode(204)); gateway.unlinkProvider(scope, "google")
            request(server, "DELETE", "/api/v1/auth/social/providers/google")
            server.enqueue(response(fixture("membership"))); assertEquals("active", gateway.membership(scope).status)
            request(server, "GET", "/api/v1/member/makerspaces/7/account/membership")
            server.enqueue(response(fixture("membership").replace("active", "revoked"))); assertEquals("revoked", gateway.leave(scope, "Moving").status)
            assertEquals(JsonObject(mapOf("reason" to JsonPrimitive("Moving"))), body(request(server, "POST", "/api/v1/member/makerspaces/7/account/leave")))
            server.enqueue(response(fixture("membership"))); gateway.cancelRenewal(scope, "No renewal")
            assertEquals(JsonPrimitive("No renewal"), body(request(server, "POST", "/api/v1/member/makerspaces/7/account/cancel"))["reason"])
            server.enqueue(response("""{"count":1,"next":null,"previous":null,"results":[${fixture("export")}]}""")); assertEquals(jobID, gateway.exports(scope).items.single().id)
            request(server, "GET", "/api/v1/member/makerspaces/7/account/exports")
            server.enqueue(response(fixture("export").replace("available", "pending"), 202)); assertEquals("pending", gateway.requestExport(scope).status)
            assertEquals(JsonObject(emptyMap()), body(request(server, "POST", "/api/v1/member/makerspaces/7/account/exports")))
            server.enqueue(response(fixture("export"))); assertEquals("available", gateway.export(scope, jobID).status)
            request(server, "GET", "/api/v1/member/makerspaces/7/account/exports/$jobID")
            val token = "fresh-one-use-token"
            server.enqueue(response("""{"url":"/api/v1/data-exports/download/$jobID/$token","expires_at":"2099-01-01T00:00:00Z"}"""))
            val archive = Base64.getDecoder().decode(Json.parseToJsonElement(fixture("archive")).jsonObject.getValue("base64").jsonPrimitive.content)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/zip").setBody(Buffer().write(archive)))
            assertArrayEquals(archive, gateway.exportArchive(scope, jobID))
            request(server, "POST", "/api/v1/member/makerspaces/7/account/exports/$jobID/download")
            request(server, "GET", "/api/v1/data-exports/download/$jobID/$token", authenticated = false)
        }
    }
    @Test fun generatedBootstrapKeepsSelfExportIndependentFromSelfServiceAndRejectsWrongMaker() = runBlocking {
        withGateway { gateway, server, _ ->
            server.enqueue(response(fixture("bootstrap")))
            assertEquals(AccountCapabilities(false, true), gateway.capabilities(scope))
            request(server, "GET", "/api/v1/bootstrap?slug=workshop")
            server.enqueue(response(fixture("bootstrap").replace("\"id\":7", "\"id\":8")))
            try { gateway.capabilities(scope); fail("Wrong makerspace granted capabilities") } catch (failure: ApiFailure) { assertEquals(403, failure.status) }
            request(server, "GET", "/api/v1/bootstrap?slug=workshop")
        }
    }
    @Test fun wrongManagementPasswordRotatesOnceThenValidIdentityPreservesCredentials() = runBlocking {
        withGateway { gateway, server, tokens ->
            server.enqueue(response("""{"detail":"Wrong password."}""", 401))
            val deviceGrant = Json.parseToJsonElement(fixture("grants")).jsonArray.first()
            server.enqueue(response("""{"access":"rotated-access","refresh":"rotated-refresh","device_grant":$deviceGrant}"""))
            server.enqueue(response("""{"detail":"Wrong password."}""", 401))
            server.enqueue(response(ME))
            try { gateway.regenerateRecovery(scope, "wrong"); fail("Wrong password accepted") }
            catch (failure: ApiFailure) { assertEquals(0, failure.status); assertEquals("password_verification_failed", failure.code) }
            assertEquals("rotated-access", tokens.read()?.access)
            val paths = (1..4).map { requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path }
            assertEquals(listOf("/api/v1/auth/mfa/recovery", "/api/v1/auth/device/refresh", "/api/v1/auth/mfa/recovery", "/api/v1/auth/me"), paths)
        }
    }
    @Test fun unsafeGrantCannotCauseAuthenticatedOrPublicFetchToAnotherResource() = runBlocking {
        withGateway { gateway, server, _ ->
            server.enqueue(response("""{"url":"https://evil.example/api/v1/data-exports/download/$jobID/token","expires_at":"2099-01-01T00:00:00Z"}"""))
            try { gateway.exportArchive(scope, jobID); fail("Unsafe grant accepted") } catch (failure: ApiFailure) { assertEquals("invalid_account_input", failure.code) }
            assertEquals(1, server.requestCount)
            request(server, "POST", "/api/v1/member/makerspaces/7/account/exports/$jobID/download")
        }
    }
    @Test fun staleContextDoesNotSendEvenGlobalAccountOperation() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val tokens = MemoryTokenStore().apply { replace(TokenPair("access", "refresh")) }
            val gateway = GeneratedAccountGateway(ApiSession(server.url("/"), tokens), { true }, { MemberScope(8, "other") })
            try { gateway.revokeGrant(scope, grantID); fail("Stale context sent") } catch (_: CancellationException) { }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun oldAccountGrantCannotStartPublicDownloadAfterNewAccountReplacesCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val tokens = MemoryTokenStore().apply { replace(TokenPair("old-access", "old-refresh")) }
            val ownerCurrent = java.util.concurrent.atomic.AtomicBoolean(true)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    assertEquals("Bearer old-access", request.getHeader("Authorization"))
                    tokens.replace(TokenPair("new-access", "new-refresh"))
                    ownerCurrent.set(false)
                    return response("""{"url":"/api/v1/data-exports/download/$jobID/old-private-grant","expires_at":"2099-01-01T00:00:00Z"}""")
                }
            }
            val gateway = GeneratedAccountGateway(ApiSession(server.url("/"), tokens),
                { ownerCurrent.get() && tokens.read() != null }, { scope })
            try { gateway.exportArchive(scope, jobID); fail("Old owner downloaded after replacement") }
            catch (failure: ApiFailure) { assertEquals(401, failure.status) }
            assertEquals(1, server.requestCount)
            assertEquals("new-access", tokens.read()?.access)
        }
    }
    @Test fun unsignedArchiveSupportsOctetStreamAndRequiresFreshGrantForEveryDownload() = runBlocking {
        withGateway { gateway, server, _ ->
            repeat(2) { index ->
                server.enqueue(response("""{"url":"/api/v1/data-exports/download/$jobID/fresh-$index","expires_at":"2099-01-01T00:00:00Z"}"""))
                server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(Buffer().write(byteArrayOf(80,75,5,6))))
                assertTrue(GeneratedAccountGateway.zip(gateway.exportArchive(scope, jobID)))
                request(server, "POST", "/api/v1/member/makerspaces/7/account/exports/$jobID/download")
                request(server, "GET", "/api/v1/data-exports/download/$jobID/fresh-$index", false)
            }
        }
    }
    @Test fun publicGrantValidationRejectsAmbiguousOriginPathOrToken() {
        val base = "https://spaceworks.example"
        assertEquals("valid", AccountExportGrant.token(base, jobID, "$base:443/api/v1/data-exports/download/$jobID/valid"))
        for (value in listOf("https://evil.example/api/v1/data-exports/download/$jobID/x", "http://spaceworks.example/api/v1/data-exports/download/$jobID/x",
            "$base/api/v1/data-exports/download/$jobID/x?token=y", "$base/api/v1/data-exports/download/$jobID/x#fragment",
            "$base/api/v1/data-exports/download/$jobID/%2Fbad", "$base/api/v1/data-exports/download/$jobID/../bad", "https://user:pass@spaceworks.example/api/v1/data-exports/download/$jobID/x")) {
            try { AccountExportGrant.token(base, jobID, value); fail(value) } catch (_: ApiFailure) { }
        }
    }
    @Test fun expiredGrantAndUnsupportedArchiveCannotPublishDownloadData() = runBlocking {
        withGateway { gateway, server, _ ->
            server.enqueue(response("""{"url":"/api/v1/data-exports/download/$jobID/expired","expires_at":"2020-01-01T00:00:00Z"}"""))
            try { gateway.exportArchive(scope, jobID); fail("Expired grant fetched") } catch (_: ApiFailure) { }
            assertEquals(1, server.requestCount)
            request(server, "POST", "/api/v1/member/makerspaces/7/account/exports/$jobID/download")
            server.enqueue(response("""{"url":"/api/v1/data-exports/download/$jobID/fresh","expires_at":"2099-01-01T00:00:00Z"}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Not an archive</html>"))
            try { gateway.exportArchive(scope, jobID); fail("HTML archive accepted") } catch (_: ApiFailure) { }
            assertEquals(3, server.requestCount)
        }
    }
    @Test(timeout = 15000) fun unknownLengthArchiveCapIsEnforcedWhileReadingAndCancellationStopsSlowStream() = runBlocking {
        withGateway { gateway, server, _ ->
            server.enqueue(response("""{"url":"/api/v1/data-exports/download/$jobID/large","expires_at":"2099-01-01T00:00:00Z"}"""))
            val oversized = Buffer().write(byteArrayOf(80,75,3,4)).write(ByteArray(25 * 1024 * 1024))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/zip").setChunkedBody(oversized, 8192))
            try { gateway.exportArchive(scope, jobID); fail("Oversized archive accepted") } catch (failure: ApiFailure) { assertTrue(failure.detail.contains("exceeds")) }
            request(server, "POST", "/api/v1/member/makerspaces/7/account/exports/$jobID/download")
            request(server, "GET", "/api/v1/data-exports/download/$jobID/large", false)
            server.enqueue(response("""{"url":"/api/v1/data-exports/download/$jobID/slow","expires_at":"2099-01-01T00:00:00Z"}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/zip").setBody(Buffer().write(byteArrayOf(80,75,3,4)).write(ByteArray(32768))).throttleBody(4, 1, TimeUnit.SECONDS))
            val pending = async(Dispatchers.IO) { gateway.exportArchive(scope, jobID) }
            request(server, "POST", "/api/v1/member/makerspaces/7/account/exports/$jobID/download")
            request(server, "GET", "/api/v1/data-exports/download/$jobID/slow", false)
            delay(100) // Enter the blocked body read before cancellation; bounded fixture pacing permits server teardown.
            withTimeout(5_000) { pending.cancelAndJoin() }
            assertTrue(pending.isCancelled)
        }
    }
    @Test fun missingTokenPairInCurrentAccountSignalsSessionExpiryRatherThanSilentlyCancelling() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val gateway = GeneratedAccountGateway(ApiSession(server.url("/")), { false }, { scope })
            try { gateway.security(scope); fail("Missing session accepted") } catch (failure: ApiFailure) { assertEquals(401, failure.status) }
            assertEquals(0, server.requestCount)
        }
    }
    private companion object { const val ME = """{"id":1,"username":"meera","email":"meera@example.invalid","display_name":"Meera Nair","phone":"","email_verified":true,"role":"member","is_superuser":false,"must_change_password":false,"makerspaces":[]}""" }
}
