package org.spaceworks.app.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Debug-only interaction model. It has no API, token store, payment SDK or real deletion hook. */
enum class DemoDeletionStage { EXPLANATION, BLOCKERS, REAUTHENTICATE, CONFIRM, FINISHED }
enum class DemoDeletionScenario { OBLIGATIONS, READY, CONFLICT }
enum class DemoDeletionProof { PASSWORD, AUTHENTICATOR, PHONE }
enum class DemoDeletionFix { PAYMENTS, ACTIVITY, BOOKINGS, EVENTS, STAFF, SUPPORT }
data class DemoDeletionBlocker(val code: String, val makerspaceID: Int?, val count: Int, val detail: String) {
    val fix: DemoDeletionFix get() = when (code) {
        "unpaid_charges", "refund_in_progress", "deposit_held" -> DemoDeletionFix.PAYMENTS
        "active_loans", "open_requests" -> DemoDeletionFix.ACTIVITY
        "upcoming_bookings" -> DemoDeletionFix.BOOKINGS
        "upcoming_registrations" -> DemoDeletionFix.EVENTS
        "last_manager" -> DemoDeletionFix.STAFF
        else -> DemoDeletionFix.SUPPORT
    }
}
data class DemoDeletionState(
    val stage: DemoDeletionStage = DemoDeletionStage.EXPLANATION,
    val scenario: DemoDeletionScenario = DemoDeletionScenario.OBLIGATIONS,
    val blockers: List<DemoDeletionBlocker> = emptyList(),
    val proofExpiresAt: Long? = null,
    val error: DeletionCopy? = null,
    val conflict: Boolean = false,
)

class DemoAccountDeletion(private val now: () -> Long = System::currentTimeMillis) {
    /** Actual Debug memberships only; no maker is fabricated for an account with no memberships. */
    constructor(makerspaceIDs: List<Int>, now: () -> Long = System::currentTimeMillis) : this(now) {
        require(makerspaceIDs.all { it > 0 })
        val actual = makerspaceIDs.distinct()
        instanceObligations = obligations.mapNotNull { blocker ->
            when (blocker.makerspaceID) {
                null -> blocker
                7 -> actual.firstOrNull()?.let { blocker.copy(makerspaceID = it) }
                8 -> actual.getOrNull(1)?.let { blocker.copy(makerspaceID = it) }
                    ?: actual.firstOrNull()?.let { blocker.copy(makerspaceID = it) }
                else -> null
            }
        }
    }
    private var instanceObligations = obligations
    private val mutable = MutableStateFlow(DemoDeletionState())
    val state = mutable.asStateFlow()
    fun choose(scenario: DemoDeletionScenario) { mutable.value = DemoDeletionState(scenario = scenario) }
    fun review() {
        if (state.value.stage == DemoDeletionStage.FINISHED) return
        val blockers = if (state.value.scenario == DemoDeletionScenario.OBLIGATIONS) instanceObligations
            else if (state.value.conflict) state.value.blockers else emptyList()
        mutable.value = state.value.copy(stage = if (blockers.isEmpty()) DemoDeletionStage.REAUTHENTICATE else DemoDeletionStage.BLOCKERS,
            blockers = blockers, proofExpiresAt = null, error = null)
    }
    fun authenticate(method: DemoDeletionProof, credential: String) {
        if (state.value.stage != DemoDeletionStage.REAUTHENTICATE || state.value.blockers.isNotEmpty()) return
        val correct = when (method) { DemoDeletionProof.PASSWORD -> credential == "DemoPass!123"; else -> credential == "123456" }
        // Credentials are only an argument; neither state nor any persistent storage retains them.
        mutable.value = if (!correct) state.value.copy(error = DeletionCopy.PROOF_INVALID)
        else state.value.copy(stage = DemoDeletionStage.CONFIRM, proofExpiresAt = now() + 300_000, error = null)
    }
    fun expireProof() {
        if (state.value.stage == DemoDeletionStage.CONFIRM && (state.value.proofExpiresAt ?: 0) <= now()) {
            mutable.value = state.value.copy(stage = DemoDeletionStage.REAUTHENTICATE, proofExpiresAt = null, error = DeletionCopy.PROOF_EXPIRED)
        }
    }
    fun confirm(typedConfirmation: String): Boolean {
        expireProof()
        if (state.value.stage != DemoDeletionStage.CONFIRM || state.value.blockers.isNotEmpty()) return false
        if (typedConfirmation != "DELETE") { mutable.value = state.value.copy(error = DeletionCopy.CONFIRM_INVALID); return false }
        if (state.value.scenario == DemoDeletionScenario.CONFLICT) {
            val blocker = instanceObligations.firstOrNull { it.code == "active_loans" }
                ?: instanceObligations.first { it.makerspaceID == null }
            mutable.value = state.value.copy(stage = DemoDeletionStage.BLOCKERS, blockers = listOf(blocker),
                proofExpiresAt = null, error = null, conflict = true)
            return false
        }
        mutable.value = DemoDeletionState(stage = DemoDeletionStage.FINISHED, scenario = state.value.scenario)
        return true
    }
    fun back() { mutable.value = DemoDeletionState(scenario = state.value.scenario) }
    fun clear() { mutable.value = DemoDeletionState() }
    companion object {
        val obligations = listOf(
            DemoDeletionBlocker("unpaid_charges", 7, 2, "Two charges need payment."),
            DemoDeletionBlocker("refund_in_progress", 7, 1, "A refund is still processing."),
            DemoDeletionBlocker("deposit_held", 8, 1, "A deposit is held by your makerspace."),
            DemoDeletionBlocker("active_loans", 7, 1, "Return your outstanding hardware."),
            DemoDeletionBlocker("open_requests", 8, 2, "Two hardware requests remain open."),
            DemoDeletionBlocker("accountability_open", 7, 1, "An accountability review remains open."),
            DemoDeletionBlocker("open_service_jobs", 8, 1, "A service job remains open."),
            DemoDeletionBlocker("upcoming_bookings", 7, 1, "An upcoming booking is still active."),
            DemoDeletionBlocker("upcoming_registrations", 8, 1, "An event registration is still active."),
            DemoDeletionBlocker("last_manager", 7, 1, "Give another manager access before leaving."),
            DemoDeletionBlocker("organization_owner", 8, 1, "Transfer organization ownership first."),
            DemoDeletionBlocker("superadmin", null, 1, "Ask the server operator to review this account."),
            DemoDeletionBlocker("future_backend_code", 8, 1, "Additional review is required by this makerspace."),
        )
    }
}
