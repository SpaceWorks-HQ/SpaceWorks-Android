package org.spaceworks.app.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

/** A push is a refetch hint, never permission to display or mutate a makerspace's data. */
data class PushHint(val makerspaceId: Int, val feature: String?, val event: String?, val eventId: String?,
    val inboxRowId: Int?, val link: String?, val version: Int) {
    companion object {
        fun from(data: Map<String, String>): PushHint? {
            val makerspace = data["makerspace_id"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
            return PushHint(makerspace, data["feature"], data["event"], data["event_id"],
                data["inbox_row_id"]?.toIntOrNull()?.takeIf { it > 0 }, safeInAppPath(data["link"]), data["v"]?.toIntOrNull() ?: 1)
        }

        /** Returns paths only. The app router must additionally allowlist routes and active memberships. */
        fun safeInAppPath(value: String?): String? {
            if (value == null || value.length > 2048 || !value.startsWith("/") || value.startsWith("//") ||
                value.any { it == '\\' || it.isISOControl() }) return null
            return try {
                val uri = URI(value)
                val path = uri.path ?: return null
                value.takeIf { uri.scheme == null && uri.rawAuthority == null && uri.fragment == null &&
                    !path.startsWith("//") && path.none { it == '\\' || it == '%' || it.isISOControl() } &&
                    path.split('/').none { it == "." || it == ".." } }
            } catch (_: Exception) { null }
        }
    }
}

object NativePushEvents {
    private val mutableToken = MutableStateFlow<String?>(null)
    val token = mutableToken.asStateFlow()
    private val mutableHints = MutableSharedFlow<PushHint>(replay = 1, extraBufferCapacity = 15, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val hints = mutableHints.asSharedFlow()
    internal fun updateToken(token: String) { if (token.isNotBlank()) mutableToken.value = token }
    internal fun receive(hint: PushHint) { mutableHints.tryEmit(hint) }
    internal fun clearToken() { mutableToken.value = null }
}

object FcmRegistration {
    /** Call after explicit push setup/sign-in, then register using the generated API once per membership. */
    suspend fun token(context: Context): String {
        val messaging = configured(context)
        try {
            messaging.isAutoInitEnabled = true
            return messaging.token.awaitPlatformTask().also(NativePushEvents::updateToken)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw PlatformUnavailable("Notifications are unavailable. Try again later.") }
    }

    /** The generated gateway must first remove the per-makerspace server registration IDs. */
    suspend fun stop(context: Context) {
        val messaging = configured(context)
        messaging.isAutoInitEnabled = false
        try { messaging.deleteToken().awaitPlatformTask() }
        finally { NativePushEvents.clearToken() }
    }

    private fun configured(context: Context): FirebaseMessaging {
        if (FirebaseApp.getApps(context).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
            throw PlatformUnavailable("Notifications are not configured for this build.")
        }
        return FirebaseMessaging.getInstance()
    }
}

object NotificationAccess {
    fun needsRuntimePermission(sdk: Int, permissionGranted: Boolean): Boolean = sdk >= 33 && !permissionGranted
    fun allowed(context: Context): Boolean =
        !needsRuntimePermission(Build.VERSION.SDK_INT, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
}

/** Invoked by a visible user action; denial never prevents using the app. */
@Composable
fun rememberNotificationPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val result = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result.value(it && NotificationAccess.allowed(context)) }
    return {
        if (NotificationAccess.needsRuntimePermission(Build.VERSION.SDK_INT,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else result.value(NotificationAccess.allowed(context))
    }
}

/** Declare exported=false with com.google.firebase.MESSAGING_EVENT in the app manifest. */
class SpaceWorksMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { NativePushEvents.updateToken(token) }

    override fun onMessageReceived(message: RemoteMessage) {
        val hint = PushHint.from(message.data) ?: return
        NativePushEvents.receive(hint)
        if (!NotificationAccess.allowed(this)) return
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        putHint(launch, hint)
        launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val requestId = (hint.eventId ?: message.messageId ?: message.data.toSortedMap().toString()).hashCode()
        val pending = PendingIntent.getActivity(this, requestId, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Makerspace updates", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = android.app.Notification.Builder(this, CHANNEL)
            .setSmallIcon(org.spaceworks.app.R.drawable.ic_brand)
            .setContentTitle(message.notification?.title ?: applicationInfo.loadLabel(packageManager))
            .setContentText(message.notification?.body ?: "Open your inbox for the latest update.")
            .setStyle(android.app.Notification.BigTextStyle().bigText(message.notification?.body ?: "Open your inbox for the latest update."))
            .setContentIntent(pending).setAutoCancel(true).setGroup("makerspace-${hint.makerspaceId}").build()
        try { notifications.notify(requestId, notification) } catch (_: SecurityException) { /* Permission changed while receiving. */ }
    }

    companion object {
        private const val CHANNEL = "spaceworks_updates"
        private const val PREFIX = "org.spaceworks.push."
        private fun putHint(intent: Intent, hint: PushHint) {
            intent.putExtra(PREFIX + "makerspace_id", hint.makerspaceId)
            intent.putExtra(PREFIX + "feature", hint.feature).putExtra(PREFIX + "event", hint.event)
                .putExtra(PREFIX + "event_id", hint.eventId).putExtra(PREFIX + "link", hint.link)
                .putExtra(PREFIX + "inbox_row_id", hint.inboxRowId ?: -1).putExtra(PREFIX + "v", hint.version)
        }
        /** Consume in both Activity.onCreate and onNewIntent. The router refetches after scope validation. */
        fun consume(intent: Intent): PushHint? {
            val data = if (intent.hasExtra(PREFIX + "makerspace_id")) mapOf(
                "makerspace_id" to intent.getIntExtra(PREFIX + "makerspace_id", -1).toString(),
                "feature" to intent.getStringExtra(PREFIX + "feature").orEmpty(),
                "event" to intent.getStringExtra(PREFIX + "event").orEmpty(),
                "event_id" to intent.getStringExtra(PREFIX + "event_id").orEmpty(),
                "link" to intent.getStringExtra(PREFIX + "link").orEmpty(),
                "inbox_row_id" to intent.getIntExtra(PREFIX + "inbox_row_id", -1).toString(),
                "v" to intent.getIntExtra(PREFIX + "v", 1).toString(),
            ) else listOf("makerspace_id", "feature", "event", "event_id", "link", "inbox_row_id", "v")
                .mapNotNull { key -> intent.getStringExtra(key)?.let { key to it } }.toMap()
            val hint = PushHint.from(data)
            data.keys.forEach { intent.removeExtra(it); intent.removeExtra(PREFIX + it) }
            return hint
        }
    }
}
