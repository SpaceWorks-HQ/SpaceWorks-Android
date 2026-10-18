package org.spaceworks.app

import android.content.Context
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.spaceworks.app.platform.FeedbackKind
import org.spaceworks.app.platform.NativeActionFeedback

/** Proves native dispatch/settings gates, not the physical tactile quality of emulator hardware. */
class NativeFeedbackTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private class FeedbackView(context:Context):View(context) {
        var calls=0
        override fun performHapticFeedback(feedbackConstant:Int):Boolean {calls++;return true}
    }
    @Test fun attachedNativeViewDispatchesFreshOutcomeOnlyAndRespectsViewDisablement() {
        lateinit var view:FeedbackView
        compose.setContent {AndroidView(factory={FeedbackView(it).also {created->view=created}},modifier=Modifier.size(48.dp))}
        compose.runOnIdle {
            assertTrue(view.isAttachedToWindow)
            var owner="account-a"
            val feedback=NativeActionFeedback(view,{owner},{compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)})
            assertEquals(0,view.calls)
            val first=feedback.begin()
            feedback.finish(first,FeedbackKind.CONFIRM)
            feedback.finish(first,FeedbackKind.CONFIRM)
            assertEquals(1,view.calls)
            view.isHapticFeedbackEnabled=false
            feedback.finish(feedback.begin(),FeedbackKind.REJECT)
            assertEquals(1,view.calls)
            view.isHapticFeedbackEnabled=true
            val stale=feedback.begin();owner="account-b"
            feedback.finish(stale,FeedbackKind.CONFIRM)
            assertEquals(1,view.calls)
            feedback.finish(feedback.begin(),FeedbackKind.SELECTION)
            assertEquals(2,view.calls)
        }
    }
}
