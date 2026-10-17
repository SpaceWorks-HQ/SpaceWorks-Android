package org.spaceworks.app.actions

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberScope

class DemoMemberActionsTest {
    private val first=MemberScope(1,"fab-lab-kochi")
    private val second=MemberScope(2,"maker-space-bengaluru")
    @Test fun submittedRequestIsIdempotentVisibleAndIsolatedFromAnotherMakerspace()=runTest {
        val api=DemoMemberWorkspace();val lines=listOf(RequestLine("101",2))
        val result=api.submitRequest(first,"A stool",lines,"stable-key")
        assertEquals(result,api.submitRequest(first,"A stool",lines,"stable-key"))
        assertEquals(2,api.requestStatus(first,result.publicToken).items.single().quantity)
        assertEquals(3,api.overview(first).requests.size)
        assertEquals(2,api.overview(second).requests.size)
        assertTrue(runCatching {api.requestStatus(second,result.publicToken)}.isFailure)
    }
    @Test fun renewalBookingAndRegistrationMutationsUpdateTheReadService()=runTest {
        val api=DemoMemberWorkspace()
        val renewed=api.renew(first,"101");assertEquals(renewed.dueAt,api.overview(first).loans.first().dueAt)
        assertTrue(runCatching {api.renew(first,"102")}.isFailure)
        val answers=mapOf("project" to JsonPrimitive("Sensor enclosure"),"safety" to JsonPrimitive(false))
        assertEquals("approved",api.book(first,"00000000-0000-0000-0000-000000000031","2026-11-02T10:00:00+05:30","2026-11-02T12:00:00+05:30",answers))
        val booking=api.bookings(first).items.last();assertEquals("Electronics bench",booking.spaceName)
        assertEquals("cancelled",api.cancelBooking(first,booking.id).status)
        assertEquals("cancelled",api.bookings(first).items.last().status)
        assertEquals("registered",api.register(first,"00000000-0000-0000-0000-000000000021",answers,null,null,null,"1",null))
        val registration=api.overview(first).registrations.last();assertTrue(api.registrationQR(first,registration.id).decodeToString().contains("<svg"))
        api.cancelRegistration(first,registration.id)
        assertEquals("cancelled",api.overview(first).registrations.last().status)
        assertTrue(runCatching {api.registrationQR(first,registration.id)}.isFailure)
    }
    @Test fun preferencesAreScopedAccountProfileIsSharedAndResetDiscardsAllDemoChanges()=runTest {
        val api=DemoMemberWorkspace();val profile=ActionProfile("Meera Makes","Meera","Nair","+919876543210")
        api.updateProfile(first,profile);assertEquals(profile,api.profile(first));assertEquals(profile,api.profile(second));assertEquals("Meera Makes",api.card(second).printedName)
        val hardware=api.preferences(first).first();assertTrue(hardware.mandatoryEvents.isNotEmpty())
        assertFalse(api.updatePreference(first,hardware.feature,hardware.channel,false,false).first().enabled)
        assertTrue(api.preferences(second).first().enabled)
        assertTrue(runCatching {api.updatePreference(first,"events","native_push",true,null)}.isFailure)
        api.resetAccount();assertEquals("Meera Nair",api.profile(first).displayName);assertTrue(api.preferences(first).first().enabled)
    }
}
