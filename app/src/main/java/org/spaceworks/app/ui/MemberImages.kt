package org.spaceworks.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.spaceworks.app.platform.StorageDocumentKind
import org.spaceworks.app.platform.StorageTransfer
import java.io.File

private const val IMAGE_BYTES=6*1024*1024
private fun boundedBitmap(bytes:ByteArray):Bitmap? {
    if(bytes.size>IMAGE_BYTES)return null
    val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    if(bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outWidth.toLong()*bounds.outHeight>32_000_000)return null
    val options=BitmapFactory.Options().apply {inSampleSize=1}
    while(maxOf(bounds.outWidth,bounds.outHeight)/options.inSampleSize>1200)options.inSampleSize*=2
    return BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
}
/** Generated API image URLs use a separate bounded, unauthenticated storage client. */
@Composable fun MemberImage(url:String?,description:String,modifier:Modifier=Modifier,qr:Boolean=false) {
    key(url){MemberImageContent(url,description,modifier,qr)}
}
@Composable private fun MemberImageContent(url:String?,description:String,modifier:Modifier,qr:Boolean) {
    val context=LocalContext.current
    var failure by remember(url){mutableStateOf(false)}
    val bitmap by produceState<Bitmap?>(null,url) {
        if(url.isNullOrBlank())return@produceState
        try {
            value=withContext(Dispatchers.IO) {
                if(url.startsWith("data:image/png;base64,")) {
                    require(url.length<=IMAGE_BYTES*4/3+64)
                    boundedBitmap(Base64.decode(url.substringAfter(','),Base64.DEFAULT))
                }else {
                    val cache=File(context.cacheDir,"member-images").apply {mkdirs()}
                    StorageTransfer(cache).download(url,StorageDocumentKind.IMAGE,"image",IMAGE_BYTES)
                        .use {boundedBitmap(it.file.readBytes())}
                }
            }
            failure=value==null
        }catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){failure=true}
    }
    if(bitmap!=null)Image(requireNotNull(bitmap).asImageBitmap(),description,modifier,
        contentScale=if(qr)ContentScale.Fit else ContentScale.Crop)
    else if(qr) {
        Column(modifier,verticalArrangement=Arrangement.Center) {
            if(failure)Text("Your membership QR code is unavailable. Try again later.",Modifier.semantics {liveRegion=LiveRegionMode.Polite})
            else {CircularProgressIndicator();Text("Loading membership card…")}
        }
    } else Surface(modifier,color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.medium) {
        Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.Center) {
            Icon(Icons.Outlined.Build,null,Modifier.size(40.dp),tint=MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
