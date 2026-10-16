package org.spaceworks.app
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
class ConnectionStateTest {
    @Test fun connectsOnlyAfterHealthAndPersistsNormalizedAddress() = runBlocking {
        var saved=""
        val holder=ConnectionStateHolder("",false,false,{assertEquals("example.org",it.host)},{saved=it})
        holder.edit("https://example.org")
        holder.connect()
        assertTrue(holder.state.value.connected);assertEquals("https://example.org/",saved)
    }
    @Test fun failedHealthAndInvalidInputStayOnSetup() = runBlocking {
        var calls=0
        val holder=ConnectionStateHolder("http://example.org",false,false,{calls++;error("Unavailable")},{fail("Should not save")})
        holder.connect();assertEquals(0,calls);assertFalse(holder.state.value.connected)
        holder.edit("https://example.org");holder.connect();assertEquals(1,calls)
        assertEquals("Unavailable",holder.state.value.error);assertFalse(holder.state.value.connecting)
    }
    @Test fun brandedServerCannotBeEdited() {
        val holder=ConnectionStateHolder("https://fixed.example",true,false,{}, {})
        holder.edit("https://other.example");assertEquals("https://fixed.example",holder.state.value.url)
    }
}
