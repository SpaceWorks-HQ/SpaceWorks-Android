package org.spaceworks.app.account

import androidx.compose.runtime.Composable

enum class AccountFixDestination { PAYMENTS, ACTIVITY, BOOKINGS, EVENTS, STAFF, SUPPORT }

/** Variant-provided preview only. Release supplies no implementation or deletion transport. */
interface AccountDeletionPreview {
    fun clear()
    @Composable fun Screen(onBack:()->Unit,onFix:(Int?,AccountFixDestination)->Unit,onDeleted:()->Unit)
}
