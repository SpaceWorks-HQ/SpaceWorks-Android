package org.spaceworks.app.account

import org.spaceworks.app.member.MemberScope

/** Immutable display projections only. Generated contract types own every wire payload. */
data class AccountSessionRow(val id: String, val kind: String, val current: Boolean, val createdAt: String, val lastUsedAt: String?, val expiresAt: String?)
data class AccountGrantRow(val id: String, val platform: String, val status: String, val createdAt: String, val lastUsedAt: String)
data class AccountProviderRow(val provider: String, val createdAt: String)
data class AccountMembership(val status: String, val renewalCancelledAt: String?)
data class AccountSecurity(val available: Boolean, val enabled: Boolean, val required: Boolean)
data class AccountPhone(val number: String, val verified: Boolean)
data class AccountExport(val id: String, val status: String, val createdAt: String, val expiresAt: String, val failure: String)
data class AccountRows<T>(val items: List<T>, val hasMore: Boolean)
data class AccountCapabilities(val selfService: Boolean, val selfExport: Boolean) {
    companion object { val NONE = AccountCapabilities(false, false); val DEMO = AccountCapabilities(true, true) }
}

interface AccountGateway {
    val isDemo: Boolean get() = false
    suspend fun capabilities(scope: MemberScope): AccountCapabilities
    suspend fun sessions(scope: MemberScope): AccountRows<AccountSessionRow>
    suspend fun grants(scope: MemberScope?): List<AccountGrantRow>
    suspend fun revokeSession(scope: MemberScope, id: String)
    suspend fun revokeGrant(scope: MemberScope?, id: String)
    suspend fun changePassword(scope: MemberScope?, current: String, replacement: String)
    suspend fun validateSession(scope: MemberScope?)
    suspend fun security(scope: MemberScope?): AccountSecurity
    suspend fun disableMFA(scope: MemberScope?, password: String): AccountSecurity
    suspend fun regenerateRecovery(scope: MemberScope?, password: String): List<String>
    suspend fun startPhone(scope: MemberScope?, phone: String)
    suspend fun confirmPhone(scope: MemberScope?, phone: String, code: String): AccountPhone
    suspend fun unlinkPhone(scope: MemberScope?): AccountPhone
    suspend fun providers(scope: MemberScope?): List<AccountProviderRow>
    suspend fun unlinkProvider(scope: MemberScope?, provider: String)
    suspend fun membership(scope: MemberScope): AccountMembership
    suspend fun leave(scope: MemberScope, reason: String): AccountMembership
    suspend fun cancelRenewal(scope: MemberScope, reason: String): AccountMembership
    suspend fun exports(scope: MemberScope): AccountRows<AccountExport>
    suspend fun requestExport(scope: MemberScope): AccountExport
    suspend fun export(scope: MemberScope, id: String): AccountExport
    /** A fresh one-use grant and generated public ZIP operation; never a persisted URL. */
    suspend fun exportArchive(scope: MemberScope, id: String): ByteArray
}

/** Debug-only adapter implements provider previews; release never simulates a provider link. */
interface DemoAccountProviderControl {
    suspend fun previewProvider(provider: String, result: String)
}

/** Inject once per signed-in server/account, shared across makerspaces and route recreation. */
class AccountPhoneCooldown {
    @Volatile var untilMillis: Long = 0
        private set
    @Synchronized fun extend(value: Long) { untilMillis = maxOf(untilMillis, value) }
}
