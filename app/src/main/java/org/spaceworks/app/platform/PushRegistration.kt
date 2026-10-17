package org.spaceworks.app.platform

import org.spaceworks.app.R

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
        fun from(data: Map<String, *>): PushHint? {
            val makerspace = decimalInteger(data["makerspace_id"])?.takeIf { it > 0 } ?: return null
            // Only an absent version is legacy. Malformed/future versions cannot activate v2 routing.
            val version = if (data.containsKey("v")) decimalInteger(data["v"]) ?: 0 else 1
            val knownVersion = version == 1 || version == 2
            return PushHint(makerspace,
                if (knownVersion) boundedText(data["feature"]) else null,
                if (knownVersion) boundedText(data["event"]) else null,
                if (version == 2) boundedText(data["event_id"]) else null,
                if (version == 2) decimalInteger(data["inbox_row_id"])?.takeIf { it > 0 } else null,
                if (version == 2) safeInAppPath(data["link"] as? String) else null,
                version)
        }

        private fun decimalInteger(value: Any?): Int? = when (value) {
            is String -> value.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toIntOrNull()
            is Byte -> value.toInt()
            is Short -> value.toInt()
            is Int -> value
            is Long -> value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
            // Boolean IDs, floating point values and arbitrary objects are never identifiers.
            else -> null
        }

        private fun boundedText(value: Any?): String? = (value as? String)?.takeIf {
            it.isNotBlank() && it.length <= 256 && it.none(::isControl)
        }

        private fun isControl(char: Char): Boolean = char.isISOControl() || Character.getType(char) == Character.FORMAT.toInt()

        /** Returns paths only. The app router must additionally allowlist routes and active memberships. */
        fun safeInAppPath(value: String?): String? {
            if (value == null || value.length > 2048 || value.toByteArray(Charsets.UTF_8).size > 2048 ||
                !value.startsWith("/") || value.startsWith("//") ||
                value.any { it == '\\' || it == '%' || isControl(it) }) return null
            return try {
                val uri = URI(value)
                val path = uri.path ?: return null
                value.takeIf { uri.scheme == null && uri.rawAuthority == null && uri.query == null && uri.fragment == null &&
                    !path.startsWith("//") && path.none { it == '\\' || it == '%' || isControl(it) } &&
                    path.split('/').none { it == "." || it == ".." } }
            } catch (_: Exception) { null }
        }
    }
}

/** Background delivery uses only a private consent/membership hint; content stays generic.
 * A notification tap must freshly authenticate and authorize the route before showing data. */
object NativePushEvents {
    internal val ownership = PushOwnerLeaseCoordinator()
    fun claim(owner:PushOwnerIdentity,enabled:Boolean=true):PushOwnerLease =
        ownership.claim(owner,enabled).also { lease ->
            if(!enabled)ownership.publish(lease,requireEnabled=false){updateDelivery(false,emptySet())}
        }
    fun cleanupLease(owner:PushOwnerIdentity):PushOwnerLease? = ownership.cleanupLease(owner)
    fun configure(context:Context,lease:PushOwnerLease,active:Boolean,makers:Set<Int>):Boolean =
        ownership.publish(lease,requireEnabled=false) {
            if(active)ownership.enable(lease)else ownership.disable(lease)
            synchronized(this) { persistDelivery(context,active,makers) }
        }
    internal fun updateToken(lease:PushOwnerLease,value:String,isCurrent:()->Boolean) {
        ownership.publish(lease) { if(isCurrent())updateToken(value) }
    }
    internal fun clearToken(lease:PushOwnerLease) {
        ownership.publish(lease,requireEnabled=false) { clearToken() }
    }
    private val mutableToken = MutableStateFlow<String?>(null)
    val token = mutableToken.asStateFlow()
    private val mutableHints = MutableSharedFlow<PushHint>(replay = 0, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val hints = mutableHints.asSharedFlow()
    @Volatile private var loaded=false
    @Volatile private var members:Set<Int> = emptySet()
    @Volatile private var enabled=false
    internal fun updateDelivery(active:Boolean,makers:Set<Int>) {
        loaded=true;enabled=active;members=if(active)makers.filter {it>0}.toSet()else emptySet()
        if(!active)mutableToken.value=null
    }
    private fun persistDelivery(context:Context,active:Boolean,makers:Set<Int>) {
        updateDelivery(active,makers)
        val prefs=context.getSharedPreferences("native-push-delivery",Context.MODE_PRIVATE)
        check(prefs.edit().putBoolean("active",active).putStringSet("makers",members.map(Int::toString).toSet()).commit())
        if(!active)context.getSystemService(NotificationManager::class.java).cancelAll()
    }
    @Synchronized private fun load(context:Context) {
        if(loaded)return
        val prefs=context.getSharedPreferences("native-push-delivery",Context.MODE_PRIVATE)
        val ids=prefs.getStringSet("makers",emptySet()).orEmpty().mapNotNull {it.toIntOrNull()?.takeIf {id->id>0}}.toSet()
        updateDelivery(prefs.getBoolean("active",false),ids)
    }
    internal fun tokenAllowed(context:Context):Boolean {load(context);return enabled}
    internal fun allowed(context:Context,makerId:Int):Boolean {load(context);return enabled&&makerId in members}
    internal fun updateToken(token:String) {if(enabled && token.isNotBlank())mutableToken.value=token}
    internal fun receive(hint:PushHint) {if(enabled && hint.makerspaceId in members)mutableHints.tryEmit(hint)}
    internal fun clearToken(){mutableToken.value=null}
}

object FcmRegistration {
    fun configured(context:Context):Boolean=FirebaseApp.getApps(context).any {it.name==FirebaseApp.DEFAULT_APP_NAME}

    /** One shared SDK queue prevents an old deletion finishing after a new owner's token request. */
    suspend fun token(context:Context,lease:PushOwnerLease,isCurrent:()->Boolean):String {
        try {
            return NativePushEvents.ownership.serialized(lease) {
                if(!isCurrent())throw CancellationException("Push owner changed")
                if(!configured(context))throw PlatformUnavailable(context.getString(R.string.mobile_notifications_are_not_configured_for_this_build))
                val messaging=FirebaseMessaging.getInstance()
                var started=false
                NativePushEvents.ownership.publish(lease) {
                    if(isCurrent()){ messaging.isAutoInitEnabled=true; started=true }
                }
                if(!started)throw CancellationException("Push owner changed")
                messaging.token.awaitPlatformTask().also { value ->
                    NativePushEvents.updateToken(lease,value,isCurrent)
                }
            }?.takeIf { isCurrent() } ?: throw CancellationException("Push owner changed")
        }catch(cancelled:CancellationException){throw cancelled}
        catch(unavailable:PlatformUnavailable){throw unavailable}
        catch(_:Exception){throw PlatformUnavailable(context.getString(R.string.mobile_notifications_are_unavailable_try_again_later))}
    }

    /** Explicit old-owner cleanup remains valid after its private UI has already been cleared. */
    suspend fun stop(context:Context,lease:PushOwnerLease,isCurrent:()->Boolean) {
        NativePushEvents.ownership.serialized(lease,requireEnabled=false) {
            if(!isCurrent())return@serialized
            var shouldDelete=false
            NativePushEvents.ownership.publish(lease,requireEnabled=false) {
                if(isCurrent()) {
                    NativePushEvents.configure(context,lease,false,emptySet())
                    if(configured(context)) {
                        FirebaseMessaging.getInstance().isAutoInitEnabled=false
                        shouldDelete=true
                    }
                }
            }
            try {
                if(shouldDelete && isCurrent())FirebaseMessaging.getInstance().deleteToken().awaitPlatformTask()
            } finally { NativePushEvents.clearToken(lease) }
        }
    }
}

object NotificationAccess {
    private const val CHANNEL="spaceworks_updates"
    fun createChannel(context:Context) {context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,context.getString(R.string.mobile_makerspace_updates),NotificationManager.IMPORTANCE_DEFAULT))}
    fun channel():String=CHANNEL
    fun needsRuntimePermission(sdk: Int, permissionGranted: Boolean): Boolean = sdk >= 33 && !permissionGranted
    fun allowed(context: Context): Boolean =
        !needsRuntimePermission(Build.VERSION.SDK_INT, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).let {it.areNotificationsEnabled() && (it.getNotificationChannel(CHANNEL)?.importance ?: NotificationManager.IMPORTANCE_DEFAULT)!=NotificationManager.IMPORTANCE_NONE}
}

/** Invoked by a visible user action; denial never prevents using the app. */
@Composable
fun rememberNotificationPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val result = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result.value(it && NotificationAccess.allowed(context)) }
    return {
        NotificationAccess.createChannel(context)
        if (NotificationAccess.needsRuntimePermission(Build.VERSION.SDK_INT,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else result.value(NotificationAccess.allowed(context))
    }
}

/** Declare exported=false with com.google.firebase.MESSAGING_EVENT in the app manifest. */
class SpaceWorksMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {if(NativePushEvents.tokenAllowed(this))NativePushEvents.updateToken(token)}

    override fun onMessageReceived(message: RemoteMessage) {
        val hint = PushHint.from(message.data) ?: return
        if(!NativePushEvents.allowed(this,hint.makerspaceId))return
        NativePushEvents.receive(hint)
        if (!NotificationAccess.allowed(this)) return
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        putHint(launch, hint)
        launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val requestId = (hint.eventId ?: message.messageId ?: message.data.toSortedMap().toString()).hashCode()
        val pending = PendingIntent.getActivity(this, requestId, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(NotificationAccess.channel(), getString(R.string.mobile_makerspace_updates), NotificationManager.IMPORTANCE_DEFAULT))
        val notification = android.app.Notification.Builder(this, NotificationAccess.channel())
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(applicationInfo.loadLabel(packageManager))
            .setContentText(getString(R.string.mobile_open_your_inbox_for_the_latest_update))
            .setStyle(android.app.Notification.BigTextStyle().bigText(getString(R.string.mobile_open_your_inbox_for_the_latest_update)))
            .setContentIntent(pending).setAutoCancel(true).setGroup("makerspace-${hint.makerspaceId}").build()
        try { notifications.notify(requestId, notification) } catch (_: SecurityException) { /* Permission changed while receiving. */ }
    }

    companion object {
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
