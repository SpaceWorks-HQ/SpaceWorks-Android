package org.spaceworks.app.account

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.auth.*

class DemoAccountResetTest {
    @Test fun deletionOrExitClearsOnlyItsDemoIdentityAndEveryPrivateEntryWithoutRemoteLogout()=runTest {
        val bundle=DemoSupport.create()
        bundle.holder.initialize();bundle.holder.signIn("single.demo","demo-password")
        val owner=requireNotNull(bundle.management).owner
        val holder=requireNotNull(owner.current());holder.activate(org.spaceworks.app.member.MemberScope(1,"fab-lab-kochi"))
        holder.open(AccountPage.PHONE)
        bundle.accounts!!.open(AccountRoute.VERIFY_EMAIL,"demo@example.invalid")
        assertNotNull(bundle.holder.state.value.user);assertNotNull(bundle.tokens.read())
        bundle.clearDemoState()
        assertNull(bundle.tokens.read());assertNull(bundle.holder.state.value.user)
        assertNull(bundle.accounts.state.value.route);assertNull(holder.state.value.scope)
        assertNull(owner.current());assertNull(owner.completion.value)
        val epoch=bundle.tokens.snapshot().epoch;bundle.clearDemoState();assertEquals(epoch,bundle.tokens.snapshot().epoch)
    }
    @Test fun previewResetCannotTouchALiveBundleEvenWhenItSharesAMakerID()=runTest {
        val demo=DemoSupport.create();demo.holder.initialize();demo.holder.signIn("single.demo","demo-password")
        val live=AuthBundle(demo.holder,demo.tokens,demo=false)
        val before=demo.tokens.snapshot();live.clearDemoState()
        assertEquals(before,demo.tokens.snapshot());assertNotNull(demo.holder.state.value.user)
    }
}
