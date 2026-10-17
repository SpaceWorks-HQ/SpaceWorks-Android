package org.spaceworks.app.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountManagementOwnerTest {
    private val scope=MemberScope(7,"sample")
    private val other=MemberScope(8,"other")
    private inner class Harness {
        var origin="https://one.example";var pairPresent=true
        var user:String?="51"
            set(value){field=value;pairPresent=value!=null}
        var epoch=10L;var selected:MemberScope?=scope
        fun identity()=if(pairPresent)user?.let{AccountOwnerIdentity(origin,it,epoch)}else null
        var ended=0;var refreshed=0;val dropped=mutableListOf<Int>()
        val contexts=mutableListOf<AccountManagementContext>();val gateways=mutableListOf<OwnerFakeAccountGateway>()
        var beforeFactory:()->Unit={}
        var finish:suspend(AccountOwnerIdentity,String?)->Boolean={identity,_->owner.resetAccount();user=null;if(epoch==identity.installationEpoch)epoch++;true}
        val owner=AccountManagementOwner(::identity,{selected},
            endedInstallation={identity->origin==identity.origin&&!pairPresent&&epoch==identity.installationEpoch+1},
            endLocalSession={identity,notice->ended++;finish(identity,notice)},
            foreground={refreshed++},dropConfirmedMembership={_,id->dropped+=id;true},
            gatewayFactory={context->beforeFactory();contexts+=context;OwnerFakeAccountGateway().also{gateways+=it}},now={1000L})
        fun active()=owner.current()!!.also{it.activate(scope)}
    }

    @Test fun lazyBindingAndReplacementClearPrivateStateBeforeGatewayConstruction()=runTest {
        val h=Harness();assertTrue(h.gateways.isEmpty());val first=h.active();first.open(AccountPage.DEVICES)
        assertEquals(1,first.state.value.grants.size);assertSame(first,h.owner.current())
        h.beforeFactory={assertTrue(first.state.value.grants.isEmpty());assertNull(first.state.value.scope)}
        h.user="52";h.epoch++;val second=h.owner.current()!!
        assertNotSame(first,second);assertEquals(2,h.gateways.size)
        assertFalse(h.contexts.first().hasSession());assertNull(h.contexts.first().selected())
    }
    @Test fun boundClosuresTrackActualScopeOnlyForTheirExactOwnerInstallation()=runTest {
        val h=Harness();h.active();val context=h.contexts.single()
        assertTrue(context.hasSession());assertEquals(scope,context.selected())
        h.selected=other;assertEquals(other,context.selected())
        h.epoch++;assertFalse(context.hasSession());assertNull(context.selected())
        h.owner.current();assertTrue(h.contexts.last().hasSession())
        h.origin="https://two.example";assertFalse(h.contexts.last().hasSession())
    }
    @Test fun phoneCooldownSurvivesMakerSwitchAndSameAccountReloginButSeparatesUserAndOrigin()=runTest {
        val h=Harness();val first=h.active();val cooldown=h.contexts.single().phoneCooldown;cooldown.extend(61000)
        first.open(AccountPage.DEVICES);h.owner.clearScope();assertTrue(first.state.value.grants.isEmpty())
        h.selected=other;assertSame(first,h.owner.current());first.activate(other)
        assertEquals(61000L,first.state.value.phoneRetryAtMillis)
        h.owner.resetAccount();h.epoch+=2;h.active();assertSame(cooldown,h.contexts.last().phoneCooldown)
        h.user="52";h.epoch++;h.active();assertNotSame(cooldown,h.contexts.last().phoneCooldown)
        assertEquals(0L,h.contexts.last().phoneCooldown.untilMillis)
        h.user="51";h.origin="https://two.example";h.epoch++;h.active();assertEquals(0L,h.contexts.last().phoneCooldown.untilMillis)
        h.origin="https://one.example";h.epoch++;h.active();assertSame(cooldown,h.contexts.last().phoneCooldown)
    }
    @Test fun oldCallbacksAfterReplacementNeverCallNativeAuthOrDropMembership()=runTest {
        val h=Harness();h.active();val old=h.contexts.single();h.user="52";h.epoch++;h.active()
        old.onSignedOut();old.onSecurityEnded(listOf("private-code"),"notice");old.onPasswordChanged()
        old.onMembershipChanged();old.onConfirmedMembershipLeft(scope)
        assertEquals(0,h.ended);assertEquals(0,h.refreshed);assertTrue(h.dropped.isEmpty());assertNull(h.owner.completion.value)
    }
    @Test fun acceptedLocalEndPublishesDefensiveRedactedCodesAfterIntentionalControllerReset()=runTest {
        val h=Harness();val holder=h.active();holder.open(AccountPage.SECURITY)
        val codes=mutableListOf("PRIVATE-ONE","PRIVATE-TWO")
        h.contexts.single().onSecurityEnded(codes,"Recovery updated.")
        val result=h.owner.completion.value!!;codes.clear()
        assertEquals(listOf("PRIVATE-ONE","PRIVATE-TWO"),result.codes)
        assertEquals("Recovery updated.",result.notice);assertFalse(result.toString().contains("PRIVATE"))
        assertNull(h.owner.current());assertSame(result,h.owner.completion.value)
        try{(result.codes as MutableList<String>).clear();fail("Completion must be immutable")}
        catch(_:UnsupportedOperationException){}
        assertTrue(holder.state.value.grants.isEmpty());assertNull(holder.state.value.scope)
        h.owner.acknowledgeCompletion();assertNull(h.owner.completion.value)
    }
    @Test fun signedOutUsesOnlyLocalEndWithNoRecoveryCodesAndNoRemoteMutation()=runTest {
        val h=Harness();h.active();h.contexts.single().onSignedOut()
        assertEquals(1,h.ended);assertTrue(h.owner.completion.value!!.codes.isEmpty())
        assertTrue(h.gateways.single().revokedSessions.isEmpty());assertEquals(0,h.refreshed)
    }
    @Test fun actualHolderRecoveryCallbackSurvivesItsClearAndAuthSessionEndedClear()=runTest {
        val h=Harness();val holder=h.active();holder.open(AccountPage.SECURITY)
        holder.regenerateRecovery("current password")
        assertNull(holder.state.value.scope);assertNull(holder.state.value.security)
        assertEquals(1,h.ended);assertEquals(listOf("DEMO-ONE","DEMO-TWO"),h.owner.completion.value!!.codes)
        assertNull(h.owner.current());assertNotNull(h.owner.completion.value)
    }
    @Test fun coreAlreadyClearedExactInstallationStillEndsCachedAuthLocallyWithoutApi()=runTest {
        val h=Harness();val holder=h.active();holder.open(AccountPage.DEVICES);val context=h.contexts.single()
        h.pairPresent=false;h.epoch++;assertNull(h.identity());assertEquals("51",h.user)
        // Even if signed-out composition detached the binding, its original generation is retained.
        assertNull(h.owner.current());context.onSignedOut()
        assertEquals(1,h.ended);assertNull(h.user);assertTrue(holder.state.value.grants.isEmpty())
        assertTrue(h.owner.completion.value!!.codes.isEmpty());assertEquals(0,h.refreshed)
    }
    @Test fun alreadyClearedProofNeverAllowsReplacementOrLaterEpochCleanup()=runTest {
        for(rebind in listOf(false,true)){
            val h=Harness();h.active();val context=h.contexts.single()
            h.user="52";h.epoch++;if(rebind)h.active()
            h.user=null;h.epoch++
            context.onSignedOut();assertEquals(0,h.ended);assertNull(h.owner.completion.value)
        }
    }
    @Test fun rejectedEndOrWrongInstallationProofCannotPublishCodes()=runTest {
        for(accepted in listOf(false,true)){
            val h=Harness();h.active();h.finish={_,_->h.owner.resetAccount();h.user=null;h.epoch+=2;accepted}
            h.contexts.single().onSecurityEnded(listOf("PRIVATE"),null)
            assertNull(h.owner.completion.value)
        }
    }
    @Test fun delayedOldEndCannotPublishOrClearReplacementHolder()=runTest {
        val h=Harness();h.active();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        h.finish={_,_->h.owner.resetAccount();entered.complete(Unit);release.await();true}
        val pending=async{h.contexts.first().onSecurityEnded(listOf("OLD"),null)};entered.await()
        h.user="52";h.epoch++;val replacement=h.active();replacement.open(AccountPage.DEVICES)
        release.complete(Unit);pending.await()
        assertNull(h.owner.completion.value);assertSame(replacement,h.owner.current());assertEquals(1,replacement.state.value.grants.size)
    }
    @Test fun intermediateNewBindingThenLogoutSuppressesOldCompletionEvenIfProofIsForcedToLookEnded()=runTest {
        val h=Harness();h.active();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        h.finish={_,_->h.owner.resetAccount();entered.complete(Unit);release.await();true}
        val pending=async{h.contexts.first().onSecurityEnded(listOf("OLD"),null)};entered.await()
        h.user="52";h.epoch++;h.active();h.owner.resetAccount();h.user=null
        // Epoch intentionally equals old+1: generation must independently refuse a newer binding.
        release.complete(Unit);pending.await();assertNull(h.owner.completion.value)
    }
    @Test fun changedActiveOriginOrBackgroundWhileEndingPreventsLateSecretPresentation()=runTest {
        for(background in listOf(false,true)){
            val h=Harness();h.active();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            h.finish={_,_->h.owner.resetAccount();h.user=null;h.epoch++;entered.complete(Unit);release.await();true}
            val pending=async{h.contexts.first().onSecurityEnded(listOf("OLD"),null)};entered.await()
            if(background)h.owner.background()else h.origin="https://two.example"
            release.complete(Unit);pending.await();assertNull(h.owner.completion.value)
        }
    }
    @Test fun originAwayAndBackWithoutNewAccountBindingStillRejectsPendingOldCodes()=runTest {
        val h=Harness();h.active();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        h.finish={_,_->h.owner.resetAccount();h.user=null;h.epoch++;entered.complete(Unit);release.await();true}
        val pending=async{h.contexts.first().onSecurityEnded(listOf("OLD"),null)};entered.await()
        h.origin="https://two.example";h.owner.resetAccount();h.origin="https://one.example";h.owner.resetAccount()
        release.complete(Unit);pending.await();assertNull(h.owner.completion.value)
    }
    @Test fun duplicatePendingCallbackDoesNotEndSameInstallationTwice()=runTest {
        val h=Harness();h.active();val context=h.contexts.single();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        h.finish={_,_->entered.complete(Unit);release.await();h.owner.resetAccount();h.user=null;h.epoch++;true}
        val pending=async{context.onSecurityEnded(listOf("FIRST"),null)};entered.await()
        context.onSecurityEnded(listOf("SECOND"),null);assertEquals(1,h.ended)
        release.complete(Unit);pending.await();assertEquals(listOf("FIRST"),h.owner.completion.value!!.codes)
    }
    @Test fun generic403RefreshesOnlyAndConfirmedLeaveDropsCapturedMakerBeforeRefresh()=runTest {
        val h=Harness();val holder=h.active();val api=h.gateways.single()
        api.failure=ApiFailure(403,"Access changed",null,null);holder.open(AccountPage.PROVIDERS)
        assertEquals(1,h.refreshed);assertTrue(h.dropped.isEmpty())
        api.failure=null;holder.activate(scope);holder.open(AccountPage.MEMBERSHIP)
        holder.membershipAction("Leaving",true)
        assertEquals(listOf(scope.id),h.dropped);assertEquals(2,h.refreshed);assertNull(holder.state.value.scope)
    }
}
