package org.spaceworks.app.notifications

import okhttp3.HttpUrl
import org.spaceworks.app.platform.PushHint
import org.junit.Assert.*
import org.junit.Test

class MobileRoutesTest {
    private val server=HttpUrl.Builder().scheme("https").host("makers.example").build()
    @Test fun everyCanonicalDestinationUsesOnlyRealIdKinds(){
        val sections=listOf("home","inventory","activity","inbox","profile","payments","bookings","events","spaces")
        for(section in sections)assertNotNull(section,MobileRoutes.relative("/makerspaces/7/$section"))
        for(section in listOf("inventory","payments","registrations"))assertEquals("19",MobileRoutes.relative("/makerspaces/7/$section/19")?.resourceId)
        for(section in listOf("events","bookings","spaces"))assertEquals("00000000-0000-0000-0000-000000000001",MobileRoutes.relative("/makerspaces/7/$section/00000000-0000-0000-0000-000000000001")?.resourceId)
        for(path in listOf("/makerspaces/0/inbox","/makerspaces/-7/inbox","/makerspaces/true/inbox","/makerspaces/2147483648/inbox","/makerspaces/7/registrations","/makerspaces/7/events/19","/makerspaces/7/inventory/uuid","/makerspaces/7/home/1"))assertNull(path,MobileRoutes.relative(path))
    }
    @Test fun unknownProductRouteFallsBackToInboxWithoutInventingResource(){assertEquals(MobileDestination(7,MobileSection.INBOX),MobileRoutes.relative("/makerspaces/7/future-feature"))}
    @Test fun sameOriginAndCustomSchemeNormalizeWithoutChangingServer(){
        for(link in listOf("https://makers.example/makerspaces/7/inbox","spaceworks://makerspaces/7/inbox","spaceworks:///makerspaces/7/inbox"))assertEquals(link,MobileDestination(7,MobileSection.INBOX),MobileRoutes.external(link,server))
        assertNotNull(MobileRoutes.external("https://makers.example:443/makerspaces/7/profile",server))
    }
    @Test fun hostileAndExternalLinksNeverEnterRouter(){
        for(link in listOf("https://evil.example/makerspaces/7/inbox","http://makers.example/makerspaces/7/inbox","https://makers.example:444/makerspaces/7/inbox","https://user:password@makers.example/makerspaces/7/inbox","https://makers.example/makerspaces/7/inbox?secret=1","https://makers.example/makerspaces/7/inbox#x","https://makers.example/makerspaces/7/%69nbox","spaceworks://evil/makerspaces/7/inbox","javascript:alert(1)","spaceworks:///makerspaces/7/../inbox","spaceworks:///makerspaces/7/inbox\\evil","spaceworks:///makerspaces/7/inbox\u200b"))assertNull(link,MobileRoutes.external(link,server))
    }
    @Test fun legacyAliasesAreExactAndKeepStaffIntentExplicit(){
        assertEquals(LegacyMemberRoute("fab-lab",false),MobileRoutes.legacy("https://makers.example/m/fab-lab/member",server))
        assertEquals(LegacyMemberRoute("fab-lab",true),MobileRoutes.legacy("https://makers.example/m/fab-lab/admin/notifications",server))
        for(path in listOf("/m/fab-lab/admin/other","/m/fab-lab/member/history","/m/fab-lab/member/","/m/fab-lab/../member"))assertNull(path,MobileRoutes.legacy("https://makers.example$path",server))
        assertNull(MobileRoutes.legacy("https://evil.example/m/fab-lab/member",server))
    }
    @Test fun pushVersionAndMismatchedMakerCannotSelectDifferentScope(){
        assertEquals(MobileSection.ACTIVITY,MobileRoutes.hint(PushHint(7,null,null,null,null,"/makerspaces/7/activity",2)).section)
        for(version in listOf(0,1,3,99))assertEquals(MobileSection.INBOX,MobileRoutes.hint(PushHint(7,null,null,null,null,"/makerspaces/7/activity",version)).section)
        assertEquals(MobileDestination(7,MobileSection.INBOX),MobileRoutes.hint(PushHint(7,null,null,null,null,"/makerspaces/8/payments/9",2)))
    }
}
