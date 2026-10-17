package org.spaceworks.app.payments

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.spaceworks.app.platform.NativeDocumentFile
import org.spaceworks.app.platform.NativeDocumentFiles
import org.spaceworks.app.platform.NativeDocumentKind

typealias PaymentFile = NativeDocumentFile
fun interface PaymentFileStore {suspend fun create(bytes:ByteArray,title:String):PaymentFile}
/** The owner supplies its app-private cache/native-documents directory, matching FileProvider XML. */
class CachedPaymentFiles(directory:File):PaymentFileStore {
    private val documents=NativeDocumentFiles(directory)
    override suspend fun create(bytes:ByteArray,title:String):PaymentFile {
        var unclaimed:PaymentFile?=null
        try {
            val file=withContext(Dispatchers.IO) {documents.create(bytes,NativeDocumentKind.PDF,title).also {unclaimed=it}}
            unclaimed=null
            return file
        } finally {
            // Prompt dispatcher cancellation must not discard a just-created private file.
            withContext(NonCancellable+Dispatchers.IO){unclaimed?.close()}
        }
    }
}
