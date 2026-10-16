package org.spaceworks.app.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DemoAuthFlowTest {
    @Test fun everyProviderPreviewSupportsNeutralCancelAndMfaWithoutSdk()=runTest {
        for(provider in listOf("Google","Apple","Makerspace SSO")) {
            val bundle=DemoSupport.create();bundle.holder.initialize();val flow=bundle.previewAuth!!
            flow.open();flow.choose(provider);flow.unavailable();assertNotNull(flow.state.value.error)
            flow.cancel();assertNull(flow.state.value.page);assertNull(bundle.tokens.read())
            flow.open();flow.choose(provider);flow.requireMfa(true);flow.continueProvider()
            assertNull(flow.state.value.page);assertNotNull(bundle.holder.state.value.mfa);assertNull(bundle.tokens.read())
            bundle.holder.verify("123456");assertNotNull(bundle.holder.state.value.user)
            assertFalse(bundle.holder.state.value.staffAllowed)
        }
    }
    @Test fun phonePreviewEnforcesNumberCodeCooldownExpiryAndMemberOnlySession()=runTest {
        var now=1000L;val bundle=DemoSupport.create();bundle.holder.initialize()
        val flow=DemoAuthFlow(bundle.holder){now}
        flow.open();flow.choose("Phone");flow.phone("bad");flow.sendCode();assertNotNull(flow.state.value.error)
        flow.phone("9876543210");flow.sendCode();assertEquals(PreviewAuthPage.CODE,flow.state.value.page)
        val first=flow.state.value.resendAt;flow.sendCode();assertEquals(first,flow.state.value.resendAt)
        flow.verify("000000");assertNull(bundle.tokens.read());assertNotNull(flow.state.value.error)
        flow.expire();flow.verify("123456");assertNull(bundle.tokens.read())
        now=first;flow.sendCode();flow.verify("123456")
        assertNull(flow.state.value.page);assertNotNull(bundle.holder.state.value.user)
        assertFalse(bundle.holder.state.value.staffAllowed)
    }
    @Test fun updateAndEndedSessionPreviewsHaveNoCredentialsOrSessionSideEffects()=runTest {
        val bundle=DemoSupport.create();bundle.holder.initialize();val flow=bundle.previewAuth!!
        flow.open();flow.updateRequired();assertEquals(PreviewAuthPage.UPDATE_REQUIRED,flow.state.value.page)
        flow.continueProvider();flow.verify("123456");assertNull(bundle.tokens.read())
        flow.open();flow.sessionEnded();assertEquals(PreviewAuthPage.SESSION_ENDED,flow.state.value.page)
        flow.cancel();assertNull(flow.state.value.page);assertNull(bundle.holder.state.value.user)
    }
}
