package org.spaceworks.app.account

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.MemoryTokenStore
import org.spaceworks.app.network.TokenPair

class NativeAccountBoundaryTest {
    private class Fixture {
        val tokens=MemoryTokenStore().apply {replace(TokenPair("a","r"))}
        var accepted:Long?=tokens.snapshot().epoch
        var user:String?="41"
        var scope:MemberScope?=MemberScope(7,"forge")
        var ends=0;var refreshes=0;var leaves=0
        val runtime=NativeAccountRuntime("https://forge.invalid/",tokens,{accepted},{user},{scope},
            {ends++;tokens.replace(null);user=null;true},{refreshes++},{leaves++;true})
        var active:NativeAccountRuntime?=runtime
        val boundary=NativeAccountBoundary{active}
        val identity=requireNotNull(boundary.identity())
    }
    @Test fun identityRequiresFreshlyAcceptedIdentityNotJustInstalledPair() {
        val f=Fixture();assertEquals(f.identity,f.boundary.identity())
        f.accepted=null;assertNull(f.boundary.identity());assertNull(f.boundary.selected())
        f.accepted=f.tokens.snapshot().epoch;f.tokens.replace(TokenPair("next","next-r"))
        assertNull(f.boundary.identity());assertNull(f.boundary.selected())
    }
    @Test fun sameInstallationRotationRetainsIdentityAndCallbacks()=runTest {
        val f=Fixture();val before=f.tokens.snapshot()
        assertTrue(f.tokens.rotateIfCurrent(before,TokenPair("rotated","rotated-r")))
        assertEquals(f.identity,f.boundary.identity())
        f.boundary.foreground(f.identity);assertTrue(f.boundary.dropMembership(f.identity,7))
        assertEquals(1,f.refreshes);assertEquals(1,f.leaves)
    }
    @Test fun replacementWithSameMakerNeverReceivesOldAccountCallbacks()=runTest {
        val f=Fixture();f.tokens.replace(TokenPair("other","other-r"));f.accepted=f.tokens.snapshot().epoch;f.user="42"
        assertFalse(f.boundary.end(f.identity,"old"));f.boundary.foreground(f.identity)
        assertFalse(f.boundary.dropMembership(f.identity,7));assertEquals(0,f.ends+f.refreshes+f.leaves)
        assertFalse(f.boundary.ended(f.identity))
    }
    @Test fun coreClearedSuccessorAllowsOnlyOriginalCachedUserLocalEnd()=runTest {
        val f=Fixture();assertTrue(f.tokens.rotateIfCurrent(f.tokens.snapshot(),null))
        assertNull(f.boundary.identity());assertTrue(f.boundary.ended(f.identity))
        assertTrue(f.boundary.end(f.identity,"expired"));assertEquals(1,f.ends)
        // End callback fixture writes a further epoch; exact successor proof no longer holds.
        assertFalse(f.boundary.ended(f.identity))
        val g=Fixture();g.tokens.replace(null);g.user="42"
        assertFalse(g.boundary.end(g.identity,null));assertEquals(0,g.ends)
    }
    @Test fun laterLoginThenLogoutAndOriginChangeRejectProofAndCallbacks()=runTest {
        val f=Fixture();f.tokens.replace(TokenPair("next","next-r"));f.tokens.replace(null)
        assertFalse(f.boundary.ended(f.identity));assertFalse(f.boundary.end(f.identity,null))
        val other=Fixture();f.active=other.runtime
        val foreign=f.identity.copy(origin="https://other.invalid/")
        assertFalse(f.boundary.ended(foreign));assertFalse(f.boundary.end(foreign,null))
        f.boundary.foreground(foreign);assertFalse(f.boundary.dropMembership(foreign,7))
        assertEquals(0,other.ends+other.refreshes+other.leaves)
        f.active=null;assertNull(f.boundary.identity());assertFalse(f.boundary.ended(f.identity))
    }
    @Test fun acceptedEndExposesNoAccountButRetainsExactInstallationProof()=runTest {
        val f=Fixture();assertTrue(f.boundary.end(f.identity,"done"))
        assertNull(f.boundary.identity());assertNull(f.boundary.selected());assertTrue(f.boundary.ended(f.identity))
        assertFalse(f.boundary.end(f.identity,null));assertEquals(1,f.ends)
    }
}
