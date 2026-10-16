package org.spaceworks.app.account
import kotlinx.coroutines.CompletableDeferred
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
internal class OwnerFakeAccountGateway : AccountGateway {
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
