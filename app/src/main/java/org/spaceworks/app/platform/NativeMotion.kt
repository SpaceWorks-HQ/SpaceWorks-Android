package org.spaceworks.app.platform

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

val LocalReducedMotion=staticCompositionLocalOf {false}

@Composable fun rememberReducedMotion():Boolean {
    val context=LocalContext.current
    fun read()=Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f
    var reduced by remember(context){mutableStateOf(read())}
    DisposableEffect(context) {
        val observer=object:ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange:Boolean){reduced=read()}
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer)
        onDispose {context.contentResolver.unregisterContentObserver(observer)}
    }
    return reduced
}
