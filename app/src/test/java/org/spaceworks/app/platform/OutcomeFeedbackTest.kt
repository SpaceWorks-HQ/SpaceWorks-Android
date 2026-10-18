package org.spaceworks.app.platform

import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.auth.AuthState
import org.spaceworks.app.auth.AuthOperationKind
import org.spaceworks.app.auth.authOperationKind
import org.spaceworks.generated.model.AuthUserPayload
import org.spaceworks.generated.model.MFAChallengeResponse

class OutcomeFeedbackTest {
    @Test fun replacementActionOwnerOrDisposedScreenCannotReplayOldFeedback() {
        val gate=FeedbackTicketGate()
        val first=gate.begin("account-a")
        val second=gate.begin("account-a")
        assertFalse(gate.finish(first,"account-a",true))
        assertTrue(gate.finish(second,"account-a",true))
        assertFalse(gate.finish(second,"account-a",true))
        val replaced=gate.begin("account-a")
        assertFalse(gate.finish(replaced,"account-b",true))
        val disposed=gate.begin("account-a")
        gate.cancel()
        assertFalse(gate.finish(disposed,"account-a",true))
    }
    @Test fun backgroundOrDisabledCompletionIsConsumedRatherThanReplayedOnResume() {
        val gate=FeedbackTicketGate()
        val ticket=gate.begin("scope")
        assertFalse(gate.finish(ticket,"scope",false))
        assertFalse(gate.finish(ticket,"scope",true))
        assertTrue(gate.finish(gate.begin("scope"),"scope",true))
    }
    @Test fun mfaAndRequiredPasswordAreProgressionRatherThanCompletedAuthentication() {
        val before=AuthState(initialized=true)
        val user=AuthUserPayload("member",1,"member","member@example.invalid","Member","",true,"member",false,false,emptyList())
        assertEquals(AuthOperationKind.PROGRESSED,authOperationKind(before,before.copy(mfa=MFAChallengeResponse(true,false,"challenge",300)),false))
        assertEquals(AuthOperationKind.PROGRESSED,authOperationKind(before,before.copy(user=user.copy(mustChangePassword=true)),true))
        assertNull(authOperationKind(before,before.copy(user=user),false))
        assertEquals(AuthOperationKind.ACCEPTED,authOperationKind(before,before.copy(user=user),true))
        assertNull(authOperationKind(before.copy(user=user),before.copy(user=user),true))
    }
    @Test fun oldErrorsAndBusyOrUnchangedReadsDoNotCreateNewOutcomeFeedback() {
        val before=AuthState(initialized=true,error="Check your sign-in details")
        assertNull(authOperationKind(before,before,false))
        assertNull(authOperationKind(before,before.copy(busy=true,error="New error"),false))
        assertEquals(AuthOperationKind.REJECTED,authOperationKind(before,before.copy(error="Verification expired"),false))
        assertNull(authOperationKind(AuthState(),AuthState(initialized=true),false))
    }
}
