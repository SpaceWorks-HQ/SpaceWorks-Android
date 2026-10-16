package org.spaceworks.app.platform

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** App-owned copy only. Server/user text never becomes a resource key. */
data class MobileCopyKey(val resourceId:Int,val english:String,val englishOne:String?=null) {
    fun english(vararg arguments:Any):String = String.format(Locale.ENGLISH,
        if(englishOne!=null && (arguments.firstOrNull() as? Number)?.toLong()==1L)englishOne else english,*arguments)
}

/** Inject resources into long-lived state; the English fallback keeps pure JVM fixtures Android-free. */
fun interface MobileText {
    fun resolve(key:MobileCopyKey,arguments:Array<out Any>):String
    operator fun invoke(key:MobileCopyKey,vararg arguments:Any)=resolve(key,arguments)
    companion object {
        val ENGLISH=MobileText {key,args->key.english(*args)}
        fun resources(context:Context):MobileText {
            val resources=context.resources
            return MobileText {key,args->
                if(key.englishOne==null)resources.getString(key.resourceId,*args)
                else resources.getQuantityString(key.resourceId,(args.firstOrNull() as? Number)?.toInt()?:0,*args)
            }
        }
    }
}

@Composable fun rememberMobileText():MobileText {
    val context=LocalContext.current
    val configuration=LocalConfiguration.current
    return remember(context,configuration){MobileText.resources(context)}
}

/** A local validation failure retains its resource identity until the presentation boundary. */
class MobileCopyException(val copy:MobileCopyKey,vararg val arguments:Any):IllegalArgumentException(copy.english(*arguments))
fun MobileText.failure(error:MobileCopyException)=invoke(error.copy,*error.arguments)
