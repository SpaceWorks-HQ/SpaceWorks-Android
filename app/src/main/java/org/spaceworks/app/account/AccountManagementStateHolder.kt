package org.spaceworks.app.account

import org.spaceworks.app.network.retryDeadlineMillis

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.copy.apiFailure
import org.spaceworks.app.platform.MobileText
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicLong

enum class AccountPage { MENU, PASSWORD, SECURITY, PHONE, PROVIDERS, DEVICES, SESSIONS, MEMBERSHIP, EXPORTS }
data class AccountManagementState(
    val scope: MemberScope? = null, val page: AccountPage = AccountPage.MENU,
    val globalAccountKey: String? = null,
    val busy: Boolean = false, val loading: Boolean = false, val downloading: Boolean = false,
    val error: String? = null, val notice: String? = null, val retryAtMillis: Long = 0,
    val phoneRetryAtMillis: Long = 0, val pendingPhone: String? = null, val phone: AccountPhone? = null,
    val security: AccountSecurity? = null, val sessions: List<AccountSessionRow> = emptyList(),
    val grants: List<AccountGrantRow> = emptyList(), val providers: List<AccountProviderRow> = emptyList(),
    val membership: AccountMembership? = null, val exports: List<AccountExport> = emptyList(),
    val sessionsHaveMore: Boolean = false, val exportsHaveMore: Boolean = false,
    val archive: ByteArray? = null,
    val passwordRevision: Long = 0,
    val capabilities: AccountCapabilities? = null, val capabilitiesLoading: Boolean = false,
)

/** Account-owned state; the controller clears it before switching account or makerspace. */
class AccountManagementStateHolder(
    private val api: AccountGateway,
    private val onSignedOut: suspend () -> Unit = {},
    private val onMembershipChanged: suspend () -> Unit = {},
    private val onSecurityEnded: suspend (List<String>, String?) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
    private val phoneCooldown: AccountPhoneCooldown = AccountPhoneCooldown(),
    private val text: (AccountCopy) -> String = { it.english },
    private val onPasswordChanged: suspend () -> Unit = {},
    private val failureText: MobileText = MobileText.ENGLISH,
    private val onConfirmedMembershipLeft: suspend (MemberScope) -> Unit = { onMembershipChanged() },
) {
    private val mutable = MutableStateFlow(AccountManagementState())
    val state = mutable.asStateFlow()
    val isDemo get() = api.isDemo
    private val epoch = AtomicLong()
    private val readVersion = AtomicLong()
    private val capabilityVersion = AtomicLong()
    private val mutation = Mutex()
    fun clear() { epoch.incrementAndGet(); readVersion.incrementAndGet(); capabilityVersion.incrementAndGet(); mutable.value = AccountManagementState() }
    fun activate(scope: MemberScope) {
        if (mutable.value.scope != scope) {
            val retry = mutable.value.retryAtMillis
            clear()
            mutable.value = AccountManagementState(scope = scope, retryAtMillis = retry, phoneRetryAtMillis = phoneCooldown.untilMillis)
        }
    }
    fun activateGlobal(accountKey: String) {
        require(accountKey.isNotBlank())
        if (state.value.globalAccountKey != accountKey || state.value.scope != null) {
            clear()
            mutable.value = AccountManagementState(globalAccountKey = accountKey, capabilities = AccountCapabilities.NONE,
                phoneRetryAtMillis = phoneCooldown.untilMillis)
        }
    }
    fun close(): Boolean {
        if (state.value.busy || state.value.downloading) return false
        readVersion.incrementAndGet()
        mutable.update { it.copy(page = AccountPage.MENU, error = null, notice = null, loading = false, archive = null) }
        return true
    }
    fun dismissArchive() { mutable.update { it.copy(archive = null) } }
    fun clearPendingPhone() { if (!state.value.busy) mutable.update { it.copy(pendingPhone = null, error = null, notice = null) } }
    fun canRequest(): Boolean = (state.value.scope != null || state.value.globalAccountKey != null) && !state.value.busy && !state.value.downloading && now() >= state.value.retryAtMillis
    private fun current(value: Long) = epoch.get() == value && (state.value.scope != null || state.value.globalAccountKey != null)
    private fun maker(scope: MemberScope?): MemberScope = requireNotNull(scope) { text(AccountCopy.MAKER_REQUIRED) }
    fun pageAvailable(page: AccountPage): Boolean = when (page) {
        AccountPage.SESSIONS, AccountPage.MEMBERSHIP -> state.value.capabilities?.selfService == true
        AccountPage.EXPORTS -> state.value.capabilities?.selfExport == true
        else -> true
    }
    suspend fun loadCapabilities() {
        if (!canRequest() || state.value.capabilitiesLoading) return
        val scope = state.value.scope ?: run { mutable.update { it.copy(capabilities = AccountCapabilities.NONE) }; return }
        val expected = epoch.get(); val version = capabilityVersion.incrementAndGet()
        mutable.update { it.copy(capabilitiesLoading = true) }
        try {
            val value = api.capabilities(scope)
            if (current(expected) && version == capabilityVersion.get()) mutable.update {
                val pageAllowed = when (it.page) {
                    AccountPage.SESSIONS, AccountPage.MEMBERSHIP -> value.selfService
                    AccountPage.EXPORTS -> value.selfExport
                    else -> true
                }
                it.copy(capabilities = value, page = if (pageAllowed) it.page else AccountPage.MENU,
                    sessions = if (value.selfService) it.sessions else emptyList(),
                    membership = if (value.selfService) it.membership else null,
                    exports = if (value.selfExport) it.exports else emptyList(), archive = if (value.selfExport) it.archive else null,
                    sessionsHaveMore = value.selfService && it.sessionsHaveMore, exportsHaveMore = value.selfExport && it.exportsHaveMore)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (current(expected) && version == capabilityVersion.get()) fail(failure, expected) }
        finally { if (current(expected) && version == capabilityVersion.get()) mutable.update { it.copy(capabilitiesLoading = false) } }
    }
    suspend fun open(page: AccountPage) {
        if (!canRequest()) return
        if (page in listOf(AccountPage.SESSIONS, AccountPage.MEMBERSHIP, AccountPage.EXPORTS)) {
            if (state.value.capabilities == null) loadCapabilities()
            if (!pageAvailable(page)) { mutable.update { it.copy(error = text(AccountCopy.SERVICE_DISABLED)) }; return }
        }
        mutable.update { it.copy(page = page, error = null, notice = null, archive = null) }
        refresh()
    }
    suspend fun refresh() {
        val before = state.value
        val scope = before.scope
        if (!canRequest()) return
        if (!pageAvailable(before.page)) return
        val expected = epoch.get(); val version = readVersion.incrementAndGet()
        fun fresh() = current(expected) && readVersion.get() == version && state.value.page == before.page
        mutable.update { it.copy(loading = true, error = null) }
        try {
            when (before.page) {
                AccountPage.SECURITY -> { val value = api.security(scope); if (fresh()) mutable.update { it.copy(security = value) } }
                AccountPage.SESSIONS -> {
                    val sessions = api.sessions(maker(scope)); if (fresh()) mutable.update { it.copy(sessions = sessions.items, sessionsHaveMore = sessions.hasMore) }
                }
                AccountPage.DEVICES -> { val values = api.grants(scope); if (fresh()) mutable.update { it.copy(grants = values) } }
                AccountPage.PROVIDERS -> { val values = api.providers(scope); if (fresh()) mutable.update { it.copy(providers = values) } }
                AccountPage.MEMBERSHIP -> { val value = api.membership(maker(scope)); if (fresh()) mutable.update { it.copy(membership = value) } }
                AccountPage.EXPORTS -> { val values = api.exports(maker(scope)); if (fresh()) mutable.update { it.copy(exports = values.items, exportsHaveMore = values.hasMore) } }
                else -> Unit
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (fresh()) fail(failure, expected) }
        finally { if (fresh()) mutable.update { it.copy(loading = false) } }
    }
    private suspend fun <T> act(work: suspend (MemberScope?) -> T, success: suspend (T, Long) -> Unit) {
        if (!canRequest() || !mutation.tryLock()) return
        val scope = state.value.scope
        val expected = epoch.get()
        readVersion.incrementAndGet()
        mutable.update { it.copy(busy = true, loading = false, error = null, notice = null, archive = null) }
        try { val value = work(scope); if (current(expected)) success(value, expected) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (current(expected)) fail(failure, expected) }
        finally { if (current(expected)) mutable.update { it.copy(busy = false) }; mutation.unlock() }
    }
    suspend fun revokeSession(row: AccountSessionRow) {
        if (!pageAvailable(AccountPage.SESSIONS)) return
        if (state.value.sessions.none { it.id == row.id }) return
        act({ api.revokeSession(maker(it), row.id) }) { _, _ ->
            if (row.current) { clear(); onSignedOut() }
            else mutable.update { it.copy(sessions = it.sessions.filterNot { value -> value.id == row.id }, notice = text(AccountCopy.SESSION_REVOKED)) }
        }
    }
    suspend fun revokeGrant(row: AccountGrantRow) {
        if (state.value.grants.none { it.id == row.id }) return
        act({ scope ->
            api.revokeGrant(scope, row.id)
            try { api.validateSession(scope); true } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        }) { retained, _ ->
            if (!retained) { clear(); onSignedOut() }
            else mutable.update { it.copy(grants = it.grants.filterNot { value -> value.id == row.id }, notice = text(AccountCopy.DEVICE_REVOKED)) }
        }
    }
    suspend fun changePassword(current: String, replacement: String, confirmation: String) {
        if (state.value.page != AccountPage.PASSWORD || !canRequest() || !validPassword(current)) return
        if (replacement.isEmpty() || replacement.length > 1024) {
            mutable.update { it.copy(error = text(AccountCopy.PASSWORD_NEW_REQUIRED)) }; return
        }
        if (replacement != confirmation) {
            mutable.update { it.copy(error = text(AccountCopy.PASSWORD_CONFIRMATION)) }; return
        }
        act({ api.changePassword(it, current, replacement) }) { _, expected ->
            // Gateway has awaited a fresh identity; the owner refreshes its Auth authority/flags.
            // No password, confirmation, grant or new-token assumption enters account state.
            onPasswordChanged()
            if (this.current(expected)) mutable.update { it.copy(passwordRevision = it.passwordRevision + 1,
                notice = text(if (api.isDemo) AccountCopy.DEMO_PASSWORD_CHANGED else AccountCopy.PASSWORD_CHANGED)) }
        }
    }
    suspend fun regenerateRecovery(password: String) {
        if (state.value.security?.let { it.enabled && it.available } != true || !validPassword(password)) return
        act({ api.regenerateRecovery(it, password) }) { codes, _ ->
            clear()
            onSecurityEnded(codes, text(AccountCopy.RECOVERY_CHANGED))
        }
    }
    suspend fun disableMFA(password: String) {
        val security = state.value.security ?: return
        if (security.required || !security.enabled || !validPassword(password)) return
        act({ api.disableMFA(it, password) }) { value, _ ->
            if (value.enabled) throw ApiFailure(0, text(AccountCopy.MFA_STILL_ENABLED), "mfa_not_disabled", null)
            clear(); onSecurityEnded(emptyList(), text(AccountCopy.MFA_CHANGED))
        }
    }
    private fun validPassword(value: String): Boolean {
        if (value.isNotEmpty() && value.length <= 1024) return true
        mutable.update { it.copy(error = text(AccountCopy.PASSWORD_REQUIRED)) }; return false
    }
    suspend fun startPhone(callingCode: String, nationalNumber: String) {
        val canonical = canonicalPhone(callingCode, nationalNumber)
        if (canonical == null) { mutable.update { it.copy(error = text(AccountCopy.PHONE_INVALID)) }; return }
        sendPhone(canonical)
    }
    suspend fun resendPhone() { state.value.pendingPhone?.let { sendPhone(it) } }
    private suspend fun sendPhone(canonical: String) {
        if (now() < phoneCooldown.untilMillis) { mutable.update { it.copy(phoneRetryAtMillis = phoneCooldown.untilMillis) }; return }
        act({ scope -> api.startPhone(scope, canonical) }) { _, _ ->
            phoneCooldown.extend(now() + 60_000)
            mutable.update { it.copy(pendingPhone = canonical, phoneRetryAtMillis = phoneCooldown.untilMillis, notice = text(AccountCopy.PHONE_CODE_SENT)) }
        }
    }
    suspend fun confirmPhone(code: String) {
        val phone = state.value.pendingPhone ?: return
        if (code.length != 6 || !code.all { it in '0'..'9' }) { mutable.update { it.copy(error = text(AccountCopy.PHONE_CODE_REQUIRED)) }; return }
        act({ scope ->
            try { api.confirmPhone(scope, phone, code) }
            catch (failure: ApiFailure) { if (failure.status == 400) throw failure.copy(detail = text(AccountCopy.PHONE_CODE_INVALID), localCopy = null); throw failure }
        }) { result, _ ->
            if (!result.verified || result.number != phone) throw ApiFailure(0, text(AccountCopy.PHONE_NOT_CONFIRMED), "phone_not_verified", null)
            mutable.update { it.copy(phone = result, pendingPhone = null, notice = text(AccountCopy.PHONE_VERIFIED)) }
        }
    }
    suspend fun unlinkPhone() = act({ api.unlinkPhone(it) }) { value, _ ->
        if (value.verified || value.number.isNotEmpty()) throw ApiFailure(0, text(AccountCopy.PHONE_STILL_LINKED), "phone_still_linked", null)
        mutable.update { it.copy(phone = value, pendingPhone = null, notice = text(AccountCopy.PHONE_REMOVED)) }
    }
    suspend fun unlinkProvider(row: AccountProviderRow) {
        if (state.value.providers.none { it.provider == row.provider }) return
        act({ api.unlinkProvider(it, row.provider) }) { _, _ -> mutable.update { it.copy(providers = it.providers.filterNot { value -> value.provider == row.provider }, notice = text(AccountCopy.PROVIDER_DISCONNECTED)) } }
    }
    suspend fun demoProvider(provider: String, result: String) {
        if (!api.isDemo || api !is DemoAccountProviderControl) return
        act({ api.previewProvider(provider, result); api.providers(it) }) { values, _ -> mutable.update { it.copy(providers = values, notice = if (result == "cancel") text(AccountCopy.PROVIDER_CANCELLED) else text(AccountCopy.DEMO_PROVIDER_UPDATED)) } }
    }
    suspend fun membershipAction(reason: String, leave: Boolean) {
        if (!pageAvailable(AccountPage.MEMBERSHIP)) return
        if (state.value.membership?.status != "active") return
        val trimmed = reason.trim()
        if (trimmed.isEmpty() || trimmed.length > 1000) { mutable.update { it.copy(error = text(AccountCopy.REASON_REQUIRED)) }; return }
        val owningScope = state.value.scope ?: return
        act({ if (leave) api.leave(maker(it), trimmed) else api.cancelRenewal(maker(it), trimmed) }) { value, _ ->
            if (value.status == "revoked") { clear(); onConfirmedMembershipLeft(owningScope) }
            else if (leave) mutable.update { it.copy(membership = value, notice = text(AccountCopy.MEMBERSHIP_STILL_ACTIVE)) }
            else mutable.update { it.copy(membership = value, notice = if (value.renewalCancelledAt != null) text(AccountCopy.RENEWAL_CANCELLED) else text(AccountCopy.RENEWAL_UNCONFIRMED)) }
        }
    }
    suspend fun requestExport() {
        if (!pageAvailable(AccountPage.EXPORTS)) return
        act({ api.requestExport(maker(it)) }) { value, _ ->
        mutable.update { it.copy(exports = listOf(value) + it.exports.filterNot { row -> row.id == value.id }, notice = text(AccountCopy.EXPORT_REQUESTED)) }
        }
    }
    suspend fun checkExport(row: AccountExport) {
        if (!pageAvailable(AccountPage.EXPORTS)) return
        if (state.value.exports.none { it.id == row.id }) return
        act({ api.export(maker(it), row.id) }) { value, _ -> mutable.update { it.copy(exports = it.exports.map { row -> if (row.id == value.id) value else row }) } }
    }
    suspend fun downloadExport(row: AccountExport) {
        if (!pageAvailable(AccountPage.EXPORTS)) return
        val actual = state.value.exports.firstOrNull { it.id == row.id } ?: return
        if (actual.status != "available" || !unexpired(actual.expiresAt, now())) return
        act({ scope -> api.exportArchive(maker(scope), row.id) }) { bytes, _ -> mutable.update { it.copy(archive = bytes) } }
    }
    private suspend fun fail(failure: Exception, expected: Long) {
        if (!current(expected)) return
        val apiFailure = failure as? ApiFailure
        if (apiFailure?.status == 401) { clear(); onSignedOut(); return }
        if (apiFailure?.status == 403) { clear(); onMembershipChanged(); return }
        val seconds = (apiFailure?.retryAfterSeconds ?: if (apiFailure?.status == 429) 60L else 0L).coerceAtLeast(0)
        // No server waiting period means no new deadline. A synthetic "now" deadline
        // can disable otherwise editable fields until the UI's next clock tick.
        val retry = if(seconds>0)retryDeadlineMillis(now(),seconds) else state.value.retryAtMillis
        if (state.value.page == AccountPage.PHONE && seconds > 0) phoneCooldown.extend(retry)
        mutable.update { it.copy(error = apiFailure?.let { failureText.apiFailure(it) } ?: text(AccountCopy.CONNECTION_FAILED), retryAtMillis = maxOf(it.retryAtMillis, retry), phoneRetryAtMillis = phoneCooldown.untilMillis) }
    }
    companion object {
        fun canonicalPhone(callingCode: String, nationalNumber: String): String? {
            val calling = callingCode.removePrefix("+")
            if (calling.length !in 1..3 || calling.firstOrNull() == '0' || !calling.all { it in '0'..'9' }) return null
            val incoming = nationalNumber.trim()
            val fullNumber = incoming.startsWith("+")
            val digits = incoming.removePrefix("+")
            if (digits.any { it !in '0'..'9' && it !in " -()" }) return null
            val normalized = digits.filter { it in '0'..'9' }
            if (fullNumber && !normalized.startsWith(calling)) return null
            val number = if (fullNumber) normalized.removePrefix(calling) else normalized
            if (number.length < 6 || calling.length + number.length > 15) return null
            return "+$calling$number"
        }
        fun unexpired(value: String, now: Long): Boolean = try { OffsetDateTime.parse(value).toInstant().toEpochMilli() > now } catch (_: Exception) { false }
    }
}
