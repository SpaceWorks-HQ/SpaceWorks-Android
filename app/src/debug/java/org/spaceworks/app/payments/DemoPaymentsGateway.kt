package org.spaceworks.app.payments

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure

/** Debug source-set only: isolated labelled samples, never a provider or network client. */
class DemoPaymentsGateway:PaymentsGateway,PaymentPreviewControl {
    private data class Sample(var paid:Boolean=false,var offline:Boolean=false)
    private val samples=mutableMapOf<Int,Sample>()
    private fun sample(scope:MemberScope)=samples.getOrPut(scope.id){Sample()}
    override fun resetAccount(){samples.clear()}
    override suspend fun payments(scope:MemberScope):List<PaymentRecord>{val data=sample(scope);if(data.offline)throw IOException("Demo offline")
        return listOf(
            PaymentRecord("501","Sample equipment deposit",if(data.paid)"paid_online" else "pending","250.00","inr","2026-10-20","upcoming",true,null,emptyList()),
            PaymentRecord("502","Sample workshop booking","paid_offline","150.00","inr","2026-10-20","upcoming",false,PaymentSettlement("cash","2026-10-16T09:00:00Z","DEMO-DESK-002"),emptyList()))
    }
    override suspend fun intent(scope:MemberScope,paymentId:String):PaymentIntent=throw IllegalStateException("Demo mode never opens a payment provider.")
    override suspend fun documents(scope:MemberScope,page:Int):PaymentDocumentPage {val data=sample(scope);if(data.offline)throw IOException("Demo offline")
        val rows=buildList{if(data.paid)add(document("901","501","DEMO-RECEIPT-001","250.00"));add(document("902","502","DEMO-RECEIPT-002","150.00"))}
        return PaymentDocumentPage(if(page==1)rows else emptyList(),null,rows.size)
    }
    override suspend fun documentPDF(scope:MemberScope,documentId:String):ByteArray {
        val data=sample(scope);if(data.offline)throw IOException("Demo offline")
        if(documentId!="902" && !(documentId=="901"&&data.paid))throw ApiFailure(404,"Demo document unavailable.","not_found",null)
        val pdf=PdfDocument()
        return try {
            val page=pdf.startPage(PdfDocument.PageInfo.Builder(595,842,1).create());val paint=Paint().apply{color=android.graphics.Color.BLACK;textSize=18f;isAntiAlias=true}
            listOf("SPACE WORKS","DEMO RECEIPT - SAMPLE DATA","No money moved.","This document previews the receipt flow.").forEachIndexed{index,line->page.canvas.drawText(line,48f,80f+index*48f,paint)}
            pdf.finishPage(page);ByteArrayOutputStream().use{pdf.writeTo(it);it.toByteArray()}
        } finally {pdf.close()}
    }
    override suspend fun scenario(scope:MemberScope,paymentId:String,value:DemoPaymentScenario){require(paymentId=="501");val data=sample(scope);data.offline=false
        when(value){DemoPaymentScenario.PAID->data.paid=true;DemoPaymentScenario.OFFLINE->data.offline=true;DemoPaymentScenario.REFUSED->throw ApiFailure(503,"Online payment is unavailable. Try again later or pay at the desk.","payments_unavailable",null);else->data.paid=false}
    }
    override suspend fun confirmDelayed(scope:MemberScope,paymentId:String){require(paymentId=="501");sample(scope).apply{paid=true;offline=false}}
    private fun document(id:String,payment:String,number:String,amount:String)=PaymentDocument(id,payment,number,"receipt",amount,"inr","2026-10-16T09:00:00Z")
}
