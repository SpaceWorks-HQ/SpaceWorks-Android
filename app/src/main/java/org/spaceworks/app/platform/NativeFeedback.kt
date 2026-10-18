package org.spaceworks.app.platform

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

internal class NativeActionFeedback(private val view:View,private val owner:()->Any,
    private val active:()->Boolean) {
    private val gate=FeedbackTicketGate()
    fun begin()=gate.begin(owner())
    fun cancel()=gate.cancel()
    fun perform(kind:FeedbackKind)=finish(begin(),kind)
    fun finish(ticket:FeedbackTicket,kind:FeedbackKind?) {
        if(!gate.finish(ticket,owner(),kind!=null && active() && view.isAttachedToWindow && view.isHapticFeedbackEnabled))return
        val constant=when(kind) {
            FeedbackKind.SELECTION->HapticFeedbackConstants.CLOCK_TICK
            FeedbackKind.CONFIRM->if(Build.VERSION.SDK_INT>=30)HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
            FeedbackKind.REJECT->if(Build.VERSION.SDK_INT>=30)HapticFeedbackConstants.REJECT else HapticFeedbackConstants.CONTEXT_CLICK
            null->return
        }
        // The one-argument native API respects system touch-feedback settings and device support.
        // No IGNORE flags, custom vibration waveform or VIBRATE permission.
        view.performHapticFeedback(constant)
    }
}

@Composable internal fun rememberActionFeedback(owner:Any):NativeActionFeedback {
    val view=LocalView.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val currentOwner by rememberUpdatedState(owner)
    var mounted by remember(view,lifecycle){mutableStateOf(true)}
    val feedback=remember(view,lifecycle) {NativeActionFeedback(view,{currentOwner},
        {mounted && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)})}
    DisposableEffect(feedback,owner) {onDispose {feedback.cancel()}}
    DisposableEffect(feedback) {onDispose {mounted=false;feedback.cancel()}}
    return feedback
}
