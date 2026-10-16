package org.spaceworks.app.platform

import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.rememberMobileText

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

enum class NativeDocumentKind(val extension:String,val mime:String) { PDF("pdf","application/pdf"), ZIP("zip","application/zip") }

/** Only app-private bytes are owned here, never API URLs, signed tokens or provider credentials. */
class NativeDocumentFile internal constructor(val file:File,val title:String,val kind:NativeDocumentKind,
    private val now:()->Long,private val schedule:(Long,()->Unit)->Unit):Closeable {
    val id=UUID.randomUUID().toString()
    private var closed=false;private var sharedUntil=0L
    @Synchronized fun retainForShare(){check(!closed && file.isFile);sharedUntil=maxOf(sharedUntil,now()+SHARE_LEASE);file.setLastModified(now())}
    @Synchronized fun isAvailable()=!closed && file.isFile
    @Synchronized override fun close(){if(closed)return;closed=true;removeWhenSafe()}
    @Synchronized private fun removeWhenSafe(){val remaining=sharedUntil-now();if(remaining>0)schedule(remaining){removeWhenSafe()} else if(!file.delete()&&file.exists())throw MobileCopyException(DocumentStrings.DOCUMENT_CLEANUP_FAILED)}
    override fun toString()="NativeDocumentFile(private)"
    companion object {internal const val SHARE_LEASE=15*60*1000L}
}

class NativeDocumentFiles(private val directory:File,private val now:()->Long=System::currentTimeMillis,
    private val schedule:(Long,()->Unit)->Unit={wait,work->cleanup.launch{delay(wait);work()};Unit}) {
    fun create(bytes:ByteArray,kind:NativeDocumentKind,title:String):NativeDocumentFile {
        require(bytes.size in 4..LIMIT && when(kind){NativeDocumentKind.PDF->bytes.size>=5&&bytes.copyOfRange(0,5).contentEquals("%PDF-".toByteArray());NativeDocumentKind.ZIP->listOf(byteArrayOf(80,75,3,4),byteArrayOf(80,75,5,6),byteArrayOf(80,75,7,8)).any{bytes.copyOfRange(0,4).contentEquals(it)}}) {throw MobileCopyException(DocumentStrings.DOCUMENT_UNAVAILABLE)}
        if(!directory.isDirectory&&!directory.mkdirs())throw MobileCopyException(DocumentStrings.DOCUMENT_PREPARE_FAILED)
        val file=File.createTempFile("document_",".${kind.extension}",directory)
        try{file.outputStream().use{it.write(bytes)}}catch(f:Exception){file.delete();throw f}
        return NativeDocumentFile(file,title.filterNot(Char::isISOControl).take(120),kind,now,schedule)
    }
    /** On cold launch only: interrupted share recipients retain at least the documented lease. */
    fun cleanStale(){directory.listFiles()?.filter{it.isFile&&it.name.matches(Regex("document_[A-Za-z0-9_-]+\\.(pdf|zip)"))&&it.lastModified()<now()-24*60*60*1000L}?.forEach{it.delete()}}
    companion object {private const val LIMIT=25*1024*1024;private val cleanup=CoroutineScope(SupervisorJob()+Dispatchers.IO)}
}

/** Native PDF raster preview plus explicit local save/share. FileProvider is configured by the host. */
@Composable fun NativeDocumentPanel(file:NativeDocumentFile,onClose:()->Unit) {
    val text=rememberMobileText()
    val title=file.title.ifBlank {text(DocumentStrings.DOCUMENT_DOCUMENT)}
    val textColors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.onPrimaryContainer)
    val outlineColors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.onPrimaryContainer)
    val context=LocalContext.current;val work=rememberCoroutineScope();var error by remember(file.id){mutableStateOf<String?>(null)}
    var page by remember(file.id){mutableIntStateOf(0)};var pages by remember(file.id){mutableIntStateOf(1)}
    var bitmap by remember(file.id,page){mutableStateOf<Bitmap?>(null)};var rendering by remember(file.id,page){mutableStateOf(file.kind==NativeDocumentKind.PDF)}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(file.kind.mime)){uri->
        if(uri!=null && file.isAvailable())work.launch {
            try{withContext(Dispatchers.IO){if(file.isAvailable())context.contentResolver.openOutputStream(uri)?.use{output->file.file.inputStream().use{it.copyTo(output)}}?:throw java.io.IOException(text(DocumentStrings.DOCUMENT_EXPORT_UNAVAILABLE))}}
            catch(c:CancellationException){throw c}catch(_:Exception){error=text(DocumentStrings.DOCUMENT_THE_DOCUMENT_COULD_NOT_BE_SAVED_TRY_ANOTHER_LOCATION)}
        }
    }
    val share=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){}
    fun send(view:Boolean){
        try {
            if(!file.isAvailable())return
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.documents",file.file)
            file.retainForShare()
            val intent=Intent(if(view)Intent.ACTION_VIEW else Intent.ACTION_SEND).apply {
                if(view)setDataAndType(uri,file.kind.mime) else {type=file.kind.mime;putExtra(Intent.EXTRA_STREAM,uri)}
                clipData=ClipData.newRawUri(text(DocumentStrings.DOCUMENT_DOCUMENT),uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            share.launch(Intent.createChooser(intent,if(view)text(DocumentStrings.DOCUMENT_OPEN_DOCUMENT) else text(DocumentStrings.DOCUMENT_SHARE_DOCUMENT)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }catch(_:Exception){error=text(DocumentStrings.DOCUMENT_NO_COMPATIBLE_APP_COULD_OPEN_THIS_DOCUMENT_YOU_CAN_SAVE_IT_I)}
    }
    LaunchedEffect(file.id,page){
        if(file.kind!=NativeDocumentKind.PDF)return@LaunchedEffect
        var prepared:Bitmap?=null
        try {
            withContext(Dispatchers.IO){
                ParcelFileDescriptor.open(file.file,ParcelFileDescriptor.MODE_READ_ONLY).use {descriptor->PdfRenderer(descriptor).use {renderer->
                    val total=renderer.pageCount;require(total>0);val selected=page.coerceIn(0,total-1)
                    renderer.openPage(selected).use {pdf->
                        val scale=minOf(2f,2048f/maxOf(pdf.width,pdf.height).coerceAtLeast(1));val w=(pdf.width*scale).toInt().coerceAtLeast(1);val h=(pdf.height*scale).toInt().coerceAtLeast(1)
                        prepared=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also{it.eraseColor(android.graphics.Color.WHITE);pdf.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)}
                    };withContext(Dispatchers.Main){pages=total}
                }}
            }
            bitmap=prepared;prepared=null
        }catch(c:CancellationException){throw c}catch(_:Exception){error=text(DocumentStrings.DOCUMENT_THIS_PDF_COULD_NOT_BE_PREVIEWED_SAVE_IT_OR_OPEN_IT_IN_ANOTHE)}
        finally{prepared?.recycle();rendering=false}
    }
    DisposableEffect(bitmap){val retained=bitmap;onDispose{retained?.recycle()}}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.widthIn(max=960.dp).fillMaxWidth(.94f).fillMaxHeight(.94f),shape=MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(title,style=MaterialTheme.typography.headlineSmall,modifier=Modifier.semantics{heading()})
                Text(text(DocumentStrings.DOCUMENT_THIS_FILE_MAY_CONTAIN_PRIVATE_ACCOUNT_INFORMATION_CHOOSE_A_T))
                error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Assertive})}
                if(rendering)CircularProgressIndicator(Modifier.semantics{contentDescription=text(DocumentStrings.DOCUMENT_PREPARING_PDF_PREVIEW)})
                bitmap?.let{Image(it.asImageBitmap(),text(DocumentStrings.DOCUMENT_1_S_PAGE_2_S_OF_3_S, title, page+1, pages),Modifier.fillMaxWidth())}
                if(file.kind==NativeDocumentKind.PDF){
                    Text(text(DocumentStrings.DOCUMENT_PAGE_1_S_OF_2_S, page+1, pages))
                    if(pages>1)Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick={page--},enabled=page>0&&!rendering,colors=textColors,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(text(DocumentStrings.DOCUMENT_PREVIOUS_PAGE))}
                        TextButton(onClick={page++},enabled=page+1<pages&&!rendering,colors=textColors,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(text(DocumentStrings.DOCUMENT_NEXT_PAGE))}
                    }
                    Text(text(DocumentStrings.DOCUMENT_FOR_SELECTABLE_TEXT_OR_ASSISTIVE_READING_OPEN_THE_PDF_IN_A_D),style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick={send(true)},colors=outlineColors,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(text(DocumentStrings.DOCUMENT_OPEN_IN_ANOTHER_APP))}
                }else Text(text(DocumentStrings.DOCUMENT_ZIP_ARCHIVE_SAVE_IT_TO_A_PRIVATE_LOCATION_TO_INSPECT_ITS_CON))
                OutlinedButton(onClick={export.launch("Document.${file.kind.extension}")},colors=outlineColors,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(text(DocumentStrings.DOCUMENT_SAVE_1_S, file.kind.extension.uppercase()))}
                OutlinedButton(onClick={send(false)},colors=outlineColors,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(text(DocumentStrings.DOCUMENT_SHARE_DOCUMENT))}
                TextButton(onClick=onClose,colors=textColors,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(text(DocumentStrings.DOCUMENT_DONE))}
            }
        }
    }
}
