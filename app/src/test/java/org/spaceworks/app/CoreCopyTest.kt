package org.spaceworks.app.copy

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.ConnectionStateHolder
import org.spaceworks.app.actions.*
import org.spaceworks.app.auth.AuthGateway
import org.spaceworks.app.auth.AuthStateHolder
import org.spaceworks.app.network.Errors
import org.spaceworks.app.network.MemoryTokenStore
import org.spaceworks.app.platform.MobileText

class CoreCopyTest {
    private val resolver=MobileText {key,args->"resolved:${key.english(*args)}"}

    @Test fun localFallbackHasProvenanceButIdenticalServerDetailNeverBecomesAKey() {
        val local=Errors.map(503,null)
        val server=Errors.map(503,"""{"detail":"The server is unavailable. Try again later."}""")
        assertEquals(local.detail,server.detail)
        assertNotNull(local.localCopy);assertNull(server.localCopy)
        assertEquals("resolved:The server is unavailable. Try again later.",resolver.apiFailure(local))
        assertEquals("The server is unavailable. Try again later.",resolver.apiFailure(server))
    }
    @Test fun nestedRemoteValidationIsVerbatimAndSpecialCodeCopyRemainsLocallyOwned() {
        val fields=Errors.map(400,"""{"profile":{"name":["Enter your name."]}}""")
        assertNull(fields.localCopy);assertEquals("profile.name: Enter your name.",resolver.apiFailure(fields))
        val special=Errors.map(409,"""{"code":"payment_rail_conflict","detail":"Remote rail detail."}""")
        assertNotNull(special.localCopy)
        assertEquals("resolved:Finish this payment on the web.",resolver.apiFailure(special))
        assertEquals(409,special.status);assertEquals("payment_rail_conflict",special.code)
    }
    @Test fun connectionValidationResolvesOwnedCopyWithoutAttemptingAnInvalidServer()=runTest {
        var healthCalls=0
        val holder=ConnectionStateHolder("http://remote.example",false,false,{healthCalls++},{},text=resolver)
        holder.connect()
        assertEquals(0,healthCalls)
        assertEquals("resolved:Enter a valid HTTPS server URL.",holder.state.value.error)
    }
    @Test fun customFormLocalValidationUsesResolverWhilePublishedQuestionLabelsStayUntouched() {
        val form=ActionForm(listOf(ActionQuestion("actual-id","Makerspace question", "number",true,emptyList())))
        val missing=ActionForms.errors(form,emptyMap(),resolver)
        assertEquals(mapOf("actual-id" to "resolved:This question is required."),missing)
        val malformed=ActionForms.errors(form,mapOf("actual-id" to JsonPrimitive("wrong")),resolver)
        assertEquals(mapOf("actual-id" to "resolved:Enter a valid number."),malformed)
        assertEquals("Makerspace question",form.questions.single().label)
    }
    @Test fun signInValidationUsesInjectedCopyWithoutCallingAnyApiOrCreatingTokens()=runTest {
        val api=object:AuthGateway {
            override suspend fun config()=error("Unexpected API")
            override suspend fun login(username:String,password:String)=error("Unexpected API")
            override suspend fun enroll(challenge:String)=error("Unexpected API")
            override suspend fun verify(challenge:String,code:String,enrollment:Boolean)=error("Unexpected API")
            override suspend fun me()=error("Unexpected API")
            override suspend fun logout()=error("Unexpected API")
        }
        val tokens=MemoryTokenStore()
        val holder=AuthStateHolder(api,tokens,text=resolver)
        holder.signIn("","")
        assertEquals("resolved:Enter your email or username and password.",holder.state.value.error)
        assertNull(tokens.read());assertFalse(holder.state.value.busy)
    }
}
