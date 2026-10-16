package org.spaceworks.app.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.spaceworks.app.auth.requireBody
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.network.ApiSession
import org.spaceworks.app.network.Errors
import org.spaceworks.generated.api.*
import org.spaceworks.generated.model.*
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Only generated operations talk to the API. Scope is immutable across rotation/retry. */
class GeneratedAccountGateway(
    private val session: ApiSession,
    private val hasSession: () -> Boolean,
    private val selected: () -> MemberScope?,
    private val text: (AccountCopy) -> String = { it.english },
) : AccountGateway {
    // Constructed lazily for one accepted account installation by AccountManagementOwner.
    private val owningInstallationEpoch=session.installationEpoch
    private fun observeOwner(call:Call) {if(session.installationEpoch!=owningInstallationEpoch)call.cancel()}
    private fun guard(scope: MemberScope?, ownerEpoch: Long? = null) {
        if (selected() != scope) throw CancellationException("Account context changed")
        if (!hasSession()) throw ApiFailure(401, text(AccountCopy.SIGN_IN_AGAIN), "session_expired", null)
        if (session.installationEpoch!=owningInstallationEpoch || (ownerEpoch != null && ownerEpoch != session.installationEpoch)) throw CancellationException("Account installation changed")
    }
    private suspend fun <T> checked(scope: MemberScope?, password: Boolean = false, globalPassword: Boolean = false, ownerEpoch: Long? = null, block: suspend () -> T): T {
        guard(scope, ownerEpoch)
        try { return block().also { guard(scope, ownerEpoch) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ApiFailure) {
            guard(scope, ownerEpoch)
            // A wrong management password is also401. The core already tried one token rotation.
            if (password && failure.status == 401 && hasSession()) {
                try {
                    val auth = if (globalPassword) session.globalApi(AuthApi::class.java) { call -> observeOwner(call);if (ownerEpoch != null && ownerEpoch != session.installationEpoch) call.cancel() } else api(AuthApi::class.java, scope)
                    auth.apiV1AuthMeRetrieve().requireBody(); guard(scope, ownerEpoch)
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { throw failure }
                throw failure.copy(status = 0, code = "password_verification_failed")
            }
            throw failure
        }
    }
    private fun <T> api(type: Class<T>, scope: MemberScope?): T { guard(scope); return if (scope == null) session.globalApi(type,::observeOwner) else session.scopedApi(type, scope.id,::observeOwner) }
    private fun uuid(value: String): UUID = try { UUID.fromString(value).also { require(it.toString().equals(value, true)) } }
        catch (_: Exception) { throw invalid(text(AccountCopy.RECORD_INVALID)) }
    private fun opaque(value: String): String {
        require(value.isNotEmpty() && value.length <= 512 && value.none { it.isISOControl() }) { text(AccountCopy.SESSION_INVALID) }
        return value
    }
    private fun Response<Unit>.success() { if (!isSuccessful) throw Errors.map(code(), errorBody()?.string(), headers()["Retry-After"]) }
    override suspend fun capabilities(scope: MemberScope): AccountCapabilities = checked(scope) {
        val value = api(TenantBootstrapApi::class.java, scope).apiV1BootstrapRetrieve(slug = scope.slug).requireBody()
        if (value.makerspace.id != scope.id) throw ApiFailure(403, text(AccountCopy.MAKER_CHANGED), "makerspace_changed", null)
        val modules = value.modules.containsAll(listOf("membership", "member_accounts"))
        AccountCapabilities(modules && "membership.self_service" in value.features, modules && "membership.self_export" in value.features)
    }
    override suspend fun sessions(scope: MemberScope): AccountRows<AccountSessionRow> = checked(scope) {
        val page = api(MemberAccountApi::class.java, scope).apiV1MemberMakerspacesAccountSessionsRetrieve(scope.id).requireBody()
        validateNext(scope, page.next?.toString(), "sessions")
        AccountRows(page.results.map { AccountSessionRow(it.id, it.kind.value, it.current, it.createdAt.toString(), it.lastUsedAt?.toString(), it.expiresAt?.toString()) }, page.next != null)
    }
    override suspend fun grants(scope: MemberScope?): List<AccountGrantRow> = checked(scope) {
        api(DeviceAuthApi::class.java, scope).apiV1AuthDeviceGrantsList().requireBody().map { AccountGrantRow(it.id.toString(), it.platform, it.status, it.createdAt.toString(), it.lastUsedAt.toString()) }
    }
    override suspend fun revokeSession(scope: MemberScope, id: String) = checked(scope) {
        api(MemberAccountApi::class.java, scope).apiV1MemberMakerspacesAccountSessionsDestroy(scope.id, opaque(id)).success()
    }
    override suspend fun revokeGrant(scope: MemberScope?, id: String) = checked(scope) {
        api(DeviceAuthApi::class.java, scope).apiV1AuthDeviceGrantsDestroy(uuid(id)).success()
    }
    override suspend fun changePassword(scope: MemberScope?, current: String, replacement: String) {
        val ownerEpoch = session.installationEpoch
        // The password belongs to the account, never to a selected makerspace. The epoch pins
        // both generated operations even if another account logs in at the same makerspace.
        checked(scope, password = true, globalPassword = true, ownerEpoch = ownerEpoch) {
            session.globalApi(AuthApi::class.java) { call -> if (ownerEpoch != session.installationEpoch) call.cancel() }
                .apiV1AuthChangePasswordCreate(ChangePassword(current, replacement)).requireBody()
        }
        // An ACK carries no native rotation/grant authority. Verify the current identity afresh.
        // A post-ACK401 is an authentication failure, never a wrong-current-password error.
        checked(scope, ownerEpoch = ownerEpoch) {
            session.globalApi(AuthApi::class.java) { call -> if (ownerEpoch != session.installationEpoch) call.cancel() }
                .apiV1AuthMeRetrieve().requireBody()
        }
    }
    override suspend fun validateSession(scope: MemberScope?) { checked(scope) { api(AuthApi::class.java, scope).apiV1AuthMeRetrieve().requireBody() } }
    override suspend fun security(scope: MemberScope?): AccountSecurity = checked(scope) { security(api(MFAApi::class.java, scope).apiV1AuthMfaRetrieve().requireBody()) }
    override suspend fun disableMFA(scope: MemberScope?, password: String): AccountSecurity = checked(scope, password = true) {
        security(api(MFAApi::class.java, scope).apiV1AuthMfaDisableCreate(MFAPassword(password)).requireBody())
    }
    override suspend fun regenerateRecovery(scope: MemberScope?, password: String): List<String> = checked(scope, password = true) {
        api(MFAApi::class.java, scope).apiV1AuthMfaRecoveryCreate(MFAPassword(password)).requireBody().recoveryCodes
    }
    override suspend fun startPhone(scope: MemberScope?, phone: String) { checked(scope) { api(AuthApi::class.java, scope).apiV1AuthPhoneLinkStartCreate(PhoneStart(phone)).requireBody() } }
    override suspend fun confirmPhone(scope: MemberScope?, phone: String, code: String): AccountPhone = checked(scope) {
        phone(api(AuthApi::class.java, scope).apiV1AuthPhoneLinkConfirmCreate(PhoneConfirm(phone, code)).requireBody())
    }
    override suspend fun unlinkPhone(scope: MemberScope?): AccountPhone = checked(scope) { phone(api(AuthApi::class.java, scope).apiV1AuthPhoneDestroy().requireBody()) }
    override suspend fun providers(scope: MemberScope?): List<AccountProviderRow> = checked(scope) {
        api(SocialAuthApi::class.java, scope).apiV1AuthSocialProvidersList().requireBody().map { AccountProviderRow(it.provider, it.createdAt.toString()) }
    }
    override suspend fun unlinkProvider(scope: MemberScope?, provider: String) = checked(scope) {
        require(provider.isNotEmpty() && provider.length <= 200 && provider.none { it.isISOControl() })
        api(SocialAuthApi::class.java, scope).apiV1AuthSocialProvidersDestroy(provider).success()
    }
    override suspend fun membership(scope: MemberScope): AccountMembership = checked(scope) { membership(api(MemberAccountApi::class.java, scope).apiV1MemberMakerspacesAccountMembershipRetrieve(scope.id).requireBody()) }
    override suspend fun leave(scope: MemberScope, reason: String): AccountMembership = checked(scope) { membership(api(MemberAccountApi::class.java, scope).apiV1MemberMakerspacesAccountLeaveCreate(scope.id, MembershipReason(reason)).requireBody()) }
    override suspend fun cancelRenewal(scope: MemberScope, reason: String): AccountMembership = checked(scope) { membership(api(MemberAccountApi::class.java, scope).apiV1MemberMakerspacesAccountCancelCreate(scope.id, MembershipReason(reason)).requireBody()) }
    override suspend fun exports(scope: MemberScope): AccountRows<AccountExport> = checked(scope) {
        val page = api(MemberAccountApi::class.java, scope).apiV1MemberMakerspacesAccountExportsRetrieve(scope.id).requireBody()
        validateNext(scope, page.next?.toString(), "exports")
        AccountRows(page.results.map(::export), page.next != null)
    }
    override suspend fun requestExport(scope: MemberScope): AccountExport = checked(scope) {
        export(api(MemberAccountApi::class.java, scope).apiV1MemberMakerspacesAccountExportsCreate(scope.id, kotlinx.serialization.json.JsonObject(emptyMap())).requireBody())
    }
    override suspend fun export(scope: MemberScope, id: String): AccountExport = checked(scope) {
        export(api(MemberAccountApi::class.java, scope).apiV1MemberMakerspacesAccountExportsRetrieve2(uuid(id), scope.id).requireBody())
    }
    override suspend fun exportArchive(scope: MemberScope, id: String): ByteArray = checked(scope) {
        val jobID = uuid(id)
        val grant = api(MemberAccountApi::class.java, scope).apiV1MemberMakerspacesAccountExportsDownloadCreate(jobID, scope.id).requireBody()
        if (grant.expiresAt <= OffsetDateTime.now()) throw invalid(text(AccountCopy.EXPORT_LINK_EXPIRED))
        val token = AccountExportGrant.token(session.server.toString(), id, grant.url.toString(), text)
        guard(scope)
        // The one-use published download is public. No JWT/makerspace header may reach it.
        coroutineScope {
            suspendCancellableCoroutine { continuation ->
                val active = AtomicReference<Call?>()
                val reader = launch(Dispatchers.IO) {
                    try {
                        guard(scope)
                        val public = session.publicApi(DataExportsApi::class.java) { call -> active.set(call);observeOwner(call); if (!continuation.isActive) call.cancel() }
                        // Retrofit's suspend call cancels its socket when this reader is cancelled.
                        // The body reader owns close, preventing concurrent Okio close/read races.
                        val body = public.apiV1DataExportsDownloadRetrieve(jobID, token).requireBody()
                        val bytes = body.use { response ->
                            guard(scope)
                            val mime = response.contentType()?.let { "${it.type}/${it.subtype}" }
                            if (mime !in setOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) throw invalid(text(AccountCopy.EXPORT_UNREADABLE))
                            val maximum = 25 * 1024 * 1024
                            if (response.contentLength() > maximum) throw invalid(text(AccountCopy.EXPORT_TOO_LARGE))
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            response.byteStream().use { input ->
                                while (true) {
                                    if (!continuation.isActive) throw CancellationException()
                                    guard(scope)
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    if (output.size() > maximum - count) throw invalid(text(AccountCopy.EXPORT_TOO_LARGE))
                                    output.write(buffer, 0, count)
                                }
                            }
                            output.toByteArray()
                        }
                        if (!zip(bytes)) throw invalid(text(AccountCopy.EXPORT_UNREADABLE))
                        guard(scope)
                        if (continuation.isActive) continuation.resume(bytes)
                    } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                    finally { active.set(null) }
                }
                continuation.invokeOnCancellation { active.getAndSet(null)?.cancel(); reader.cancel() }
            }
        }
    }
    private fun validateNext(scope: MemberScope, value: String?, resource: String) {
        if (value == null) return
        val origin = session.server
        val url = origin.resolve(value) ?: throw invalid(text(AccountCopy.PAGE_UNAVAILABLE))
        if (value.length > 8192 || '\\' in value || url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null || url.encodedPath != "/api/v1/member/makerspaces/${scope.id}/account/$resource") throw invalid(text(AccountCopy.PAGE_UNAVAILABLE))
        // Schema has no page query. UI exposes an honest browser notice rather than repeat page1.
    }
    private fun security(value: MFAStatus) = AccountSecurity(value.available, value.enabled, value.required)
    private fun phone(value: PhoneStatus) = AccountPhone(value.phoneE164, value.verified)
    private fun membership(value: MembershipAccount) = AccountMembership(value.status.value, value.renewalCancelledAt?.toString())
    private fun export(value: SubjectExport) = AccountExport(value.id.toString(), value.status.value, value.createdAt.toString(), value.expiresAt.toString(), value.failureDetail)
    companion object {
        fun invalid(message: String) = ApiFailure(0, message, "invalid_account_input", null)
        fun zip(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes[0] == 80.toByte() && bytes[1] == 75.toByte() &&
            ((bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) || (bytes[2] == 5.toByte() && bytes[3] == 6.toByte()) || (bytes[2] == 7.toByte() && bytes[3] == 8.toByte()))
    }
}

object AccountExportGrant {
    fun token(origin: String, jobID: String, value: String, text: (AccountCopy) -> String = { it.english }): String {
        val base = origin.toHttpUrl()
        if (value.length > 8192 || value.any { it.isISOControl() || it == '\\' }) throw GeneratedAccountGateway.invalid(text(AccountCopy.EXPORT_LINK_UNAVAILABLE))
        val url = base.resolve(value) ?: throw GeneratedAccountGateway.invalid(text(AccountCopy.EXPORT_LINK_UNAVAILABLE))
        val prefix = "/api/v1/data-exports/download/$jobID/"
        if (url.scheme != base.scheme || url.host != base.host || url.port != base.port || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null || !url.encodedPath.startsWith(prefix)) throw GeneratedAccountGateway.invalid(text(AccountCopy.EXPORT_LINK_UNAVAILABLE))
        val encoded = url.encodedPath.removePrefix(prefix)
        val token = url.pathSegments.last()
        if (encoded.isEmpty() || '/' in encoded || token.length > 4096 || token in setOf(".", "..") || token.any { it.isISOControl() || it == '/' || it == '\\' }) throw GeneratedAccountGateway.invalid(text(AccountCopy.EXPORT_LINK_UNAVAILABLE))
        return token
    }
}
