package org.spaceworks.app.auth

enum class AuthOperationKind { PROGRESSED, ACCEPTED, REJECTED }

/** Nonsecret, operation-owned completion proof. Contains no credentials, recovery codes or user data. */
class AuthOperationOutcome internal constructor(internal val owner:Any,internal val revision:Long,
    internal val epoch:Long,val kind:AuthOperationKind)

internal fun authOperationKind(before:AuthState,after:AuthState,accepted:Boolean):AuthOperationKind? = when {
    after.busy -> null
    after.error!=null && after.error!=before.error -> AuthOperationKind.REJECTED
    after.gate!=null && after.gate!=before.gate -> AuthOperationKind.REJECTED
    after.user!=null && before.user==null && accepted ->
        if(after.user.mustChangePassword)AuthOperationKind.PROGRESSED else AuthOperationKind.ACCEPTED
    after.mfa!=null && before.mfa==null -> AuthOperationKind.PROGRESSED
    after.recoveryCodes.isNotEmpty() && before.recoveryCodes.isEmpty() -> AuthOperationKind.PROGRESSED
    else -> null
}
