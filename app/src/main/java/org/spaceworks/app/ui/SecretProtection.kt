package org.spaceworks.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

fun Context.activity():Activity?=when(this){is Activity->this;is ContextWrapper->baseContext.activity();else->null}
@Composable fun ProtectSecrets(enabled:Boolean) {
    val window=LocalContext.current.activity()?.window
    DisposableEffect(window,enabled) {
        val already=window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE)!=0
        if(enabled)window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {if(enabled && !already)window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
    }
}
