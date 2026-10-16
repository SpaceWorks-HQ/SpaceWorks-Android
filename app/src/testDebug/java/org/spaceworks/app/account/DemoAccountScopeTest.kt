package org.spaceworks.app.account

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import java.util.UUID

class DemoAccountScopeTest {
    private val one=MemberScope(1,"first")
    private val two=MemberScope(2,"second")

    @Test fun membershipLeaveAndRenewalAffectOnlyTheirActualMakerWhileAccountContactStaysGlobal()=runTest {
        val api=DemoAccountGateway()
        api.cancelRenewal(one,"Preview cancellation")
        assertNotNull(api.membership(one).renewalCancelledAt)
        assertEquals(AccountMembership("active",null),api.membership(two))
        val left=api.leave(one,"Preview leave")
        assertEquals("revoked",left.status);assertEquals(AccountMembership("active",null),api.membership(two))
        api.cancelRenewal(two,"Other cancellation");assertEquals(left,api.membership(one))
        assertNotNull(api.membership(two).renewalCancelledAt)
        api.startPhone(one,"+919876543210")
        assertTrue(api.confirmPhone(two,"+919876543210","123456").verified)
        api.unlinkProvider(one,"google");assertTrue(api.providers(two).isEmpty())
    }
    @Test fun exportJobsAndReadinessRemainMakerBoundAndForeignOrUnknownIdsAreRefused()=runTest {
        val api=DemoAccountGateway();val first=api.requestExport(one)
        assertTrue(api.exports(two).items.isEmpty())
        val second=api.requestExport(two);assertNotEquals(first.id,second.id)
        assertEquals(first.id,UUID.fromString(first.id).toString())
        assertEquals(listOf(first.id),api.exports(one).items.map{it.id})
        assertEquals(listOf(second.id),api.exports(two).items.map{it.id})
        api.export(one,first.id);assertEquals("pending",api.exports(two).items.single().status)
        assertArrayEquals(byteArrayOf(80,75),api.exportArchive(one,first.id).take(2).toByteArray())
        for(id in listOf(first.id,"unknown-job")){
            try{api.export(two,id);fail("Foreign/unknown export must be refused")}
            catch(f:ApiFailure){assertEquals(404,f.status)}
            try{api.exportArchive(two,id);fail("Foreign/unknown archive must be refused")}
            catch(f:ApiFailure){assertEquals(404,f.status)}
        }
        val later=api.requestExport(one);assertNotEquals(first.id,later.id)
        assertEquals(setOf(first.id,later.id),api.exports(one).items.map{it.id}.toSet())
        assertEquals(listOf(second.id),api.exports(two).items.map{it.id})
    }
    @Test fun deletionFixtureUsesActualMakerIdsPreservingDetailsAndGlobalOnlyZeroMembershipConflict() {
        val source=DemoAccountDeletion.obligations
        val mapped=DemoAccountDeletion(listOf(1,2,2)){1000L};mapped.review()
        assertEquals(setOf(1,2,null),mapped.state.value.blockers.map{it.makerspaceID}.toSet())
        assertEquals(source.map{Triple(it.code,it.count,it.detail)},mapped.state.value.blockers.map{Triple(it.code,it.count,it.detail)})
        val unknown=mapped.state.value.blockers.single{it.code=="future_backend_code"}
        assertEquals(2,unknown.makerspaceID);assertEquals(source.last().detail,unknown.detail);assertEquals(DemoDeletionFix.SUPPORT,unknown.fix)
        mapped.choose(DemoDeletionScenario.CONFLICT);mapped.review();mapped.authenticate(DemoDeletionProof.PASSWORD,"DemoPass!123")
        assertEquals(301000L,mapped.state.value.proofExpiresAt);assertFalse(mapped.confirm("DELETE"))
        assertEquals(1,mapped.state.value.blockers.single().makerspaceID)
        val single=DemoAccountDeletion(listOf(2));single.review()
        assertTrue(single.state.value.blockers.all{it.makerspaceID==null||it.makerspaceID==2})
        val empty=DemoAccountDeletion(emptyList()){1000L};empty.review()
        assertTrue(empty.state.value.blockers.isNotEmpty());assertTrue(empty.state.value.blockers.all{it.makerspaceID==null})
        empty.choose(DemoDeletionScenario.CONFLICT);empty.review();empty.authenticate(DemoDeletionProof.PHONE,"123456")
        assertFalse(empty.confirm("DELETE"));assertTrue(empty.state.value.conflict)
        assertTrue(empty.state.value.blockers.all{it.makerspaceID==null});assertEquals(DemoDeletionFix.SUPPORT,empty.state.value.blockers.single().fix)
        val original=DemoAccountDeletion{1000L};original.review()
        assertEquals(setOf(7,8,null),original.state.value.blockers.map{it.makerspaceID}.toSet())
        try{DemoAccountDeletion(listOf(1,0));fail("Nonpositive actual ID must be refused")}
        catch(_:IllegalArgumentException){}
    }
}
