package org.spaceworks.app.notifications

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.network.ApiFailure

@OptIn(ExperimentalCoroutinesApi::class)
class PushStateHolderTest {
    private class Memory:PushPersistence {
        val consents=mutableMapOf<String,Boolean>();val saved=mutableMapOf<String,List<PushRecord>>()
        override suspend fun consent(account:String)=consents[account]?:false
        override suspend fun consent(account:String,value:Boolean){consents[account]=value}
        override suspend fun records(account:String)=saved[account].orEmpty()
        override suspend fun records(account:String,value:List<PushRecord>){saved[account]=value}
        override suspend fun clear(account:String){consents.remove(account);saved.remove(account)}
    }
    private class Provider:PushProvider {var calls=0;var stops=0;override suspend fun token():String{calls++;return TOKEN};override suspend fun stop(){stops++}}
    private class Gateway:PushGateway {
        val registrations=mutableListOf<Pair<Int,String>>();val removed=mutableListOf<Pair<Int,Int>>();var failure:Exception?=null;var removeFailure:Exception?=null;var nextId=100
        var entered:CompletableDeferred<Unit>?=null;var release:CompletableDeferred<Unit>?=null
        override suspend fun register(makerspaceId:Int,token:String):Int {registrations+=makerspaceId to token;entered?.complete(Unit);release?.await();failure?.let {throw it};return nextId++}
        override suspend fun remove(makerspaceId:Int,deviceId:Int){removed+=makerspaceId to deviceId;removeFailure?.let {throw it}}
    }
    @Test fun noConsentOrPermissionNeverAcquiresTokenOrCallsServer()=runTest {
        val api=Gateway();val sdk=Provider();val store=Memory();val holder=PushStateHolder(api,sdk,store)
        holder.activate(ACCOUNT,true);assertEquals(0,sdk.calls);holder.enable(false);assertTrue(holder.state.value.consent);assertFalse(holder.state.value.permission);assertEquals(0,sdk.calls);assertTrue(api.registrations.isEmpty())
    }
    @Test fun explicitEnableRegistersEachMembershipAndOnlyPersistsFingerprint()=runTest {
        val api=Gateway();val sdk=Provider();val store=Memory();val holder=PushStateHolder(api,sdk,store)
        holder.activate(ACCOUNT,true);holder.enable(true)
        assertEquals(setOf(1,2),holder.state.value.registered);assertEquals(listOf(1,2),api.registrations.map {it.first});assertEquals(2,store.records("a").size);assertTrue(store.records("a").all {it.tokenHash.length==64&&!it.tokenHash.contains(TOKEN)})
        holder.activate(ACCOUNT,true);assertEquals(2,api.registrations.size)
    }
    @Test fun membershipAddedAndRemovedUsesReturnedIdsAndCurrentHeaderScope()=runTest {
        val api=Gateway();val store=Memory();val holder=PushStateHolder(api,Provider(),store)
        holder.activate(PushAccount("a",setOf(1)),true);holder.enable(true);holder.activate(ACCOUNT,true)
        assertEquals(listOf(1,2),api.registrations.map {it.first});holder.activate(PushAccount("a",setOf(2)),true)
        assertTrue(api.removed.contains(1 to 100));assertEquals(setOf(2),holder.state.value.registered)
    }
    @Test fun rotationRegistersNewTokenBeforeRemovingOldAssociation()=runTest {
        val api=Gateway();val store=Memory();val holder=PushStateHolder(api,Provider(),store)
        holder.activate(PushAccount("a",setOf(1)),true);holder.enable(true);holder.rotated("rotated_valid_token_123456789")
        assertEquals(2,api.registrations.size);assertEquals(listOf(1 to 100),api.removed);assertEquals(101,store.records("a").single().deviceId)
    }
    @Test fun sameReturnedIdOnRotationIsNotDeleted()=runTest {
        val api=Gateway();val holder=PushStateHolder(api,Provider(),Memory())
        holder.activate(PushAccount("a",setOf(1)),true);holder.enable(true);api.nextId=100;holder.rotated("rotated_valid_token_123456789")
        assertTrue(api.removed.isEmpty());assertEquals(setOf(1),holder.state.value.registered)
    }
    @Test fun disableRemovesEveryReturnedIdStopsSdkAndClearsPrivateState()=runTest {
        val api=Gateway();val sdk=Provider();val store=Memory();val gates=mutableListOf<Boolean>();val holder=PushStateHolder(api,sdk,store,deliveryGate={on,_->gates+=on})
        holder.activate(ACCOUNT,true);holder.enable(true);assertTrue(holder.disable())
        assertEquals(setOf(1 to 100,2 to 101),api.removed.toSet());assertEquals(1,sdk.stops);assertTrue(store.records("a").isEmpty());assertFalse(store.consent("a"));assertFalse(gates.last());assertTrue(holder.state.value.registered.isEmpty())
    }
    @Test fun accountSwitchCannotReuseOldConsentOrAssociation()=runTest {
        val api=Gateway();val sdk=Provider();val store=Memory();val holder=PushStateHolder(api,sdk,store)
        holder.activate(ACCOUNT,true);holder.enable(true);holder.activate(PushAccount("b",setOf(1)),true)
        assertFalse(holder.state.value.consent);assertTrue(holder.state.value.registered.isEmpty());assertTrue(api.removed.isEmpty());assertEquals(2,api.registrations.size)
        assertEquals(2,store.records("a").size);assertTrue(store.consent("a"));assertTrue(store.records("b").isEmpty());assertEquals(1,sdk.stops)
    }
    @Test fun retryAfterBlocksRepeatedAcquisitionUntilDeadline()=runTest {
        var clock=1000L;val api=Gateway();val sdk=Provider();val holder=PushStateHolder(api,sdk,Memory(),now={clock})
        holder.activate(ACCOUNT,true);api.failure=ApiFailure(429,"Wait for the server",null,30);holder.enable(true);assertEquals(31000L,holder.state.value.retryAtMillis);holder.enable(true);assertEquals(1,sdk.calls)
        clock=31001L;api.failure=null;holder.enable(true);assertEquals(setOf(1,2),holder.state.value.registered)
    }
    @Test fun disableDuringRegistrationWaitsThenDeletesCompletedIdAndBlocksLateGate()=runTest {
        val api=Gateway();api.entered=CompletableDeferred();api.release=CompletableDeferred();val gates=mutableListOf<Boolean>();val holder=PushStateHolder(api,Provider(),Memory(),deliveryGate={on,_->gates+=on})
        holder.activate(PushAccount("a",setOf(1)),true);val enable=launch {holder.enable(true)};api.entered!!.await();val off=async {holder.disable()};runCurrent();assertFalse(gates.last());api.release!!.complete(Unit);enable.join();assertTrue(off.await());assertEquals(listOf(1 to 100),api.removed);assertFalse(gates.last())
    }
    @Test fun logoutRetainsConsentButCannotBeRearmedByLateTokenOrEnable()=runTest {
        val api=Gateway();val sdk=Provider();val store=Memory();val gates=mutableListOf<Boolean>();val holder=PushStateHolder(api,sdk,store,deliveryGate={on,_->gates+=on})
        holder.activate(ACCOUNT,true);holder.enable(true);holder.disable(forgetConsent=false);val count=api.registrations.size;val acquisitions=sdk.calls
        holder.rotated("late_valid_token_1234567890");holder.enable(true)
        assertTrue(store.consent("a"));assertEquals(count,api.registrations.size);assertEquals(acquisitions,sdk.calls);assertFalse(gates.last())
        holder.activate(ACCOUNT,true);assertEquals(count+2,api.registrations.size)
    }
    @Test fun failedRemovalIdsAreRetainedForAuthenticatedCleanupRetry()=runTest {
        val api=Gateway();val store=Memory();val holder=PushStateHolder(api,Provider(),store)
        holder.activate(ACCOUNT,true);holder.enable(true);api.removeFailure=ApiFailure(503,"Unavailable",null,null)
        assertFalse(holder.disable());assertEquals(2,store.records("a").size);assertFalse(store.consent("a"));api.removeFailure=null
        assertTrue(holder.disable());assertTrue(store.records("a").isEmpty());assertEquals(4,api.removed.size)
    }
    @Test fun cleanup429StopsFurtherRequestsAndRetainsIdsUntilDeadline()=runTest {
        var clock=1000L;val api=Gateway();val store=Memory();val holder=PushStateHolder(api,Provider(),store,now={clock})
        holder.activate(ACCOUNT,true);holder.enable(true);api.removeFailure=ApiFailure(429,"Wait",null,30)
        assertFalse(holder.disable());assertEquals(31000L,holder.state.value.retryAtMillis);assertEquals(1,api.removed.size);assertEquals(2,store.records("a").size)
        assertFalse(holder.disable());assertEquals(1,api.removed.size);clock=31001;api.removeFailure=null
        assertTrue(holder.disable());assertEquals(3,api.removed.size);assertTrue(store.records("a").isEmpty())
    }
    @Test fun revokedMembershipCleanup429PreventsAllFollowingRegistrations()=runTest {
        var clock=1000L;val api=Gateway();val holder=PushStateHolder(api,Provider(),Memory(),now={clock})
        holder.activate(ACCOUNT,true);holder.enable(true);api.removeFailure=ApiFailure(429,"Wait",null,30)
        holder.activate(PushAccount("a",setOf(3)),true);assertEquals(1,api.removed.size);assertEquals(2,api.registrations.size)
        holder.activate(PushAccount("a",setOf(3)),true);assertEquals(1,api.removed.size);assertEquals(2,api.registrations.size)
        clock=31001;api.removeFailure=null;holder.activate(PushAccount("a",setOf(3)),true);assertEquals(listOf(1,2,3),api.registrations.map {it.first})
    }
    @Test fun isolatedDemoPauseClosesInFlightDeliveryWithoutServerOrSdkCleanup()=runTest {
        val api=Gateway();api.entered=CompletableDeferred();api.release=CompletableDeferred()
        val sdk=Provider();val store=Memory();val gates=mutableListOf<Boolean>()
        val holder=PushStateHolder(api,sdk,store,deliveryGate={on,_->gates+=on})
        holder.activate(PushAccount("a",setOf(1)),true)
        val enable=launch {holder.enable(true)};api.entered!!.await()
        holder.pause();assertFalse(gates.last());assertFalse(holder.state.value.busy)
        api.release!!.complete(Unit);enable.join()
        holder.rotated("late_valid_token_1234567890");holder.enable(true)
        assertFalse(gates.last());assertEquals(1,api.registrations.size)
        assertTrue(api.removed.isEmpty());assertEquals(0,sdk.stops);assertTrue(store.consent("a"))
        assertEquals(100,store.records("a").single().deviceId)
        holder.activate(PushAccount("a",setOf(1)),true)
        assertEquals(setOf(1),holder.state.value.registered);assertTrue(gates.last())
    }
    @Test fun losingAllMembershipsStopsDeliveryAndNeverAcquiresAnotherToken()=runTest {
        val api=Gateway();val sdk=Provider();val store=Memory();val gates=mutableListOf<Boolean>()
        val holder=PushStateHolder(api,sdk,store,deliveryGate={on,_->gates+=on})
        holder.activate(ACCOUNT,true);holder.enable(true);val requests=sdk.calls
        holder.activate(PushAccount("a",emptySet()),true)
        holder.rotated("late_valid_token_1234567890");holder.enable(true)
        assertEquals(requests,sdk.calls);assertFalse(gates.last());assertTrue(holder.state.value.registered.isEmpty())
        assertEquals(2,api.removed.size);assertTrue(store.records("a").isEmpty());assertTrue(store.consent("a"))
    }
    companion object {private const val TOKEN="valid_fcm_token_123456789012345";private val ACCOUNT=PushAccount("a",setOf(1,2))}
}
