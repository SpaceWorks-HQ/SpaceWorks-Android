package org.spaceworks.app.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.spaceworks.app.R
import org.spaceworks.app.ui.*

/** Variant-independent whole-account deletion entry. Debug supplies a preview; release the generated flow. */
interface AccountDeletionFlow {
    fun clear()
    @Composable fun Screen(onBack:()->Unit,onFix:(Int?,AccountFixDestination)->Unit,onDeleted:()->Unit)
}

/** Production deletion backed by [AccountDeletionStateHolder] and generated operations. */
class GeneratedDeletionFlow(private val holder:AccountDeletionStateHolder,private val makerName:(Int)->String?,
    private val canOpenStaff:(Int?)->Boolean,private val trustedHost:String?,
    private val googleClientId:()->String?):AccountDeletionFlow {
    override fun clear()=holder.clear()
    @Composable override fun Screen(onBack:()->Unit,onFix:(Int?,AccountFixDestination)->Unit,onDeleted:()->Unit) {
        val context=androidx.compose.ui.platform.LocalContext.current
        val unavailable=stringResource(R.string.deletion_provider_unavailable)
        val credential:(suspend(String)->String)?=googleClientId()?.let {client->
            val provider:suspend(String)->String={nonce->org.spaceworks.app.platform.GoogleIdentity.credential(context.activity()?:throw IllegalStateException(unavailable),client,nonce)}
            provider}
        AccountDeletionScreen(holder,onBack,onFix,makerName,canOpenStaff,trustedHost,credential)
    }
}

@Composable fun AccountDeletionScreen(holder:AccountDeletionStateHolder,onBack:()->Unit,onFix:(Int?,AccountFixDestination)->Unit,
    makerName:(Int)->String?={null},canOpenStaff:(Int?)->Boolean={false},trustedHost:String?=null,
    googleCredential:(suspend(String)->String)?=null) {
    val s by holder.state.collectAsState()
    val scope=rememberCoroutineScope()
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val uri=androidx.compose.ui.platform.LocalUriHandler.current
    var password by remember(holder,s.stage) {mutableStateOf("")}
    var code by remember(holder,s.stage,s.phoneCodeSent) {mutableStateOf("")}
    var confirmation by remember(holder,s.stage) {mutableStateOf("")}
    LaunchedEffect(holder) {if(s.preview==null)holder.load()}
    LaunchedEffect(s.stage,s.proofExpiresAt) {if(s.stage==DeletionStage.CONFIRM)while(true){kotlinx.coroutines.delay(500);holder.expireProof()}}
    fun leave(){password="";code="";confirmation="";holder.clear();onBack()}
    fun act(block:suspend()->Unit){focus.clearFocus();keyboard?.hide();scope.launch {block()}}
    BackHandler(!s.busy) {leave()}
    AuthLayout(stringResource(R.string.account_deletion),stringResource(R.string.deletion_explanation)) {
        BrandTextButton(stringResource(R.string.account_back),::leave,!s.busy)
        Text(stringResource(R.string.deletion_retention),color=MaterialTheme.colorScheme.onSurfaceVariant)
        s.preview?.takeIf {it.restoreReason.isNotBlank()}?.let {
            Text(stringResource(R.string.deletion_restore_title),style=MaterialTheme.typography.titleMedium)
            Text(it.restoreReason)
        }
        val loading=stringResource(R.string.deletion_loading)
        if(s.busy)LinearProgressIndicator(Modifier.fillMaxWidth().semantics {contentDescription=loading})
        s.message?.let {BrandError(stringResource(when(it) {
            DeletionMessage.PROOF_FAILED->R.string.deletion_proof_failed
            DeletionMessage.PROOF_EXPIRED->R.string.deletion_proof_expired
            DeletionMessage.CONFIRM_INVALID->R.string.deletion_confirm_invalid
            DeletionMessage.CONFLICT->R.string.deletion_conflict
            DeletionMessage.CODE_REQUIRED->R.string.deletion_code_required
            DeletionMessage.PASSWORD_REQUIRED->R.string.deletion_password_required
            DeletionMessage.PROVIDER_UNAVAILABLE->R.string.deletion_provider_unavailable
        }))}
        BrandError(s.error)
        when(s.stage) {
            DeletionStage.LOADING -> {
                Text(stringResource(R.string.deletion_loading),Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                if(!s.busy)BrandButton(stringResource(R.string.deletion_recheck),{act {holder.load()}})
            }
            DeletionStage.BLOCKERS -> {
                Text(stringResource(R.string.deletion_blockers),style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics {heading()})
                s.preview?.blockers.orEmpty().forEach {blocker->
                    OutlinedCard(Modifier.fillMaxWidth().testTag("deletion-blocker-${blocker.code}")) {
                        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(blockerLabel(blocker.code)),style=MaterialTheme.typography.titleMedium)
                            val where=blocker.makerspaceId?.let {makerName(it)?:blocker.makerspaceSlug?.let(::makerspaceLabel)?:stringResource(R.string.account_makerspace_id,it)}
                                ?:stringResource(R.string.deletion_this_server)
                            Text(stringResource(R.string.deletion_blocker_scope_count,where,pluralStringResource(R.plurals.deletion_outstanding_count,blocker.count,blocker.count)))
                            // Server detail is authoritative and the only text for unknown codes.
                            Text(blocker.detail)
                            val web=blocker.organizationUrl?.takeIf {AccountLinkPolicy.safe(it) && trustedHost!=null && java.net.URI(it).host.equals(trustedHost,true)}
                            when {
                                web!=null -> BrandTextButton(stringResource(R.string.deletion_open_web_console),{runCatching {uri.openUri(web)}})
                                else -> {
                                    val destination=if(blocker.fix==AccountFixDestination.STAFF && !canOpenStaff(blocker.makerspaceId))AccountFixDestination.SUPPORT else blocker.fix
                                    BrandTextButton(stringResource(fixLabel(destination)),{holder.clear();onFix(blocker.makerspaceId,destination)})
                                }
                            }
                        }
                    }
                }
                BrandButton(stringResource(R.string.deletion_recheck),{act {holder.load()}},!s.busy,Modifier.testTag("deletion-recheck"))
            }
            DeletionStage.REAUTHENTICATE -> {
                Text(stringResource(R.string.deletion_proof_title),style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics {heading()})
                val usable=s.passwordAvailable || s.totpRequired || s.phoneAvailable || (s.socialProviders.isNotEmpty() && googleCredential!=null)
                if(!usable)Text(stringResource(R.string.deletion_no_native_method))
                if(s.passwordAvailable || s.totpRequired) {
                    if(s.passwordAvailable && s.totpRequired)Text(stringResource(R.string.deletion_password_code_help))
                    if(s.passwordAvailable)BrandField(password,{password=it.take(1024)},stringResource(R.string.deletion_password),Modifier.testTag("deletion-password"),!s.busy,secret=true)
                    if(s.totpRequired)BrandField(code,{code=it.filter {c->c.isDigit()}.take(6)},stringResource(R.string.deletion_authenticator),Modifier.testTag("deletion-totp"),!s.busy,role=FieldRole.ONE_TIME_CODE)
                    BrandButton(stringResource(R.string.deletion_proof_continue),{val p=password;val c=code;password="";code="";act {holder.verifyPassword(p,c)}},!s.busy,Modifier.testTag("deletion-authenticate"))
                }
                if(s.phoneAvailable) {
                    if(!s.phoneCodeSent)BrandTextButton(stringResource(R.string.deletion_send_code),{act {holder.sendPhoneCode()}},!s.busy,modifier=Modifier.testTag("deletion-phone-start"))
                    else {
                        Text(stringResource(R.string.deletion_code_sent))
                        BrandField(code,{code=it.filter {c->c.isDigit()}.take(6)},stringResource(R.string.deletion_phone),Modifier.testTag("deletion-phone-code"),!s.busy,role=FieldRole.ONE_TIME_CODE)
                        BrandButton(stringResource(R.string.deletion_proof_continue),{val c=code;code="";act {holder.verifyPhone(c)}},!s.busy && code.length==6,Modifier.testTag("deletion-phone-verify"))
                    }
                }
                if(googleCredential!=null)s.socialProviders.forEach {provider->
                    BrandTextButton(stringResource(R.string.deletion_verify_with,"Google"),{act {holder.verifySocial(provider,googleCredential)}},!s.busy)
                }
            }
            DeletionStage.FACTOR -> {
                Text(stringResource(R.string.deletion_factor_title),style=MaterialTheme.typography.titleLarge)
                Text(stringResource(if(s.factorKind=="totp")R.string.deletion_factor_totp else R.string.deletion_factor_code))
                BrandField(code,{code=it.trim().take(64)},stringResource(R.string.deletion_code),Modifier.testTag("deletion-factor"),!s.busy,role=FieldRole.ONE_TIME_CODE)
                BrandButton(stringResource(R.string.deletion_proof_continue),{val c=code;code="";act {holder.verifyFactor(c)}},!s.busy && code.isNotBlank())
                BrandTextButton(stringResource(R.string.deletion_use_another_method),holder::backToMethods,!s.busy)
            }
            DeletionStage.CONFIRM -> {
                Text(stringResource(R.string.deletion_confirm_title),style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics {heading()})
                Text(stringResource(R.string.deletion_confirm_help))
                BrandField(confirmation,{confirmation=it.take(16)},stringResource(R.string.deletion_confirm_label),Modifier.testTag("deletion-confirmation"),!s.busy,
                    onDone={if(confirmation=="DELETE"){val typed=confirmation;confirmation="";act {holder.confirm(typed)}}})
                Button({val typed=confirmation;confirmation="";act {holder.confirm(typed)}},Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("deletion-submit"),
                    enabled=!s.busy && confirmation=="DELETE",colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error,contentColor=MaterialTheme.colorScheme.onError)) {
                    Text(stringResource(R.string.deletion_delete_account))
                }
            }
            DeletionStage.FINISHED -> {
                Text(stringResource(R.string.deletion_deleted),Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                s.deleted?.let {Text(stringResource(R.string.deletion_purge_after,it.purgeAfter.take(10)))}
            }
        }
    }
}

private fun blockerLabel(code:String)=when(code) {
    "unpaid_charges"->R.string.deletion_unpaid
    "refund_in_progress"->R.string.deletion_refund
    "deposit_held"->R.string.deletion_deposit
    "active_loans"->R.string.deletion_loans
    "open_requests"->R.string.deletion_requests
    "accountability_open"->R.string.deletion_accountability
    "problem_report_open"->R.string.deletion_problem_report
    "access_restricted"->R.string.deletion_access_restricted
    "open_service_jobs"->R.string.deletion_service_jobs
    "upcoming_bookings"->R.string.deletion_bookings
    "upcoming_registrations"->R.string.deletion_events
    "last_manager"->R.string.deletion_manager
    "last_org_governor"->R.string.deletion_last_org_governor
    "organization_owner"->R.string.deletion_owner
    "makerspace_gated"->R.string.deletion_makerspace_gated
    "superadmin"->R.string.deletion_superadmin
    else->R.string.deletion_other
}
private fun fixLabel(destination:AccountFixDestination)=when(destination) {
    AccountFixDestination.PAYMENTS->R.string.deletion_fix_payments
    AccountFixDestination.ACTIVITY->R.string.deletion_fix_activity
    AccountFixDestination.BOOKINGS->R.string.deletion_fix_bookings
    AccountFixDestination.EVENTS->R.string.deletion_fix_events
    AccountFixDestination.STAFF->R.string.deletion_fix_staff
    AccountFixDestination.SUPPORT->R.string.deletion_fix_support
}
