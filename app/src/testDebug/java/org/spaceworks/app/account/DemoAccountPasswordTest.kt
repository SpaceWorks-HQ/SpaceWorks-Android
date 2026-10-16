package org.spaceworks.app.account

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DemoAccountPasswordTest {
    @Test fun manualPreviewNeverChangesTheSampleCredentialOrClaimsARealPasswordUpdate() = runTest {
        val api = DemoAccountGateway(); val holder = AccountManagementStateHolder(api)
        holder.activateGlobal("demo-account"); holder.open(AccountPage.PASSWORD)
        holder.changePassword("wrong", "replacement", "replacement")
        assertEquals(0L, holder.state.value.passwordRevision); assertNull(holder.state.value.notice)
        holder.changePassword("DemoPass!123", "replacement", "replacement")
        assertEquals(AccountCopy.DEMO_PASSWORD_CHANGED.english, holder.state.value.notice)
        holder.changePassword("DemoPass!123", "another", "another")
        assertEquals(2L, holder.state.value.passwordRevision)
        assertNotNull(holder.state.value.globalAccountKey)
    }
}
