package org.spaceworks.app.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import org.spaceworks.app.R
import org.spaceworks.app.ui.*

/** Only the Debug source set contains this component and its synthetic state. */
@Composable fun DemoAccountDeletionScreen(holder: DemoAccountDeletion, onBack: () -> Unit,
    onFix: (DemoDeletionBlocker, DemoDeletionFix) -> Unit, onDemoDeleted: () -> Unit,
    makerspaceName:(Int)->String?={null},canOpenStaff:(Int?)->Boolean={false}) {
    val s by holder.state.collectAsState()
    var method by remember(holder) { mutableStateOf(DemoDeletionProof.PASSWORD) }
    var credential by remember(holder, s.stage, method) { mutableStateOf("") }
    var confirmation by remember(holder, s.stage) { mutableStateOf("") }
    DisposableEffect(holder) { onDispose { holder.clear() } }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun leave() { credential = ""; confirmation = ""; holder.clear(); onBack() }
    fun authenticate() {
        val supplied = credential; credential = ""; focus.clearFocus(); keyboard?.hide()
        holder.authenticate(method, supplied)
    }
    fun confirm() {
        val supplied = confirmation; confirmation = ""; focus.clearFocus(); keyboard?.hide()
        if (holder.confirm(supplied)) onDemoDeleted()
    }
    BackHandler { leave() }
    // Also expire idle confirmation screens, not only a subsequent button tap.
    LaunchedEffect(s.stage, s.proofExpiresAt) {
        if (s.stage == DemoDeletionStage.CONFIRM) {
            while (true) { kotlinx.coroutines.delay(500); holder.expireProof() }
        }
    }
    AuthLayout(stringResource(R.string.account_deletion), deletionText(DeletionCopy.DEMO_ONLY)) {
        BrandTextButton(stringResource(R.string.account_back), { leave() })
        Text(deletionText(DeletionCopy.EXPLANATION))
        Text(deletionText(DeletionCopy.RETENTION), color = MaterialTheme.colorScheme.onSurfaceVariant)
        BrandError(s.error?.let { deletionText(it) })
        when (s.stage) {
            DemoDeletionStage.EXPLANATION -> {
                Text(deletionText(DeletionCopy.SCENARIO), style = MaterialTheme.typography.titleMedium)
                DemoDeletionScenario.entries.forEach { scenario ->
                    val selected = s.scenario == scenario
                    BrandTextButton(deletionText(scenario.copy), { holder.choose(scenario) }, modifier = Modifier.semantics { this.selected = selected })
                }
                BrandButton(deletionText(DeletionCopy.REVIEW), { holder.review() }, modifier = Modifier.testTag("deletion-review"))
            }
            DemoDeletionStage.BLOCKERS -> {
                if (s.conflict) Text(deletionText(DeletionCopy.CONFLICT), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text(deletionText(DeletionCopy.BLOCKERS), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                s.blockers.forEach { blocker ->
                    OutlinedCard(Modifier.fillMaxWidth().testTag("deletion-blocker-${blocker.code}")) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(deletionText(blocker.copy), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.deletion_blocker_scope_count, blocker.makerspaceID?.let(makerspaceName) ?: when (blocker.makerspaceID) {
                                7 -> stringResource(R.string.deletion_maker_7)
                                8 -> stringResource(R.string.deletion_maker_8)
                                null -> deletionText(DeletionCopy.THIS_SERVER)
                                else -> stringResource(R.string.account_makerspace_id,blocker.makerspaceID)
                            }, pluralStringResource(R.plurals.deletion_outstanding_count, blocker.count, blocker.count)))
                            // Synthetic server detail models the contract: preserve it, especially unknown codes.
                            Text(blocker.detail)
                            val destination=if(blocker.fix==DemoDeletionFix.STAFF && !canOpenStaff(blocker.makerspaceID))DemoDeletionFix.SUPPORT else blocker.fix
                            BrandTextButton(deletionText(destination.copy), { holder.back(); onFix(blocker, destination) })
                        }
                    }
                }
                BrandTextButton(deletionText(DeletionCopy.RECHECK), { holder.review() })
                BrandTextButton(deletionText(DeletionCopy.CHANGE_SCENARIO), { holder.back() })
            }
            DemoDeletionStage.REAUTHENTICATE -> {
                Text(deletionText(DeletionCopy.PROOF_TITLE), style = MaterialTheme.typography.titleLarge)
                DemoDeletionProof.entries.forEach { proof ->
                    val selected = method == proof
                    BrandTextButton(deletionText(proof.copy), { credential = ""; method = proof }, modifier = Modifier.semantics { this.selected = selected })
                }
                Text(deletionText(if (method == DemoDeletionProof.PASSWORD) DeletionCopy.PASSWORD_HELP else DeletionCopy.CODE_HELP))
                BrandField(credential, { credential = it.take(if (method == DemoDeletionProof.PASSWORD) 1024 else 6) },
                    deletionText(if (method == DemoDeletionProof.PASSWORD) DeletionCopy.PASSWORD else DeletionCopy.CODE),
                    Modifier.testTag("deletion-proof"), secret = method == DemoDeletionProof.PASSWORD,
                    role = if (method == DemoDeletionProof.PASSWORD) FieldRole.PASSWORD else FieldRole.ONE_TIME_CODE,
                    onDone = { authenticate() })
                BrandButton(deletionText(DeletionCopy.PROOF_CONTINUE), { authenticate() }, credential.isNotEmpty(), Modifier.testTag("deletion-authenticate"))
            }
            DemoDeletionStage.CONFIRM -> {
                Text(deletionText(DeletionCopy.CONFIRM_TITLE), style = MaterialTheme.typography.titleLarge)
                Text(deletionText(DeletionCopy.CONFIRM_HELP))
                BrandField(confirmation, { confirmation = it.take(16) }, deletionText(DeletionCopy.CONFIRM_LABEL),
                    Modifier.testTag("deletion-confirmation"), onDone = { confirm() })
                BrandButton(deletionText(DeletionCopy.DELETE), { confirm() }, confirmation == "DELETE", Modifier.testTag("deletion-submit"))
            }
            DemoDeletionStage.FINISHED -> Text(deletionText(DeletionCopy.FINISHED), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

private val DemoDeletionScenario.copy get() = when (this) {
    DemoDeletionScenario.OBLIGATIONS -> DeletionCopy.SCENARIO_OBLIGATIONS
    DemoDeletionScenario.READY -> DeletionCopy.SCENARIO_READY
    DemoDeletionScenario.CONFLICT -> DeletionCopy.SCENARIO_CONFLICT
}
private val DemoDeletionProof.copy get() = when (this) {
    DemoDeletionProof.PASSWORD -> DeletionCopy.PASSWORD
    DemoDeletionProof.AUTHENTICATOR -> DeletionCopy.AUTHENTICATOR
    DemoDeletionProof.PHONE -> DeletionCopy.PHONE
}
private val DemoDeletionFix.copy get() = when (this) {
    DemoDeletionFix.PAYMENTS -> DeletionCopy.FIX_PAYMENTS
    DemoDeletionFix.ACTIVITY -> DeletionCopy.FIX_ACTIVITY
    DemoDeletionFix.BOOKINGS -> DeletionCopy.FIX_BOOKINGS
    DemoDeletionFix.EVENTS -> DeletionCopy.FIX_EVENTS
    DemoDeletionFix.STAFF -> DeletionCopy.FIX_STAFF
    DemoDeletionFix.SUPPORT -> DeletionCopy.FIX_SUPPORT
}
private val DemoDeletionBlocker.copy get() = when (code) {
    "unpaid_charges" -> DeletionCopy.UNPAID
    "refund_in_progress" -> DeletionCopy.REFUND
    "deposit_held" -> DeletionCopy.DEPOSIT
    "active_loans" -> DeletionCopy.LOANS
    "open_requests" -> DeletionCopy.REQUESTS
    "accountability_open" -> DeletionCopy.ACCOUNTABILITY
    "open_service_jobs" -> DeletionCopy.SERVICE_JOBS
    "upcoming_bookings" -> DeletionCopy.BOOKINGS
    "upcoming_registrations" -> DeletionCopy.EVENTS
    "last_manager" -> DeletionCopy.MANAGER
    "organization_owner" -> DeletionCopy.OWNER
    "superadmin" -> DeletionCopy.SUPERADMIN
    else -> DeletionCopy.OTHER
}

@Composable private fun deletionText(copy: DeletionCopy): String = stringResource(when (copy) {
    DeletionCopy.DEMO_ONLY -> R.string.deletion_demo_only
    DeletionCopy.EXPLANATION -> R.string.deletion_explanation
    DeletionCopy.RETENTION -> R.string.deletion_retention
    DeletionCopy.SCENARIO -> R.string.deletion_scenario
    DeletionCopy.SCENARIO_OBLIGATIONS -> R.string.deletion_scenario_obligations
    DeletionCopy.SCENARIO_READY -> R.string.deletion_scenario_ready
    DeletionCopy.SCENARIO_CONFLICT -> R.string.deletion_scenario_conflict
    DeletionCopy.REVIEW -> R.string.deletion_review
    DeletionCopy.BLOCKERS -> R.string.deletion_blockers
    DeletionCopy.CONFLICT -> R.string.deletion_conflict
    DeletionCopy.RECHECK -> R.string.deletion_recheck
    DeletionCopy.CHANGE_SCENARIO -> R.string.deletion_change_scenario
    DeletionCopy.PROOF_TITLE -> R.string.deletion_proof_title
    DeletionCopy.PASSWORD -> R.string.deletion_password
    DeletionCopy.AUTHENTICATOR -> R.string.deletion_authenticator
    DeletionCopy.PHONE -> R.string.deletion_phone
    DeletionCopy.PASSWORD_HELP -> R.string.deletion_password_help
    DeletionCopy.CODE_HELP -> R.string.deletion_code_help
    DeletionCopy.CODE -> R.string.deletion_code
    DeletionCopy.PROOF_CONTINUE -> R.string.deletion_proof_continue
    DeletionCopy.PROOF_INVALID -> R.string.deletion_proof_invalid
    DeletionCopy.PROOF_EXPIRED -> R.string.deletion_proof_expired
    DeletionCopy.CONFIRM_TITLE -> R.string.deletion_confirm_title
    DeletionCopy.CONFIRM_HELP -> R.string.deletion_confirm_help
    DeletionCopy.CONFIRM_LABEL -> R.string.deletion_confirm_label
    DeletionCopy.CONFIRM_INVALID -> R.string.deletion_confirm_invalid
    DeletionCopy.DELETE -> R.string.deletion_delete
    DeletionCopy.FINISHED -> R.string.deletion_finished
    DeletionCopy.THIS_SERVER -> R.string.deletion_this_server
    DeletionCopy.FIX_PAYMENTS -> R.string.deletion_fix_payments
    DeletionCopy.FIX_ACTIVITY -> R.string.deletion_fix_activity
    DeletionCopy.FIX_BOOKINGS -> R.string.deletion_fix_bookings
    DeletionCopy.FIX_EVENTS -> R.string.deletion_fix_events
    DeletionCopy.FIX_STAFF -> R.string.deletion_fix_staff
    DeletionCopy.FIX_SUPPORT -> R.string.deletion_fix_support
    DeletionCopy.UNPAID -> R.string.deletion_unpaid
    DeletionCopy.REFUND -> R.string.deletion_refund
    DeletionCopy.DEPOSIT -> R.string.deletion_deposit
    DeletionCopy.LOANS -> R.string.deletion_loans
    DeletionCopy.REQUESTS -> R.string.deletion_requests
    DeletionCopy.ACCOUNTABILITY -> R.string.deletion_accountability
    DeletionCopy.SERVICE_JOBS -> R.string.deletion_service_jobs
    DeletionCopy.BOOKINGS -> R.string.deletion_bookings
    DeletionCopy.EVENTS -> R.string.deletion_events
    DeletionCopy.MANAGER -> R.string.deletion_manager
    DeletionCopy.OWNER -> R.string.deletion_owner
    DeletionCopy.SUPERADMIN -> R.string.deletion_superadmin
    DeletionCopy.OTHER -> R.string.deletion_other
})
