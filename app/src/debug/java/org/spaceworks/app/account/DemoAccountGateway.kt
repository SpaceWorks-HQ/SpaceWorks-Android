package org.spaceworks.app.account

import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** DEBUG source-set only. Isolated local data; never calls an SDK or server. */
class DemoAccountGateway(private val scenario: String = "normal") : AccountGateway, DemoAccountProviderControl {
    override val isDemo = true
    override suspend fun capabilities(scope: MemberScope): AccountCapabilities { check(); return AccountCapabilities.DEMO }
    private var mfa = AccountSecurity(true, scenario != "noMFA", scenario == "requiredMFA")
    private var phone: AccountPhone? = null
    private var pendingPhone: String? = null
    private var valid = true
    private val memberships = mutableMapOf<Int, AccountMembership>()
    private var identities = listOf(AccountProviderRow("google", CREATED))
    private var sessions = listOf(AccountSessionRow("demo-current-session", "device", true, CREATED, CREATED, null), AccountSessionRow("demo-other-session", "web", false, CREATED, CREATED, null))
    private var grants = listOf(AccountGrantRow("00000000-0000-0000-0000-000000000071", "android", "active", CREATED, CREATED))
    private val exportsByMaker = mutableMapOf<Int, List<AccountExport>>()
    private var exportSequence = 0L
    private fun member(scope: MemberScope) = memberships.getOrPut(scope.id) { AccountMembership("active", null) }
    private fun makerExports(scope: MemberScope) = exportsByMaker[scope.id].orEmpty()
    private fun check() {
        if (scenario == "offline") throw ApiFailure(0, "Couldn't connect. Try again.", "offline", null)
        if (scenario == "throttled") throw ApiFailure(429, "Please wait before trying again.", "throttled", 60)
        if (!valid) throw ApiFailure(401, "Please sign in again.", "session_expired", null)
    }
    private fun checkPassword(value: String) { check(); if (value != "sample") throw ApiFailure(0, "The current password is incorrect.", "password_verification_failed", null) }
    override suspend fun sessions(scope: MemberScope): AccountRows<AccountSessionRow> { check(); return AccountRows(sessions, false) }
    override suspend fun grants(scope: MemberScope?): List<AccountGrantRow> { check(); return grants }
    override suspend fun revokeSession(scope: MemberScope, id: String) { check(); if (sessions.any { it.id == id && it.current }) valid = false; sessions = sessions.filterNot { it.id == id } }
    override suspend fun revokeGrant(scope: MemberScope?, id: String) { check(); grants = grants.filterNot { it.id == id }; valid = false }
    override suspend fun changePassword(scope: MemberScope?, current: String, replacement: String) {
        check()
        if (current != "DemoPass!123") throw ApiFailure(0, "The current password is incorrect.", "password_verification_failed", null)
        // Preview only: no credential is stored, changed, or sent to any SDK/server.
    }
    override suspend fun validateSession(scope: MemberScope?) { check() }
    override suspend fun security(scope: MemberScope?): AccountSecurity { check(); return mfa }
    override suspend fun disableMFA(scope: MemberScope?, password: String): AccountSecurity {
        checkPassword(password)
        if (mfa.required) throw ApiFailure(403, "Your account requires two-step sign-in.", "mfa_required", null)
        mfa = mfa.copy(enabled = false); valid = false; return mfa
    }
    override suspend fun regenerateRecovery(scope: MemberScope?, password: String): List<String> {
        checkPassword(password); valid = false
        return listOf("DEMO-ALPHA-71", "DEMO-BRAVO-72", "DEMO-CHARLIE-73")
    }
    override suspend fun startPhone(scope: MemberScope?, phone: String) { check(); pendingPhone = phone }
    override suspend fun confirmPhone(scope: MemberScope?, phone: String, code: String): AccountPhone {
        check()
        if (phone != pendingPhone || code != "123456") throw ApiFailure(400, "Invalid or expired verification code.", "invalid_code", null)
        return AccountPhone(phone, true).also { this.phone = it; pendingPhone = null }
    }
    override suspend fun unlinkPhone(scope: MemberScope?): AccountPhone { check(); pendingPhone = null; return AccountPhone("", false).also { phone = it } }
    override suspend fun providers(scope: MemberScope?): List<AccountProviderRow> { check(); return identities }
    override suspend fun unlinkProvider(scope: MemberScope?, provider: String) {
        check()
        if (scenario == "lastMethod") throw ApiFailure(409, "Keep another sign-in method before disconnecting this provider.", "last_sign_in_method", null)
        identities = identities.filterNot { it.provider == provider }
    }
    override suspend fun previewProvider(provider: String, result: String) {
        check()
        when (result) {
            "cancel" -> return
            "conflict" -> throw ApiFailure(409, "This sign-in method belongs to another account.", "identity_in_use", null)
            "unavailable" -> throw ApiFailure(503, "This provider is unavailable. Try again later.", "provider_unavailable", null)
            "connected" -> identities = identities.filterNot { it.provider == provider } + AccountProviderRow(provider, CREATED)
        }
    }
    override suspend fun membership(scope: MemberScope): AccountMembership { check(); return member(scope) }
    override suspend fun leave(scope: MemberScope, reason: String): AccountMembership {
        check()
        if (scenario == "blockedMembership") throw ApiFailure(423, "Resolve your outstanding loans before leaving this makerspace.", "membership_blocked", null)
        return AccountMembership("revoked", null).also { memberships[scope.id] = it }
    }
    override suspend fun cancelRenewal(scope: MemberScope, reason: String): AccountMembership { check(); return member(scope).copy(renewalCancelledAt = CREATED).also { memberships[scope.id] = it } }
    override suspend fun exports(scope: MemberScope): AccountRows<AccountExport> { check(); return AccountRows(makerExports(scope), false) }
    override suspend fun requestExport(scope: MemberScope): AccountExport {
        check()
        val id = UUID.nameUUIDFromBytes("demo-account-export-${scope.id}-${++exportSequence}".toByteArray()).toString()
        val row = AccountExport(id, "pending", CREATED, OffsetDateTime.now().plusHours(1).toString(), "")
        exportsByMaker[scope.id] = listOf(row) + makerExports(scope); return row
    }
    override suspend fun export(scope: MemberScope, id: String): AccountExport {
        check()
        val row = makerExports(scope).firstOrNull { it.id == id } ?: throw ApiFailure(404, "Export not found.", "not_found", null)
        return row.copy(status = "available").also { updated -> exportsByMaker[scope.id] = makerExports(scope).map { if (it.id == id) updated else it } }
    }
    override suspend fun exportArchive(scope: MemberScope, id: String): ByteArray {
        check()
        val row = makerExports(scope).firstOrNull { it.id == id } ?: throw ApiFailure(404, "Export not found.", "not_found", null)
        if (row.status != "available") throw ApiFailure(409, "Your export is still preparing.", "export_not_available", null)
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip -> zip.putNextEntry(ZipEntry("README.txt")); zip.write("DEMO EXPORT\nSample data only. No live personal data.\n".toByteArray()); zip.closeEntry() }
        return bytes.toByteArray()
    }
    private companion object { const val CREATED = "2026-10-16T09:00:00Z" }
}
