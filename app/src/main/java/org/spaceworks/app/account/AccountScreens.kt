package org.spaceworks.app.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import org.spaceworks.app.R
import org.spaceworks.app.platform.NativeDocumentFiles
import org.spaceworks.app.platform.NativeDocumentKind
import org.spaceworks.app.platform.NativeDocumentPanel
import org.spaceworks.app.platform.NativeDocumentFile
import org.spaceworks.app.ui.*
import java.net.URI
import java.io.File

private data class AccountConfirmation(val title: String, val detail: String, val action: suspend () -> Unit)
@Composable private fun AccountCard(content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable fun AccountScreens(holder: AccountManagementStateHolder, demo: Boolean, onBack: () -> Unit,
    privacyURL: String? = null, supportURL: String? = null, now: () -> Long = System::currentTimeMillis, onDemoDelete:(()->Unit)?=null) {
    val s by holder.state.collectAsStateWithLifecycle()
    val work = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val currentClock by rememberUpdatedState(now)
    val scroll = rememberScrollState()
    var confirmation by remember(holder, s.globalAccountKey, s.scope, s.page) { mutableStateOf<AccountConfirmation?>(null) }
    var password by remember(holder, s.globalAccountKey, s.scope, s.page) { mutableStateOf("") }
    var currentPassword by remember(holder, s.globalAccountKey, s.scope, s.page) { mutableStateOf("") }
    var newPassword by remember(holder, s.globalAccountKey, s.scope, s.page) { mutableStateOf("") }
    var confirmationPassword by remember(holder, s.globalAccountKey, s.scope, s.page) { mutableStateOf("") }
    var calling by remember(holder, s.globalAccountKey, s.scope, s.page) { mutableStateOf("+91") }
    var number by remember(holder, s.globalAccountKey, s.scope, s.page) { mutableStateOf("") }
    var code by remember(holder, s.globalAccountKey, s.scope, s.page) { mutableStateOf("") }
    var reason by remember(holder, s.globalAccountKey, s.scope, s.page) { mutableStateOf("") }
    var demoProvider by remember(holder, s.globalAccountKey, s.scope, s.page) { mutableStateOf<String?>(null) }
    var clock by remember { mutableLongStateOf(now()) }
    ProtectSecrets(!demo)
    LaunchedEffect(holder, s.passwordRevision) { if (s.passwordRevision > 0) { currentPassword = ""; newPassword = ""; confirmationPassword = "" } }
    DisposableEffect(lifecycle, holder, s.globalAccountKey, s.scope, s.page) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { currentPassword = ""; newPassword = ""; confirmationPassword = "" }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); currentPassword = ""; newPassword = ""; confirmationPassword = "" }
    }
    LaunchedEffect(s.scope) { if (s.scope != null) holder.loadCapabilities() }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { clock = currentClock(); kotlinx.coroutines.delay(500) }
        }
    }
    LaunchedEffect(s.error) { if (s.error != null) { focus.clearFocus(); keyboard?.hide(); scroll.scrollTo(0) } }
    val enabled = !s.busy && !s.loading && !s.downloading && clock >= s.retryAtMillis
    val workingLabel = stringResource(R.string.account_working)
    fun submit(action: suspend () -> Unit) { focus.clearFocus(); keyboard?.hide(); work.launch { action() } }
    fun close() { if (!s.busy && !s.downloading) { password = ""; code = ""; currentPassword = ""; newPassword = ""; confirmationPassword = ""; if (s.page == AccountPage.MENU) onBack() else holder.close() } }
    BackHandler { close() }
    confirmation?.let { pending ->
        val dismiss={if(!s.busy)confirmation=null}
        val confirm={confirmation=null;submit(pending.action)}
        if(LocalDensity.current.fontScale>=1.6f || LocalConfiguration.current.screenHeightDp<520) {
            Dialog(dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
                Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding(),contentAlignment=Alignment.TopCenter) {
                        Column(Modifier.widthIn(max=520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                            Text(pending.title,style=MaterialTheme.typography.headlineSmall,modifier=Modifier.semantics {heading()})
                            Text(pending.detail)
                            BrandButton(stringResource(R.string.account_confirm),confirm,enabled)
                            BrandTextButton(stringResource(R.string.account_cancel),dismiss,!s.busy)
                        }
                    }
                }
            }
        } else AlertDialog(onDismissRequest=dismiss,title={Text(pending.title)},
            text={Text(pending.detail,Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState()))},
            confirmButton={BrandTextButton(stringResource(R.string.account_confirm),confirm,enabled)},
            dismissButton={BrandTextButton(stringResource(R.string.account_cancel),dismiss,!s.busy)})
    }
    val files = remember(context) { NativeDocumentFiles(File(context.cacheDir, "native-documents")) }
    val documentTitle = stringResource(R.string.account_export_document_title)
    var document by remember { mutableStateOf<NativeDocumentFile?>(null) }
    var documentFailed by remember(s.archive) { mutableStateOf(false) }
    LaunchedEffect(s.archive) {
        document?.close(); document = null
        val bytes = s.archive ?: return@LaunchedEffect
        var created: NativeDocumentFile? = null
        try {
            withContext(Dispatchers.IO) { created = files.create(bytes, NativeDocumentKind.ZIP, documentTitle) }
            ensureActive()
            document = created; created = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { documentFailed = true }
        finally { created?.close() }
    }
    val visibleDocument = document
    DisposableEffect(visibleDocument) { onDispose { visibleDocument?.close() } }
    if (visibleDocument != null) { NativeDocumentPanel(visibleDocument) { holder.dismissArchive() }; return }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().imePadding().verticalScroll(scroll).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            BrandTextButton(stringResource(R.string.account_back), { close() }, !s.busy)
            Text(stringResource(pageTitle(s.page)), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            if (demo) Text(stringResource(R.string.account_demo), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.loading || s.busy) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = workingLabel })
            if (clock < s.retryAtMillis) Text(stringResource(R.string.account_retry, ((s.retryAtMillis - clock + 999) / 1000).toInt()))
            BrandError(s.error)
            if (documentFailed) BrandError(stringResource(R.string.account_document_failed))
            s.notice?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            when (s.page) {
                AccountPage.MENU -> {
                    Text(stringResource(R.string.account_intro))
                    AccountCard { AccountPage.entries.filter { it != AccountPage.MENU && holder.pageAvailable(it) }.forEach { page ->
                        BrandTextButton(stringResource(pageTitle(page)), { submit { holder.open(page) } }, enabled, Modifier.testTag("account-${page.name.lowercase()}"))
                    } }
                    if (s.capabilitiesLoading) Text(stringResource(R.string.account_services_loading))
                    if (s.scope!=null && s.capabilities?.let { !it.selfService || !it.selfExport } == true) Text(stringResource(R.string.account_services_disabled))
                    if(s.scope!=null)BrandTextButton(stringResource(R.string.account_services_refresh), { submit { holder.loadCapabilities() } }, enabled && !s.capabilitiesLoading)
                    AccountPrivacyLinks(privacyURL, supportURL,onDemoDelete)
                }
                AccountPage.PASSWORD -> {
                    Text(stringResource(R.string.account_password_intro))
                    if (holder.isDemo) Text(stringResource(R.string.account_password_demo_hint))
                    fun change() {
                        val current = currentPassword; val replacement = newPassword; val confirmation = confirmationPassword
                        currentPassword = ""
                        submit { holder.changePassword(current, replacement, confirmation) }
                    }
                    BrandField(currentPassword, { currentPassword = it.take(1024) }, stringResource(R.string.account_password_current), Modifier.testTag("account-password-current"), enabled, secret = true, role = FieldRole.PASSWORD)
                    BrandField(newPassword, { newPassword = it.take(1024) }, stringResource(R.string.account_password_new), Modifier.testTag("account-password-new"), enabled, secret = true, role = FieldRole.NEW_PASSWORD)
                    BrandField(confirmationPassword, { confirmationPassword = it.take(1024) }, stringResource(R.string.account_password_confirmation), Modifier.testTag("account-password-confirmation"), enabled, secret = true, role = FieldRole.NEW_PASSWORD,
                        onDone = { if (enabled && currentPassword.isNotEmpty() && newPassword.isNotEmpty() && confirmationPassword.isNotEmpty()) change() })
                    BrandButton(stringResource(R.string.account_change_password), { change() }, enabled && currentPassword.isNotEmpty() && newPassword.isNotEmpty() && confirmationPassword.isNotEmpty(), Modifier.testTag("account-password-submit"))
                }
                AccountPage.SECURITY -> {
                    Text(stringResource(R.string.account_security_intro))
                    s.security?.let { security ->
                        AccountCard {
                            Text(stringResource(if (security.enabled) R.string.account_mfa_enabled else R.string.account_mfa_disabled), style = MaterialTheme.typography.titleMedium)
                            if (security.required) Text(stringResource(R.string.account_mfa_required))
                            if (!security.available && !security.enabled) Text(stringResource(R.string.account_mfa_unavailable))
                            if (!security.enabled && security.available) Text(stringResource(R.string.account_mfa_web))
                        }
                        if (security.enabled) {
                            BrandField(password, { password = it.take(1024) }, stringResource(R.string.account_password), Modifier.testTag("account-current-password"), enabled, secret = true)
                            Text(stringResource(R.string.account_security_ends_sessions))
                            val changeNotice = stringResource(R.string.account_security_ends_sessions)
                            val recoveryTitle = stringResource(R.string.account_recovery_replace)
                            if (security.available) BrandButton(recoveryTitle, { confirmation = AccountConfirmation(recoveryTitle, changeNotice) { val value = password; password = ""; holder.regenerateRecovery(value) } }, enabled && password.isNotEmpty(), Modifier.testTag("account-regenerate-recovery"))
                            val disableTitle = stringResource(R.string.account_mfa_disable)
                            if (!security.required) BrandTextButton(disableTitle, { confirmation = AccountConfirmation(disableTitle, changeNotice) { val value = password; password = ""; holder.disableMFA(value) } }, enabled && password.isNotEmpty())
                        }
                    }
                }
                AccountPage.PHONE -> {
                    Text(stringResource(R.string.account_phone_intro))
                    s.phone?.takeIf { it.verified }?.let { Text(stringResource(R.string.account_phone_verified, it.number)) }
                        ?: Text(stringResource(R.string.account_phone_unknown), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.pendingPhone == null) {
                        BrandField(calling, { calling = it.take(4) }, stringResource(R.string.account_country_code), enabled = enabled, role = FieldRole.NUMBER)
                        BrandField(number, { number = it.take(24) }, stringResource(R.string.account_phone_number), Modifier.testTag("account-phone-number"), enabled, role = FieldRole.PHONE)
                        BrandButton(stringResource(R.string.account_phone_send), { submit { holder.startPhone(calling, number) } }, enabled && clock >= s.phoneRetryAtMillis)
                    } else {
                        Text(stringResource(R.string.account_phone_sending_to, s.pendingPhone.orEmpty()))
                        BrandField(code, { code = it.filter { c -> c in '0'..'9' }.take(6) }, stringResource(R.string.account_phone_code), Modifier.testTag("account-phone-code"), enabled, role = FieldRole.ONE_TIME_CODE,
                            onDone = { if (enabled && code.length == 6) { val value = code; code = ""; submit { holder.confirmPhone(value) } } })
                        BrandButton(stringResource(R.string.account_phone_confirm), { val value = code; code = ""; submit { holder.confirmPhone(value) } }, enabled && code.length == 6)
                        BrandTextButton(stringResource(R.string.account_phone_resend), { submit { holder.resendPhone() } }, enabled && clock >= s.phoneRetryAtMillis)
                        BrandTextButton(stringResource(R.string.account_phone_other), { code = ""; holder.clearPendingPhone() }, enabled)
                    }
                    if (clock < s.phoneRetryAtMillis) Text(stringResource(R.string.account_retry, ((s.phoneRetryAtMillis - clock + 999) / 1000).toInt()))
                    val removeTitle = stringResource(R.string.account_phone_remove); val removeDetail = stringResource(R.string.account_phone_remove_detail)
                    BrandTextButton(removeTitle, { confirmation = AccountConfirmation(removeTitle, removeDetail) { holder.unlinkPhone() } }, enabled)
                }
                AccountPage.PROVIDERS -> {
                    Text(stringResource(R.string.account_provider_intro))
                    s.providers.forEach { row -> AccountCard {
                        Text(row.provider.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
                        val title = stringResource(R.string.account_provider_disconnect, row.provider); val detail = stringResource(R.string.account_provider_disconnect_detail)
                        BrandTextButton(title, { confirmation = AccountConfirmation(title, detail) { holder.unlinkProvider(row) } }, enabled)
                    } }
                    if (demo && holder.isDemo) {
                        Text(stringResource(R.string.account_provider_demo_intro))
                        GoogleSignInButton(enabled) { demoProvider = "google" }
                        BrandTextButton(stringResource(R.string.account_provider_preview_apple), { demoProvider = "apple" }, enabled)
                        demoProvider?.let { provider ->
                            listOf("connected" to R.string.account_provider_demo_success, "cancel" to R.string.account_provider_demo_cancel, "conflict" to R.string.account_provider_demo_conflict, "unavailable" to R.string.account_provider_demo_unavailable).forEach { (result, label) ->
                                BrandTextButton(stringResource(label), { demoProvider = null; submit { holder.demoProvider(provider, result) } }, enabled)
                            }
                        }
                    } else Text(stringResource(R.string.account_provider_native_gap))
                }
                AccountPage.SESSIONS -> {
                    s.sessions.forEach { row -> AccountCard {
                        Text(if (row.current) stringResource(R.string.account_current_session) else stringResource(R.string.account_session_kind, accountSessionKind(row.kind)), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.account_created, memberDate(row.createdAt))); row.lastUsedAt?.let { Text(stringResource(R.string.account_last_used, memberDate(it))) }
                        val title = stringResource(R.string.account_sign_out_session); val detail = stringResource(R.string.account_sign_out_detail)
                        BrandTextButton(title, { confirmation = AccountConfirmation(title, detail) { holder.revokeSession(row) } }, enabled, Modifier.testTag("account-session-${row.id}"))
                    } }
                    if (s.sessionsHaveMore) Text(stringResource(R.string.account_more_web))
                }
                AccountPage.DEVICES -> {
                    Text(stringResource(R.string.account_grants), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    s.grants.forEach { row -> AccountCard {
                        Text(stringResource(R.string.account_device, row.platform, accountRecordStatus(row.status)))
                        Text(stringResource(R.string.account_last_used, memberDate(row.lastUsedAt)))
                        val title = stringResource(R.string.account_revoke_device); val detail = stringResource(R.string.account_revoke_detail)
                        BrandTextButton(title, { confirmation = AccountConfirmation(title, detail) { holder.revokeGrant(row) } }, enabled)
                    } }
                }
                AccountPage.MEMBERSHIP -> s.membership?.let { membership ->
                    Text(stringResource(R.string.account_membership_status, accountRecordStatus(membership.status)))
                    membership.renewalCancelledAt?.let { Text(stringResource(R.string.account_renewal_cancelled, memberDate(it))) }
                    if (membership.status == "active") {
                        BrandField(reason, { reason = it.take(1000) }, stringResource(R.string.account_reason), enabled = enabled)
                        Text(stringResource(R.string.account_reason_limit), style = MaterialTheme.typography.bodySmall)
                        val cancel = stringResource(R.string.account_cancel_renewal); val cancelDetail = stringResource(R.string.account_cancel_renewal_detail)
                        if (membership.renewalCancelledAt == null) BrandTextButton(cancel, { confirmation = AccountConfirmation(cancel, cancelDetail) { holder.membershipAction(reason, false) } }, enabled && reason.isNotBlank())
                        val leave = stringResource(R.string.account_leave); val leaveDetail = stringResource(R.string.account_leave_detail)
                        BrandTextButton(leave, { confirmation = AccountConfirmation(leave, leaveDetail) { holder.membershipAction(reason, true) } }, enabled && reason.isNotBlank())
                    }
                }
                AccountPage.EXPORTS -> {
                    Text(stringResource(R.string.account_export_intro))
                    BrandButton(stringResource(R.string.account_export_request), { submit { holder.requestExport() } }, enabled, Modifier.testTag("account-export-request"))
                    s.exports.forEach { row -> AccountCard {
                        Text(stringResource(when (row.status) { "available" -> R.string.account_export_available; "failed" -> R.string.account_export_failed; else -> R.string.account_export_pending }), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.account_created, memberDate(row.createdAt)))
                        if (row.failure.isNotBlank()) BrandError(row.failure)
                        if (row.status == "available" && AccountManagementStateHolder.unexpired(row.expiresAt, clock)) {
                            Text(stringResource(R.string.account_export_expiry, memberDate(row.expiresAt)))
                            BrandButton(stringResource(R.string.account_export_download), { submit { holder.downloadExport(row) } }, enabled, Modifier.testTag("account-export-download-${row.id}"))
                        } else if (row.status == "available") Text(stringResource(R.string.account_export_expired))
                        else if (row.status in listOf("pending", "running")) BrandTextButton(stringResource(R.string.account_export_check), { submit { holder.checkExport(row) } }, enabled)
                    } }
                    if (s.exportsHaveMore) Text(stringResource(R.string.account_more_web))
                }
            }
            if (s.page !in listOf(AccountPage.MENU, AccountPage.PHONE, AccountPage.PASSWORD)) BrandTextButton(stringResource(R.string.account_refresh), { submit { holder.refresh() } }, enabled)
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun pageTitle(page: AccountPage) = when (page) { AccountPage.MENU -> R.string.account_title; AccountPage.PASSWORD -> R.string.account_change_password; AccountPage.SECURITY -> R.string.account_security; AccountPage.PHONE -> R.string.account_phone; AccountPage.PROVIDERS -> R.string.account_providers; AccountPage.SESSIONS -> R.string.account_sessions; AccountPage.DEVICES -> R.string.account_grants; AccountPage.MEMBERSHIP -> R.string.account_membership; AccountPage.EXPORTS -> R.string.account_exports }

@Composable private fun AccountPrivacyLinks(privacyURL: String?, supportURL: String?,onDemoDelete:(()->Unit)?) {
    val handler = LocalUriHandler.current
    var failed by remember { mutableStateOf(false) }
    AccountCard {
        Text(stringResource(R.string.account_privacy_help), style = MaterialTheme.typography.titleLarge)
        listOf(Triple(privacyURL, R.string.account_privacy, R.string.account_privacy_missing), Triple(supportURL, R.string.account_support, R.string.account_support_missing)).forEach { (url, title, missing) ->
            if (AccountLinkPolicy.safe(url)) BrandTextButton(stringResource(title), { try { handler.openUri(url!!) } catch (_: Exception) { failed = true } })
            else Text(stringResource(missing), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (failed) BrandError(stringResource(R.string.account_link_unavailable))
        Text(stringResource(R.string.account_deletion), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.account_deletion_gap))
        onDemoDelete?.let {BrandTextButton(stringResource(R.string.account_deletion_preview),it,modifier=Modifier.testTag("account-deletion-preview"))}
    }
}

object AccountLinkPolicy {
    fun safe(value: String?): Boolean { return try {
        val text = value ?: return false
        val uri = URI(text)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && text.none { it.isISOControl() }
    } catch (_: Exception) { false } }
}

/** Present outside the authenticated shell after its credentials/private state have been cleared. */
@Composable fun AccountRecoveryCompletion(codes: List<String>, message: String?, demo: Boolean, onContinue: () -> Unit) {
    var saved by remember(codes) { mutableStateOf(false) }
    ProtectSecrets(!demo)
    BackHandler { /* Explicit acknowledgement keeps unsaved one-time codes reachable. */ }
    AuthLayout(stringResource(R.string.account_recovery_title), message ?: stringResource(R.string.account_recovery_intro)) {
        SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { codes.forEach { Text(it, style = MaterialTheme.typography.titleMedium) } } }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(saved, role = Role.Checkbox, onValueChange = { saved = it }).testTag("account-recovery-saved"), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(saved, null); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.account_recovery_saved))
        }
        BrandButton(stringResource(R.string.account_signin_continue), onContinue, saved, Modifier.testTag("account-security-continue"))
    }
}

/** Inject resources::accountText into both the account holder and generated gateway. */
fun android.content.res.Resources.accountText(copy: AccountCopy): String = getString(when (copy) {
    AccountCopy.SERVICE_DISABLED -> R.string.account_message_service_disabled
    AccountCopy.SESSION_REVOKED -> R.string.account_message_session_revoked
    AccountCopy.DEVICE_REVOKED -> R.string.account_message_device_revoked
    AccountCopy.RECOVERY_CHANGED -> R.string.account_message_recovery_changed
    AccountCopy.MFA_STILL_ENABLED -> R.string.account_message_mfa_still_enabled
    AccountCopy.MFA_CHANGED -> R.string.account_message_mfa_changed
    AccountCopy.PASSWORD_REQUIRED -> R.string.account_message_password_required
    AccountCopy.PASSWORD_NEW_REQUIRED -> R.string.account_message_password_new_required
    AccountCopy.PASSWORD_CONFIRMATION -> R.string.account_message_password_confirmation
    AccountCopy.PASSWORD_CHANGED -> R.string.account_message_password_changed
    AccountCopy.DEMO_PASSWORD_CHANGED -> R.string.account_message_demo_password_changed
    AccountCopy.PHONE_INVALID -> R.string.account_message_phone_invalid
    AccountCopy.PHONE_CODE_SENT -> R.string.account_message_phone_code_sent
    AccountCopy.PHONE_CODE_REQUIRED -> R.string.account_message_phone_code_required
    AccountCopy.PHONE_CODE_INVALID -> R.string.account_message_phone_code_invalid
    AccountCopy.PHONE_NOT_CONFIRMED -> R.string.account_message_phone_not_confirmed
    AccountCopy.PHONE_VERIFIED -> R.string.account_message_phone_verified
    AccountCopy.PHONE_STILL_LINKED -> R.string.account_message_phone_still_linked
    AccountCopy.PHONE_REMOVED -> R.string.account_message_phone_removed
    AccountCopy.PROVIDER_DISCONNECTED -> R.string.account_message_provider_disconnected
    AccountCopy.PROVIDER_CANCELLED -> R.string.account_message_provider_cancelled
    AccountCopy.DEMO_PROVIDER_UPDATED -> R.string.account_message_demo_provider_updated
    AccountCopy.REASON_REQUIRED -> R.string.account_message_reason_required
    AccountCopy.MEMBERSHIP_STILL_ACTIVE -> R.string.account_message_membership_still_active
    AccountCopy.RENEWAL_CANCELLED -> R.string.account_message_renewal_cancelled
    AccountCopy.RENEWAL_UNCONFIRMED -> R.string.account_message_renewal_unconfirmed
    AccountCopy.EXPORT_REQUESTED -> R.string.account_message_export_requested
    AccountCopy.CONNECTION_FAILED -> R.string.account_message_connection_failed
    AccountCopy.SIGN_IN_AGAIN -> R.string.account_message_sign_in_again
    AccountCopy.RECORD_INVALID -> R.string.account_message_record_invalid
    AccountCopy.SESSION_INVALID -> R.string.account_message_session_invalid
    AccountCopy.MAKER_CHANGED -> R.string.account_message_maker_changed
    AccountCopy.EXPORT_LINK_EXPIRED -> R.string.account_message_export_link_expired
    AccountCopy.EXPORT_UNREADABLE -> R.string.account_message_export_unreadable
    AccountCopy.EXPORT_TOO_LARGE -> R.string.account_message_export_too_large
    AccountCopy.PAGE_UNAVAILABLE -> R.string.account_message_page_unavailable
    AccountCopy.EXPORT_LINK_UNAVAILABLE -> R.string.account_message_export_link_unavailable
    AccountCopy.MAKER_REQUIRED -> R.string.account_message_maker_required
})

@Composable private fun accountSessionKind(value: String): String = when (value) {
    "device" -> stringResource(R.string.account_kind_device)
    "web" -> stringResource(R.string.account_kind_browser)
    else -> value
}
@Composable private fun accountRecordStatus(value: String): String = when (value) {
    "active" -> stringResource(R.string.account_status_active)
    "revoked" -> stringResource(R.string.account_status_revoked)
    else -> value
}
