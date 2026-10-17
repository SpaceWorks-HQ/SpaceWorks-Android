package org.spaceworks.app.payments

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.*

class PaymentsGatewayTest {
    private val scope=MemberScope(7,"maker-space")
    private suspend fun withGateway(block:suspend(GeneratedPaymentsGateway,MockWebServer,ApiSession)->Unit){MockWebServer().use{server->server.start();val store=MemoryTokenStore().apply{replace(TokenPair("member-access","member-refresh"))};val session=ApiSession(server.url("/"),store){"999"};block(GeneratedPaymentsGateway(session),server,session)}}
    private fun json(body:String,status:Int=200)=MockResponse().setResponseCode(status).setHeader("Content-Type","application/json").setBody(body)
    private fun request(server:MockWebServer):RecordedRequest=requireNotNull(server.takeRequest(5,TimeUnit.SECONDS)).also{assertEquals("Bearer member-access",it.getHeader("Authorization"));assertEquals("7",it.getHeader("X-Makerspace-Id"));listOf("Cookie","Origin","Referer","X-Nonce").forEach{name->assertNull(name,it.getHeader(name))}}
    private suspend fun invalid(work:suspend()->Unit){try{work();fail("Unsafe document or reference accepted")}catch(_:IllegalArgumentException){}}
    private fun grant(server:MockWebServer,url:String=server.url("/api/v1/payment-documents/901/download?token=server-issued-token").toString(),expires:Int=60)=json("""{"url":"$url","expires_in":$expires}""")

    @Test fun paymentsProjectExactServerMoneyStatusSettlementAndRefunds()=runBlocking {withGateway{gateway,server,_->
        server.enqueue(json("[$PAYMENT]"));val row=gateway.payments(scope).single()
        assertEquals("501",row.id);assertEquals("250.25",row.amount);assertEquals("inr",row.currency);assertEquals("paid_offline",row.status)
        assertEquals("cash",row.settlement!!.method);assertEquals("DESK-002",row.settlement.reference)
        assertEquals("25.25",row.refunds.single().amount);assertEquals("partial_refund",row.refunds.single().reason)
        val request=request(server);assertEquals("GET",request.method);assertEquals("/api/v1/member/makerspaces/7/payments",request.path)
    }}
    @Test fun intentUsesOnlyActualChargeIDAndNoInventedVendorPayload()=runBlocking {withGateway{gateway,server,_->
        server.enqueue(json("""{"payment_id":501,"client_secret":"pi_server_secret","publishable_key":"pk_test_server","customer_id":"cus_server","ephemeral_key":"eph_server"}"""))
        val intent=gateway.intent(scope,"501");assertEquals("501",intent.paymentId);assertEquals("cus_server",intent.customerId)
        assertEquals("PaymentIntent(redacted)",intent.toString());val request=request(server)
        assertEquals("POST",request.method);assertEquals("/api/v1/member/makerspaces/7/payments/501/mobile-intent",request.path);assertEquals(0L,request.bodySize)
    }}
    @Test fun documentsUseActualPageAndPreserveCreditNoteMoneyAndNextPage()=runBlocking {withGateway{gateway,server,_->
        val next=server.url("/api/v1/member/makerspaces/7/payment-documents?page=3")
        server.enqueue(json("""{"count":2,"next":"$next","previous":null,"results":[$DOCUMENT]}"""))
        val page=gateway.documents(scope,1);assertEquals(3,page.nextPage);assertEquals("credit_note",page.rows.single().kind);assertEquals("25.25",page.rows.single().amount)
        assertEquals("/api/v1/member/makerspaces/7/payment-documents?page=1",request(server).path)
    }}
    @Test fun pdfConsumesFreshSameOriginGrantThroughAuthenticatedGeneratedEndpoint()=runBlocking {withGateway{gateway,server,_->
        server.enqueue(grant(server));server.enqueue(MockResponse().setHeader("Content-Type","application/pdf").setBody("%PDF-1.7\nfixture"))
        assertArrayEquals("%PDF-1.7\nfixture".toByteArray(),gateway.documentPDF(scope,"901"))
        assertEquals("/api/v1/member/makerspaces/7/payment-documents/901/download-url",request(server).path)
        assertEquals("/api/v1/payment-documents/901/download?token=server-issued-token",request(server).path)
    }}
    @Test fun accountReplacementWhileDocumentGrantIsDelayedNeverDownloadsWithNewCredentials()=runBlocking {
        MockWebServer().use{server->
            server.start()
            val store=MemoryTokenStore().apply{replace(TokenPair("member-access","member-refresh"))}
            val session=ApiSession(server.url("/"),store){"7"}
            val gateway=GeneratedPaymentsGateway(session)
            server.enqueue(grant(server).setBodyDelay(200,TimeUnit.MILLISECONDS))
            val loading=async(Dispatchers.IO){gateway.documentPDF(scope,"901")}
            assertEquals("/api/v1/member/makerspaces/7/payment-documents/901/download-url",request(server).path)
            store.replace(TokenPair("new-account-access","new-account-refresh"))
            try{loading.await();fail("Old account document was published")}catch(_:CancellationException){}
            assertEquals(1,server.requestCount)
            assertNull(server.takeRequest(100,TimeUnit.MILLISECONDS))
        }
    }
    @Test fun accountReplacementAfterGrantResponseButBeforeSecondCallNeverUsesOldToken()=runBlocking {
        MockWebServer().use{server->
            server.start()
            val store=MemoryTokenStore().apply{replace(TokenPair("member-access","member-refresh"))}
            val session=ApiSession(server.url("/"),store){"7"}
            var replaced=false
            val gateway=GeneratedPaymentsGateway(session,operationEpoch={
                // Deterministically replace the account after the grant call has completed but
                // before the next generated Call is constructed. No timing/sleep assumption.
                if(server.requestCount==1 && !replaced){replaced=true;store.replace(TokenPair("new-account-access","new-account-refresh"))}
                session.installationEpoch
            })
            server.enqueue(grant(server))
            try{gateway.documentPDF(scope,"901");fail("Grant crossed account installation")}catch(_:CancellationException){}
            assertTrue(replaced)
            assertEquals("/api/v1/member/makerspaces/7/payment-documents/901/download-url",request(server).path)
            assertEquals(1,server.requestCount)
            assertNull(server.takeRequest(100,TimeUnit.MILLISECONDS))
        }
    }
    @Test fun expiredOrExternalGrantNeverCreatesADownloadRequest()=runBlocking {withGateway{gateway,server,_->
        for(response in listOf(grant(server,expires=0),grant(server,"https://outside.invalid/api/v1/payment-documents/901/download?token=secret"),grant(server,server.url("/api/v1/payment-documents/902/download?token=secret").toString()))){
            val before=server.requestCount;server.enqueue(response);invalid{gateway.documentPDF(scope,"901")};assertEquals(before+1,server.requestCount);request(server)
        }
    }}
    @Test fun pdfRequiresMimeAndMagicAndChecksDeclaredSizeBeforeBuffering()=runBlocking {withGateway{gateway,server,_->
        for(response in listOf(MockResponse().setHeader("Content-Type","text/html").setBody("<html>sign in</html>"),MockResponse().setHeader("Content-Type","application/pdf").setBody("<html/>"),MockResponse().setHeader("Content-Type","application/pdf").setBody("%PDF-").setHeader("Content-Length",25*1024*1024+1))){
            server.enqueue(grant(server));server.enqueue(response);invalid{gateway.documentPDF(scope,"901")};request(server);request(server)
        }
    }}
    @Test fun unknownLengthPDFIsCappedWhileStreaming()=runBlocking {withGateway{gateway,server,_->
        server.enqueue(grant(server));server.enqueue(MockResponse().setHeader("Content-Type","application/pdf").setChunkedBody(Buffer().write(ByteArray(25*1024*1024+1)),8192))
        invalid{gateway.documentPDF(scope,"901")};request(server);request(server)
    }}
    @Test fun cancellingDeliveredPDFStopsTheSocketWithoutCrossThreadBodyClose()=runBlocking {withGateway{gateway,server,session->
        server.enqueue(grant(server));server.enqueue(MockResponse().setHeader("Content-Type","application/pdf").setBody("%PDF-1.7\n"+"x".repeat(4096)).throttleBody(1,1,TimeUnit.SECONDS))
        val loading=async(Dispatchers.IO){gateway.documentPDF(scope,"901")};request(server);request(server)
        withTimeout(5000){while(session.client.dispatcher.runningCallsCount()!=0)delay(5)}
        assertFalse(loading.isCompleted);withTimeout(3000){loading.cancelAndJoin()};assertTrue(loading.isCancelled)
    }}
    @Test fun intentRefusalsRetainServerAuthorityCodeAndCooldownWithoutAutomaticReplay()=runBlocking {withGateway{gateway,server,_->
        server.enqueue(json("""{"detail":"Finish this payment on the web.","code":"payment_rail_conflict"}""",409))
        try{gateway.intent(scope,"501");fail("Refusal became checkout")}catch(f:ApiFailure){assertEquals(409,f.status);assertEquals("payment_rail_conflict",f.code);assertEquals("Finish this payment on the web.",f.detail)};request(server)
        server.enqueue(json("""{"detail":"Wait.","code":"throttled"}""",429).setHeader("Retry-After","90"))
        try{gateway.intent(scope,"501");fail("Throttle became checkout")}catch(f:ApiFailure){assertEquals(429,f.status);assertEquals(90L,f.retryAfterSeconds)};request(server)
        assertEquals(2,server.requestCount)
    }}
    @Test fun malformedReferencesNeverReachTheGeneratedTransport()=runBlocking {withGateway{gateway,server,_->
        for(id in listOf("0","-1","0501","https://outside.invalid","true")){invalid{gateway.intent(scope,id)};invalid{gateway.documentPDF(scope,id)}}
        invalid{gateway.documents(scope,0)};assertEquals(0,server.requestCount)
    }}
    @Test fun documentGrantsRejectTraversalEncodingCredentialsSchemesAndAmbiguousQueries(){
        val origin="https://example.invalid/".toHttpUrl()
        assertEquals("abc",PaymentDocumentLinks.token("https://EXAMPLE.invalid:443/api/v1/payment-documents/901/download?token=abc","901",origin))
        assertEquals("abc",PaymentDocumentLinks.token("/api/v1/payment-documents/901/download?token=abc","901",origin))
        listOf("https://example.invalid.evil/api/v1/payment-documents/901/download?token=abc","http://example.invalid/api/v1/payment-documents/901/download?token=abc","https://user@example.invalid/api/v1/payment-documents/901/download?token=abc","/api/v1/other/../payment-documents/901/download?token=abc","/api/v1/payment-documents/%39%30%31/download?token=abc","/api/v1/payment-documents/901/download?token=a&token=b","/api/v1/payment-documents/901/download?token=a#secret","/api/v1/payment-documents/901/download?token=%00","/api/v1/payment-documents/901/download?%74oken=abc").forEach{value->try{PaymentDocumentLinks.token(value,"901",origin);fail("Invalid grant accepted")}catch(_:Exception){}}
        listOf("https://outside.invalid/api/v1/member/makerspaces/7/payment-documents?page=2","/api/v1/member/makerspaces/8/payment-documents?page=2","/api/v1/member/makerspaces/7/payment-documents?page=1","/api/v1/member/makerspaces/7/payment-documents?page=2&page=3").forEach{value->try{PaymentDocumentLinks.next(value,1,scope,origin);fail("Invalid page accepted")}catch(_:Exception){}}
        assertEquals(3,PaymentDocumentLinks.next("/api/v1/member/makerspaces/7/payment-documents?page=3",1,scope,origin))
    }
    companion object {
        private const val DOCUMENT="""{"id":901,"payment":501,"number":"CN-001","kind":"credit_note","issued_at":"2026-10-16T09:00:00Z","money_at":"2026-10-16T09:00:00Z","amount":"25.25","currency":"inr"}"""
        private const val PAYMENT="""{"id":501,"subject_type":"booking","subject_label":"Workshop","status":"paid_offline","amount":"250.25","currency":"inr","due_at":null,"due_date":"2026-10-20","due_state":"upcoming","checkout_url":"https://ignored.invalid/checkout","online_payment_available":false,"settlement":{"method":"cash","received_at":"2026-10-16T09:00:00Z","reference":"DESK-002"},"refunded_amount":25.25,"refunds":[{"amount":"25.25","currency":"inr","status":"paid","reason":"partial_refund","settled_at":null,"method":"cash","reference":"REFUND-001","refunded_at":null}],"created_at":"2026-10-16T09:00:00Z"}"""
    }
}
