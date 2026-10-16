package org.spaceworks.app.platform

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeDocumentFilesTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun bytesRequireMatchingMagicAndSizeAndNeverBecomeAnExecutableFilename(){
        val files=NativeDocumentFiles(temporary.newFolder());val pdf=files.create("%PDF-1.7\nsample".toByteArray(),NativeDocumentKind.PDF,"../../Private\u0000name.pdf")
        assertFalse(pdf.file.name.contains("Private"));assertFalse(pdf.title.contains('\u0000'));assertTrue(pdf.file.name.endsWith(".pdf"));pdf.close();assertFalse(pdf.file.exists())
        for((bytes,kind) in listOf("<html/>".toByteArray() to NativeDocumentKind.PDF,"%PDF-1.7".toByteArray() to NativeDocumentKind.ZIP,byteArrayOf(80,75,3,4) to NativeDocumentKind.PDF,ByteArray(25*1024*1024+1) to NativeDocumentKind.ZIP)){
            try{files.create(bytes,kind,"Document");fail("Unsupported bytes became a document")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun zipRemainsLocalAndExplicitCloseRemovesUnsharedFiles(){
        val file=NativeDocumentFiles(temporary.newFolder()).create(byteArrayOf(80,75,5,6),NativeDocumentKind.ZIP,"My data")
        assertTrue(file.isAvailable());assertTrue(file.file.name.endsWith(".zip"));file.close();file.close();assertFalse(file.file.exists());assertFalse(file.isAvailable())
    }
    @Test fun shareLeaseRetainsFileAfterViewCloseThenDeletesWithoutReopeningIt(){
        var clock=0L;val pending=mutableListOf<Pair<Long,()->Unit>>()
        val file=NativeDocumentFiles(temporary.newFolder(),{clock},{wait,work->pending+=wait to work}).create("%PDF-1.7".toByteArray(),NativeDocumentKind.PDF,"Receipt")
        file.retainForShare();file.close();assertTrue(file.file.exists());assertFalse(file.isAvailable());assertEquals(15*60*1000L,pending.single().first)
        clock=15*60*1000L;pending.removeAt(0).second();assertFalse(file.file.exists())
        try{file.retainForShare();fail("Closed document reopened") }catch(_:IllegalStateException){}
    }
    @Test fun repeatedSharesExtendTheLeaseAndColdSweepOnlyDeletesStaleOwnedDocuments(){
        var clock=1000L;val pending=mutableListOf<Pair<Long,()->Unit>>();val directory=temporary.newFolder()
        val files=NativeDocumentFiles(directory,{clock},{wait,work->pending+=wait to work});val file=files.create("%PDF-1.7".toByteArray(),NativeDocumentKind.PDF,"Receipt")
        file.retainForShare();clock=5000L;file.retainForShare();file.close();assertEquals(15*60*1000L,pending.single().first)
        val other=java.io.File(directory,"unrelated.txt").apply{writeText("keep");setLastModified(1)}
        val stale=java.io.File(directory,"document_old.pdf").apply{writeText("old");setLastModified(1)}
        val fresh=java.io.File(directory,"document_fresh.zip").apply{writeText("new")}
        clock=25*60*60*1000L;fresh.setLastModified(clock);files.cleanStale()
        assertTrue(other.exists());assertFalse(stale.exists());assertTrue(fresh.exists())
    }
}
