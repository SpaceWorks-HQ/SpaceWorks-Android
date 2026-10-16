package org.spaceworks.app.notifications

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.HttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.platform.PushHint

@OptIn(ExperimentalCoroutinesApi::class)
class LinkStateHolderTest {
    private val server=HttpUrl.Builder().scheme("https").host("maker.example").build()
    @Test fun unknownPushVersionRefetchesIdentityBeforeAuthorizedInboxSelection()=runTest {
        val events=mutableListOf<String>();val holder=LinkStateHolder(server,{events+="me:$it";true},{events+="select:$it"})
        holder.offer(PushHint(7,null,null,"not-an-id",null,"/makerspaces/7/payments/5",99));assertTrue(events.isEmpty());holder.resolve()
        assertEquals(listOf("me:7","select:7"),events);assertEquals(MobileSection.INBOX,holder.state.value.delivered?.destination?.section)
    }
    @Test fun unauthorizedOrFailedIdentityNeverSelectsMakerOrDisplaysDestination()=runTest {
        val selected=mutableListOf<Int>();val holder=LinkStateHolder(server,{false},{selected+=it});holder.offer("spaceworks://makerspaces/7/inventory/19");holder.resolve()
        assertTrue(selected.isEmpty());assertNull(holder.state.value.delivered);assertNotNull(holder.state.value.error)
        val failed=LinkStateHolder(server,{throw java.io.IOException("private failure")},{selected+=it});failed.offer(PushHint(7,null,null,null,null,null,1));failed.resolve();assertFalse(failed.state.value.error.orEmpty().contains("private"));assertTrue(selected.isEmpty())
    }
    @Test fun staleIdentityResponseCannotNavigateAfterAccountClearOrNewerTap()=runTest {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val selected=mutableListOf<Int>();val holder=LinkStateHolder(server,{started.complete(Unit);release.await();true},{selected+=it})
        holder.offer("spaceworks://makerspaces/7/inbox");val read=launch {holder.resolve()};started.await();holder.clear();release.complete(Unit);read.join();assertTrue(selected.isEmpty());assertNull(holder.state.value.delivered)
    }
    @Test fun overlappingTapsResolveLatestIntentWithoutRepeatedAuthorization()=runTest {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var calls=0;val selected=mutableListOf<Int>();val holder=LinkStateHolder(server,{calls++;if(calls==1){started.complete(Unit);release.await()};true},{selected+=it})
        holder.offer("spaceworks://makerspaces/7/inbox");val first=launch {holder.resolve()};started.await();holder.offer("spaceworks://makerspaces/8/activity");holder.resolve();release.complete(Unit);first.join();assertEquals(listOf(8),selected);assertEquals(MobileSection.ACTIVITY,holder.state.value.delivered?.destination?.section)
        holder.consumed(holder.state.value.delivered!!.sequence);assertNull(holder.state.value.delivered)
    }
    @Test fun legacySlugIsMappedOnlyByFreshAuthorizedMemberships()=runTest {
        val events=mutableListOf<String>();val holder=LinkStateHolder(server,{events+="id:$it";false},{events+="select:$it"},{slug,staff->events+="fresh:$slug";if(slug=="fab-lab"&&!staff)7 else null})
        holder.offer("https://maker.example/m/fab-lab/member");holder.resolve();assertEquals(listOf("fresh:fab-lab","select:7"),events);assertEquals(MobileDestination(7,MobileSection.HOME),holder.state.value.delivered?.destination)
        holder.offer("https://maker.example/m/other/member");holder.resolve();assertEquals(3,events.size);assertNull(holder.state.value.delivered)
    }
    @Test fun legacyPushBindsHintMakerAndStaffAliasFailsWithoutVerifiedAuthority()=runTest {
        val selected=mutableListOf<Int>();val holder=LinkStateHolder(server,{true},{selected+=it},{slug,staff->if(slug=="fab-lab"&&!staff)7 else null})
        holder.offer(PushHint(7,null,null,null,null,"/m/fab-lab/member",2));holder.resolve();assertEquals(MobileSection.HOME,holder.state.value.delivered?.destination?.section)
        holder.offer(PushHint(8,null,null,null,null,"/m/fab-lab/member",2));holder.resolve();assertNull(holder.state.value.delivered)
        holder.offer(PushHint(7,null,null,null,null,"/m/fab-lab/admin/notifications",2));holder.resolve();assertNull(holder.state.value.delivered);assertEquals(listOf(7),selected)
    }
    @Test fun synchronousSelectionInvalidationCannotPublishOldDestination()=runTest {
        lateinit var holder:LinkStateHolder
        holder=LinkStateHolder(server,{true},{holder.clear()});holder.offer("spaceworks://makerspaces/7/inbox");holder.resolve();assertNull(holder.state.value.delivered);assertNull(holder.state.value.pending)
    }
    @Test fun externalOrMalformedLinkDoesNotCallIdentity()=runTest {var calls=0;val holder=LinkStateHolder(server,{calls++;true},{});holder.offer("https://evil.example/makerspaces/7/inbox");holder.resolve();assertEquals(0,calls);assertNotNull(holder.state.value.error)}
    @Test fun notificationOriginSurvivesAuthorizationButExternalLinkDoesNotAcquireIt()=runTest {
        val holder=LinkStateHolder(server,{true},{})
        holder.offer(PushHint(1,null,null,null,null,"/makerspaces/1/inventory/101",2));holder.resolve()
        assertTrue(holder.state.value.delivered!!.notification)
        holder.offer("https://maker.example/makerspaces/1/inventory/101");holder.resolve()
        assertFalse(holder.state.value.delivered!!.notification)
    }

    @Test fun inboxRelativeLinksAreSelectedServerNavigationOnly()=runTest {
        val holder=LinkStateHolder(server,{it==7},{})
        holder.offer("/makerspaces/7/inventory/101");holder.resolve()
        assertEquals(MobileSection.INVENTORY,holder.state.value.delivered!!.destination.section)
        holder.offer("//elsewhere.example/makerspaces/7/inbox");assertNotNull(holder.state.value.error)
        holder.offer("/m/fab-lab/member");assertNotNull(holder.state.value.pendingLegacy)
    }

}
