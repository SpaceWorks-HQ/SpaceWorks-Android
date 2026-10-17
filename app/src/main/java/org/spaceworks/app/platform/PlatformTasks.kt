package org.spaceworks.app.platform

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No extra coroutine/Play Services dependency. Cancelling a screen never delivers a stale callback. */
internal suspend fun <T> Task<T>.awaitPlatformTask(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener(Executor { it.run() }) { task ->
        if (continuation.isActive) {
            when {
                task.isCanceled -> continuation.cancel()
                task.isSuccessful -> continuation.resume(task.result)
                else -> continuation.resumeWithException(task.exception ?: PlatformUnavailable("The device service is unavailable. Try again."))
            }
        }
    }
}

class PlatformUnavailable(message: String) : IllegalStateException(message)
