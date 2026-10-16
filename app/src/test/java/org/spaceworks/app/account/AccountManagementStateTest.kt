package org.spaceworks.app.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.network.Errors
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountManagementStateTest {
    @Test fun zeroMembershipAccountCanManageGlobalSecurityPhoneProvidersAndDevicesWithoutTenantCalls() = runTest {
        val api = FakeAccountGateway(); val state = AccountManagementStateHolder(api)
        state.activateGlobal("https://server.example/account-51")
        state.open(AccountPage.SECURITY); assertNotNull(state.state.value.security)
        state.open(AccountPage.PROVIDERS); assertEquals(1, state.state.value.providers.size)
        state.open(AccountPage.DEVICES); assertEquals(1, state.state.value.grants.size)
        state.open(AccountPage.PHONE); state.startPhone("+91", "9876543210"); state.confirmPhone("123456")
        assertEquals(true, state.state.value.phone?.verified)
        for (page in listOf(AccountPage.SESSIONS, AccountPage.MEMBERSHIP, AccountPage.EXPORTS)) {
            assertFalse(state.pageAvailable(page)); state.open(page)
        }
        state.requestExport(); state.membershipAction("Leaving", true)
        assertEquals(0, api.makerReads); assertTrue(state.state.value.exports.isEmpty())
        state.clear(); assertFalse(state.canRequest()); assertNull(state.state.value.globalAccountKey)
    }
    @Test fun changingGlobalAccountKeyErasesPrivateDataAndDiscardsLateOldIdentityRead() = runTest {
        val api = FakeAccountGateway(); val state = AccountManagementStateHolder(api)
        state.activateGlobal("old-account")
        api.securityGate = CompletableDeferred()
        val pending = async { state.open(AccountPage.SECURITY) }
        testScheduler.runCurrent()
        state.activateGlobal("new-account"); api.securityGate!!.complete(Unit); pending.await()
        assertNull(state.state.value.security); assertEquals("new-account", state.state.value.globalAccountKey)
        assertEquals(AccountPage.MENU, state.state.value.page)
    }
    @Test fun ownedMessagesUseInjectedResourcesWhileRemoteDetailsRemainVerbatim() = runTest {
        val api = FakeAccountGateway()
        val state = AccountManagementStateHolder(api, now = { 1_000L }, text = { "localized:${it.name}" })
        state.activateGlobal("account-51"); state.open(AccountPage.PHONE)
        state.startPhone("+0", "1234567")
        assertEquals("localized:PHONE_INVALID", state.state.value.error)
        state.startPhone("+91", "9876543210")
        assertNull(state.state.value.error)
        assertEquals("localized:PHONE_CODE_SENT", state.state.value.notice)
        api.failure = ApiFailure(400, "Private account lookup detail.", "invalid_code", null)
        state.confirmPhone("123456")
        assertEquals("localized:PHONE_CODE_INVALID", state.state.value.error)
        api.failure = null; state.open(AccountPage.PROVIDERS)
        // Even text equal to the English fallback is a server response, never a translation key.
        api.failure = ApiFailure(409, AccountCopy.PROVIDER_DISCONNECTED.english, "server_conflict", null)
        state.unlinkProvider(api.provider)
        assertEquals(AccountCopy.PROVIDER_DISCONNECTED.english, state.state.value.error)
        assertNull(state.state.value.notice)
    }
    @Test fun manualPasswordValidatesLocallyThenKeepsExactSecretsAndWaitsForOwnerRefresh() = runTest {
        val api = FakeAccountGateway(); var refreshed = 0
        val state = AccountManagementStateHolder(api, onPasswordChanged = { refreshed++ })
        state.activateGlobal("account-51"); state.open(AccountPage.PASSWORD)
        state.changePassword("", "new", "new"); state.changePassword("current", "", ""); state.changePassword("current", "new", "different")
        assertEquals(0, api.passwordChanges); assertEquals(AccountCopy.PASSWORD_CONFIRMATION.english, state.state.value.error)
        state.changePassword(" current with spaces ", " new with spaces ", " new with spaces ")
        assertEquals(1, api.passwordChanges); assertEquals(" current with spaces " to " new with spaces ", api.lastPassword)
        assertEquals(1, refreshed); assertEquals(1L, state.state.value.passwordRevision)
        assertEquals(AccountCopy.PASSWORD_CHANGED.english, state.state.value.notice)
        assertEquals("account-51", state.state.value.globalAccountKey); assertFalse(state.state.value.busy)
    }
    @Test fun manualWrongPasswordPreservesKnownValidAccountAndDoesNotClaimSuccess() = runTest {
        val api = FakeAccountGateway(); var refreshed = false; var signedOut = false
        val state = AccountManagementStateHolder(api, onSignedOut = { signedOut = true }, onPasswordChanged = { refreshed = true })
        state.activateGlobal("account-51"); state.open(AccountPage.PASSWORD)
        api.failure = ApiFailure(0, "Wrong current password.", "password_verification_failed", null)
        state.changePassword("wrong", "replacement", "replacement")
        assertFalse(signedOut); assertFalse(refreshed); assertNull(state.state.value.notice)
        assertEquals("account-51", state.state.value.globalAccountKey); assertEquals("Wrong current password.", state.state.value.error)
        assertEquals(0L, state.state.value.passwordRevision)
    }
    @Test fun deferredOldPasswordCompletionCannotRefreshOrPublishIntoNewAccount() = runTest {
        val api = FakeAccountGateway(); api.passwordGate = CompletableDeferred(); var refreshed = false
        val state = AccountManagementStateHolder(api, onPasswordChanged = { refreshed = true })
        state.activateGlobal("old-account"); state.open(AccountPage.PASSWORD)
        val pending = async { state.changePassword("current", "replacement", "replacement") }; runCurrent()
        state.activateGlobal("new-account"); api.passwordGate!!.complete(Unit); pending.await()
        assertFalse(refreshed); assertNull(state.state.value.notice); assertEquals(0L, state.state.value.passwordRevision)
        assertEquals("new-account", state.state.value.globalAccountKey); assertFalse(state.state.value.busy)
    }
    @Test fun concurrentManualPasswordSubmissionSendsOnlyOneMutation() = runTest {
        val api = FakeAccountGateway(); api.passwordGate = CompletableDeferred()
        val state = AccountManagementStateHolder(api); state.activateGlobal("account-51"); state.open(AccountPage.PASSWORD)
        val pending = async { state.changePassword("current", "replacement", "replacement") }; runCurrent()
        state.changePassword("current", "replacement", "replacement"); assertEquals(1, api.passwordChanges)
        api.passwordGate!!.complete(Unit); pending.await(); assertEquals(1L, state.state.value.passwordRevision)
    }
    @Test fun manualPasswordThrottleRequiresExplicitSubmissionAfterDeadline() = runTest {
        val api = FakeAccountGateway(); var clock = 1000L
        val state = AccountManagementStateHolder(api, now = { clock }); state.activateGlobal("account-51"); state.open(AccountPage.PASSWORD)
        api.failure = ApiFailure(429, "Please wait.", "throttled", 10)
        state.changePassword("current", "replacement", "replacement"); assertEquals(11000L, state.state.value.retryAtMillis)
        state.changePassword("current", "replacement", "replacement"); assertEquals(1, api.passwordChanges)
        clock = 11000; api.failure = null
        assertEquals(1, api.passwordChanges); state.changePassword("current", "replacement", "replacement")
        assertEquals(2, api.passwordChanges); assertEquals(1L, state.state.value.passwordRevision)
    }
    @Test fun taggedFallbackUsesCoreResolverWhileIdenticalEnglishServerDetailStaysVerbatim() = runTest {
        val api = FakeAccountGateway()
        val state = AccountManagementStateHolder(api, failureText = MobileText { key, args -> "resolved:${key.english(*args)}" })
        state.activateGlobal("account-51"); state.open(AccountPage.SECURITY)
        val fallback = Errors.map(409, null, null); assertNotNull(fallback.localCopy)
        api.failure = fallback; state.refresh(); assertEquals("resolved:${fallback.detail}", state.state.value.error)
        api.failure = Errors.map(409, "{\"detail\":\"${fallback.detail}\"}", null)
        assertNull(api.failure!!.localCopy); state.refresh(); assertEquals(fallback.detail, state.state.value.error)
    }
    private val maker = MemberScope(7, "workshop")
    private fun holder(api: FakeAccountGateway, signedOut: suspend () -> Unit = {}, changed: suspend () -> Unit = {},
        ended: suspend (List<String>, String?) -> Unit = { _, _ -> }, cooldown: AccountPhoneCooldown = AccountPhoneCooldown(), now: () -> Long = { 1_000L }) =
        AccountManagementStateHolder(api, signedOut, changed, ended, now, cooldown).also { it.activate(maker) }

    @Test fun currentSessionRevocationClearsEveryPrivateFieldBeforeSignOut() = runTest {
        val api = FakeAccountGateway(); var callback = false
        lateinit var state: AccountManagementStateHolder
        state = holder(api, signedOut = { callback = true; assertNull(state.state.value.scope); assertTrue(state.state.value.sessions.isEmpty()) })
        state.open(AccountPage.SESSIONS); state.revokeSession(api.current)
        assertTrue(callback); assertEquals(listOf(api.current.id), api.revokedSessions)
    }
    @Test fun otherSessionRevocationKeepsCurrentSessionAndAccount() = runTest {
        val api = FakeAccountGateway(); var signedOut = false
        val state = holder(api, signedOut = { signedOut = true }); state.open(AccountPage.SESSIONS); state.revokeSession(api.other)
        assertFalse(signedOut); assertEquals(maker, state.state.value.scope); assertEquals(listOf(api.current), state.state.value.sessions)
    }
    @Test fun unknownGrantIdentityProbesSessionAndClearsIfItCannotBeVerified() = runTest {
        val api = FakeAccountGateway(); api.validSession = false; var signedOut = false
        val state = holder(api, signedOut = { signedOut = true }); state.open(AccountPage.DEVICES); state.revokeGrant(api.grant)
        assertTrue(signedOut); assertEquals(1, api.probes); assertNull(state.state.value.scope)
    }
    @Test fun knownOtherGrantDoesNotInferIdentityFromPlatformOrIdOrder() = runTest {
        val api = FakeAccountGateway(); var signedOut = false
        val state = holder(api, signedOut = { signedOut = true }); state.open(AccountPage.DEVICES); state.revokeGrant(api.grant)
        assertFalse(signedOut); assertEquals(1, api.probes); assertTrue(state.state.value.grants.isEmpty())
    }
    @Test fun wrongManagementPasswordKeepsSeparatelyVerifiedSession() = runTest {
        val api = FakeAccountGateway(); var signedOut = false
        val state = holder(api, signedOut = { signedOut = true }); state.open(AccountPage.SECURITY)
        api.failure = ApiFailure(0, "Wrong current password.", "password_verification_failed", null)
        state.regenerateRecovery("wrong")
        assertFalse(signedOut); assertEquals(maker, state.state.value.scope); assertEquals("Wrong current password.", state.state.value.error)
    }
    @Test fun recoveryReplacementHandsCodesToUnauthenticatedOwnerAfterClearingScope() = runTest {
        val api = FakeAccountGateway(); var codes = emptyList<String>()
        lateinit var state: AccountManagementStateHolder
        state = holder(api, ended = { values, _ -> assertNull(state.state.value.scope); codes = values })
        state.open(AccountPage.SECURITY); state.regenerateRecovery("correct")
        assertEquals(listOf("DEMO-ONE", "DEMO-TWO"), codes); assertNull(state.state.value.security)
    }
    @Test fun requiredMFARefusesDisableWithoutCallingServer() = runTest {
        val api = FakeAccountGateway(); api.mfa = AccountSecurity(true, true, true)
        val state = holder(api); state.open(AccountPage.SECURITY); state.disableMFA("correct")
        assertEquals(0, api.disableCalls); assertEquals(maker, state.state.value.scope)
    }
    @Test fun verifiedPhoneUsesExactPendingCanonicalNumberAndUniformWrongCode() = runTest {
        val api = FakeAccountGateway(); val state = holder(api); state.open(AccountPage.PHONE)
        state.startPhone("+91", "98765 43210"); assertEquals("+919876543210", state.state.value.pendingPhone)
        api.failure = ApiFailure(400, "Code lookup secret.", "invalid_code", null); state.confirmPhone("123456")
        assertEquals("Invalid or expired verification code.", state.state.value.error); assertNull(state.state.value.phone)
        api.failure = null; state.confirmPhone("123456")
        assertTrue(state.state.value.phone?.verified == true); assertNull(state.state.value.pendingPhone)
    }
    @Test fun phoneCooldownSurvivesRouteRecreationAndMakerspaceSwitchAndResendUsesPendingNumber() = runTest {
        val api = FakeAccountGateway(); val cooldown = AccountPhoneCooldown(); var clock = 1000L
        val first = holder(api, cooldown = cooldown, now = { clock }); first.open(AccountPage.PHONE); first.startPhone("+44", "7700900123")
        first.activate(MemberScope(8, "studio")); first.open(AccountPage.PHONE); first.startPhone("+44", "7700900123")
        assertEquals(1, api.phoneSends)
        val second = holder(api, cooldown = cooldown, now = { clock }); second.open(AccountPage.PHONE); second.startPhone("+44", "7700900123")
        assertEquals(1, api.phoneSends)
        clock += 60001; second.startPhone("+44", "7700900123"); clock += 60001; second.resendPhone()
        assertEquals(3, api.phoneSends); assertEquals("+447700900123", api.lastPhone)
    }
    @Test fun phoneValidationAndVerificationCannotPublishMalformedOrMismatchedNumber() = runTest {
        val api = FakeAccountGateway(); val state = holder(api); state.open(AccountPage.PHONE)
        state.startPhone("+0", "1234567"); assertEquals(0, api.phoneSends)
        state.startPhone("+91", "123456789012345"); assertEquals(0, api.phoneSends)
        state.startPhone("+91", "9876543210"); api.confirmedPhone = AccountPhone("+14155550123", true); state.confirmPhone("123456")
        assertNull(state.state.value.phone); assertNotNull(state.state.value.error)
    }
    @Test fun phoneAutofillAcceptsMatchingE164WithoutDuplicatingCountryCode() {
        assertEquals("+919876543210", AccountManagementStateHolder.canonicalPhone("+91", "+91 98765 43210"))
        assertEquals("+447700900123", AccountManagementStateHolder.canonicalPhone("44", "+44 (7700) 900123"))
        assertNull(AccountManagementStateHolder.canonicalPhone("+91", "+44 7700900123"))
        assertNull(AccountManagementStateHolder.canonicalPhone("+91", "+91+9876543210"))
    }
    @Test fun accountModuleAndExportGatesAreIndependentAndBlockDisabledOperations() = runTest {
        val api = FakeAccountGateway(); api.capability = AccountCapabilities(false, true)
        val state = holder(api); state.open(AccountPage.MEMBERSHIP)
        assertNull(state.state.value.membership); assertFalse(state.pageAvailable(AccountPage.SESSIONS))
        state.open(AccountPage.EXPORTS); state.requestExport(); assertEquals(1, state.state.value.exports.size)
        assertTrue(state.pageAvailable(AccountPage.SECURITY))
        api.capability = AccountCapabilities.NONE; state.loadCapabilities(); state.requestExport()
        assertFalse(state.pageAvailable(AccountPage.EXPORTS)); assertTrue(state.state.value.exports.isEmpty()); assertEquals(AccountPage.MENU, state.state.value.page)
    }
    @Test fun unlinkPhoneNeedsServerToConfirmEmptyUnverifiedStatus() = runTest {
        val api = FakeAccountGateway(); val state = holder(api); state.open(AccountPage.PHONE)
        api.unlinkedPhone = AccountPhone("+919876543210", true); state.unlinkPhone(); assertNotNull(state.state.value.error)
        api.unlinkedPhone = AccountPhone("", false); state.unlinkPhone(); assertEquals(false, state.state.value.phone?.verified)
    }
    @Test fun lastProviderConflictPreservesExistingSignInMethod() = runTest {
        val api = FakeAccountGateway(); val state = holder(api); state.open(AccountPage.PROVIDERS)
        api.failure = ApiFailure(409, "Keep another sign-in method.", "last_sign_in_method", null); state.unlinkProvider(api.provider)
        assertEquals(listOf(api.provider), state.state.value.providers); assertNotNull(state.state.value.error)
    }
    @Test fun blockedLeaveDoesNotClaimMembershipEndedAndCancelKeepsCurrentTerm() = runTest {
        val api = FakeAccountGateway(); var changed = 0; val state = holder(api, changed = { changed++ }); state.open(AccountPage.MEMBERSHIP)
        api.failure = ApiFailure(423, "Return outstanding equipment.", "blocked", null); state.membershipAction("Leaving", true)
        assertEquals(0, changed); assertEquals("active", state.state.value.membership?.status)
        api.failure = null; state.membershipAction("No renewal", false)
        assertEquals(0, changed); assertEquals("active", state.state.value.membership?.status); assertNotNull(state.state.value.membership?.renewalCancelledAt)
        state.membershipAction("Leaving", true); assertEquals(1, changed); assertNull(state.state.value.scope)
    }
    @Test fun successfulHTTPLeaveWithStillActiveStatusDoesNotEndMembership() = runTest {
        val api = FakeAccountGateway(); api.leaveStatus = "active"; var changed = false
        val state = holder(api, changed = { changed = true }); state.open(AccountPage.MEMBERSHIP); state.membershipAction("Leave", true)
        assertFalse(changed); assertEquals(maker, state.state.value.scope); assertTrue(state.state.value.notice!!.contains("still active"))
    }
    @Test fun exportIsPendingUntilExplicitFreshCheckAndOnlyAvailableArchiveDownloads() = runTest {
        val api = FakeAccountGateway(); val state = holder(api); state.open(AccountPage.EXPORTS); state.requestExport()
        val pending = state.state.value.exports.single(); assertEquals("pending", pending.status)
        state.downloadExport(pending); assertEquals(0, api.downloads)
        state.checkExport(pending); state.downloadExport(state.state.value.exports.single())
        assertEquals(1, api.downloads); assertNotNull(state.state.value.archive); state.clear(); assertNull(state.state.value.archive)
    }
    @Test fun throttlingBlocksAllExtraMutationsAndSharesPhoneCooldown() = runTest {
        val api = FakeAccountGateway(); val cooldown = AccountPhoneCooldown(); val state = holder(api, cooldown = cooldown)
        state.open(AccountPage.PHONE); api.failure = ApiFailure(429, "Wait.", "throttled", 60); state.startPhone("+91", "9876543210")
        assertFalse(state.canRequest()); assertEquals(61000L, cooldown.untilMillis)
        state.startPhone("+91", "9876543210"); assertEquals(1, api.phoneSends)
    }
    @Test fun invalidPhoneCodeWithoutRetryAfterStaysImmediatelyEditableAndRetryable() = runTest {
        val api=FakeAccountGateway();val state=holder(api,now={1_000L})
        state.open(AccountPage.PHONE);state.startPhone("+91","9876543210")
        api.failure=ApiFailure(400,"Invalid or expired verification code.","invalid_code",null)
        state.confirmPhone("000000")
        assertEquals(0L,state.state.value.retryAtMillis);assertFalse(state.state.value.busy)
        assertNotNull(state.state.value.pendingPhone);assertTrue(state.canRequest())
        api.failure=null;state.confirmPhone("123456")
        assertEquals(true,state.state.value.phone?.verified);assertNull(state.state.value.pendingPhone)
    }
    @Test fun missingRetryAfterUsesConservativeCooldownWhileExplicitZeroRemainsValid() = runTest {
        val api = FakeAccountGateway(); var clock = 1000L
        val state = holder(api, now = { clock }); state.open(AccountPage.PHONE)
        api.failure = ApiFailure(429, "Please wait.", "throttled", null)
        state.startPhone("+91", "9876543210")
        assertEquals(61000L, state.state.value.retryAtMillis)
        assertEquals(61000L, state.state.value.phoneRetryAtMillis)
        assertEquals("Please wait.", state.state.value.error)
        assertFalse(state.canRequest()); state.startPhone("+91", "9876543210")
        assertEquals(1, api.phoneSends)
        clock = 61000L; api.failure = null; state.startPhone("+91", "9876543210")
        assertEquals(2, api.phoneSends)
        state.open(AccountPage.SECURITY)
        api.failure = ApiFailure(429, "Retry now.", "throttled", 0)
        state.refresh()
        assertEquals(clock, state.state.value.retryAtMillis); assertTrue(state.canRequest())
    }
    @Test fun lateReadAfterScopeChangeCannotPublishPrivateAccountRows() = runTest {
        val api = FakeAccountGateway(); api.sessionGate = CompletableDeferred()
        val state = holder(api); val pending = async { state.open(AccountPage.SESSIONS) }
        kotlinx.coroutines.yield(); state.activate(MemberScope(8, "studio")); api.sessionGate!!.complete(Unit); pending.await()
        assertTrue(state.state.value.sessions.isEmpty()); assertEquals(8, state.state.value.scope?.id)
    }
    @Test fun expiredSessionClearsAllDataBeforeReauthentication() = runTest {
        val api = FakeAccountGateway(); var ended = false; val state = holder(api, signedOut = { ended = true })
        state.open(AccountPage.SESSIONS); api.failure = ApiFailure(401, "Expired.", "expired", null); state.refresh()
        assertTrue(ended); assertTrue(state.state.value.sessions.isEmpty()); assertTrue(state.state.value.grants.isEmpty())
    }
}

private class FakeAccountGateway : AccountGateway {
    var makerReads = 0
    var passwordChanges = 0
    var lastPassword: Pair<String, String>? = null
    var passwordGate: CompletableDeferred<Unit>? = null
    var securityGate: CompletableDeferred<Unit>? = null
    var capability = AccountCapabilities.DEMO
    override suspend fun capabilities(scope: MemberScope): AccountCapabilities { makerReads++; check(); return capability }
    var failure: ApiFailure? = null
    var validSession = true; var probes = 0; var disableCalls = 0; var phoneSends = 0; var downloads = 0
    var lastPhone: String? = null; var confirmedPhone: AccountPhone? = null; var unlinkedPhone = AccountPhone("", false)
    var mfa = AccountSecurity(true, true, false); var leaveStatus = "revoked"
    var sessionGate: CompletableDeferred<Unit>? = null
    val current = AccountSessionRow("opaque-current", "device", true, "2026-10-16T09:00:00Z", null, null)
    val other = AccountSessionRow("opaque-other", "web", false, "2026-10-16T09:00:00Z", null, null)
    val grant = AccountGrantRow("00000000-0000-0000-0000-000000000071", "android", "active", "2026-10-16T09:00:00Z", "2026-10-16T09:00:00Z")
    val provider = AccountProviderRow("google", "2026-10-16T09:00:00Z")
    val revokedSessions = mutableListOf<String>()
    private fun check() { failure?.let { throw it } }
    override suspend fun sessions(scope: MemberScope): AccountRows<AccountSessionRow> { sessionGate?.await(); check(); return AccountRows(listOf(current, other), false) }
    override suspend fun grants(scope: MemberScope?): List<AccountGrantRow> { check(); return listOf(grant) }
    override suspend fun revokeSession(scope: MemberScope, id: String) { check(); revokedSessions += id }
    override suspend fun revokeGrant(scope: MemberScope?, id: String) { check() }
    override suspend fun changePassword(scope: MemberScope?, current: String, replacement: String) { passwordChanges++; lastPassword = current to replacement; passwordGate?.await(); check() }
    override suspend fun validateSession(scope: MemberScope?) { probes++; if (!validSession) throw ApiFailure(401, "Expired", "expired", null) }
    override suspend fun security(scope: MemberScope?): AccountSecurity { securityGate?.await(); check(); return mfa }
    override suspend fun disableMFA(scope: MemberScope?, password: String): AccountSecurity { disableCalls++; check(); return mfa.copy(enabled = false) }
    override suspend fun regenerateRecovery(scope: MemberScope?, password: String): List<String> { check(); return listOf("DEMO-ONE", "DEMO-TWO") }
    override suspend fun startPhone(scope: MemberScope?, phone: String) { phoneSends++; lastPhone = phone; check() }
    override suspend fun confirmPhone(scope: MemberScope?, phone: String, code: String): AccountPhone { check(); return confirmedPhone ?: AccountPhone(phone, true) }
    override suspend fun unlinkPhone(scope: MemberScope?): AccountPhone { check(); return unlinkedPhone }
    override suspend fun providers(scope: MemberScope?): List<AccountProviderRow> { check(); return listOf(provider) }
    override suspend fun unlinkProvider(scope: MemberScope?, provider: String) { check() }
    override suspend fun membership(scope: MemberScope): AccountMembership { check(); return AccountMembership("active", null) }
    override suspend fun leave(scope: MemberScope, reason: String): AccountMembership { check(); return AccountMembership(leaveStatus, null) }
    override suspend fun cancelRenewal(scope: MemberScope, reason: String): AccountMembership { check(); return AccountMembership("active", "2026-10-16T09:00:00Z") }
    override suspend fun exports(scope: MemberScope): AccountRows<AccountExport> { check(); return AccountRows(emptyList(), false) }
    override suspend fun requestExport(scope: MemberScope): AccountExport { check(); return AccountExport("job", "pending", "2026-10-16T09:00:00Z", "2099-01-01T00:00:00Z", "") }
    override suspend fun export(scope: MemberScope, id: String): AccountExport { check(); return AccountExport(id, "available", "2026-10-16T09:00:00Z", "2099-01-01T00:00:00Z", "") }
    override suspend fun exportArchive(scope: MemberScope, id: String): ByteArray { check(); downloads++; return byteArrayOf(80, 75, 5, 6) }
}
