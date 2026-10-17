package org.spaceworks.app.guidance

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.spaceworks.app.platform.MobileCopyKey
import org.spaceworks.app.platform.rememberMobileText

@Composable fun rememberScopedGuidance(context: Context, scope: GuideScope, demo: Boolean = false, previewStore: GuideStore? = null): GuideProgress {
    val application = context.applicationContext
    // Store lifetime deliberately excludes maker scope, preserving demo flags when switching makers.
    // The enclosing bundle's composition owns this helper and disposes demo flags on demo exit.
    val store = remember(application, demo, previewStore) {
        if (demo) previewStore ?: MemoryGuideStore() else {
            val preferences = application.getSharedPreferences("usage-guidance", Context.MODE_PRIVATE)
            object : GuideStore {
                override fun get(key: String) = preferences.getBoolean(key, false)
                override fun set(key: String, value: Boolean) { preferences.edit().putBoolean(key, value).apply() }
            }
        }
    }
    return remember(scope, store) { GuideProgress(scope, store) }
}

/** One optional educational callout. It never hides errors, consent requirements or normal navigation. */
@Composable fun HomeStartHere(progress: GuideProgress, onInventory: () -> Unit, onBookings: (() -> Unit)?, onProfile: (() -> Unit)? = null) {
    val dismissed by progress.dismissed.collectAsState()
    if (dismissed) return
    val copy = rememberMobileText()
    Card(Modifier.fillMaxWidth().testTag("usage-start-here"), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(copy(UsageStrings.USAGE_START_HERE), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            Text(copy(UsageStrings.USAGE_START_BODY))
            GuideButton(copy(UsageStrings.USAGE_BROWSE_INVENTORY), "usage-browse-inventory", onInventory)
            if (onBookings != null) GuideButton(copy(UsageStrings.USAGE_VIEW_BOOKINGS), "usage-view-bookings", onBookings)
            else if (onProfile != null) GuideButton(copy(UsageStrings.USAGE_VIEW_PROFILE), "usage-view-profile", onProfile)
            TextButton(onClick = progress::dismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("usage-dismiss-tip"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                Text(copy(UsageStrings.USAGE_DISMISS_TIP))
            }
        }
    }
}

/** Readable instructions only. Actual app navigation, capabilities, permissions and links remain owner callbacks. */
@Composable fun UsageGuideScreen(
    demo: Boolean,
    staffAvailable: Boolean,
    onBack: () -> Unit,
    onReplayIntro: () -> Unit,
    onResetTips: (() -> Unit)? = null,
    footer: @Composable () -> Unit = {},
) {
    val copy = rememberMobileText()
    var resetNotice by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 960.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(24.dp).testTag("usage-guide-scroll"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).testTag("usage-guide-back"),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                    Text(copy(UsageStrings.USAGE_BACK))
                }
                Text(copy(UsageStrings.USAGE_APP_GUIDE), style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.testTag("usage-guide-heading").semantics { heading() })
                GuideButton(copy(UsageStrings.USAGE_REPLAY_INTRODUCTION), "usage-replay-intro", onReplayIntro)
                if (onResetTips != null) {
                    GuideButton(copy(UsageStrings.USAGE_RESET_HELPFUL_TIPS), "usage-reset-tips") {
                        onResetTips(); resetNotice = true
                    }
                    if (resetNotice) Text(copy(UsageStrings.USAGE_TIPS_RESET), modifier = Modifier.testTag("usage-reset-notice")
                        .semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (demo) Text(copy(UsageStrings.USAGE_DEMO), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(copy(UsageStrings.USAGE_INTRO))
                for (section in memberSections) key(section.id) { GuideSection(section) }
                if (staffAvailable) {
                    GuideSection(GuideTopic("staff-requests", UsageStrings.USAGE_STAFF_REQUESTS_TITLE, UsageStrings.USAGE_STAFF_REQUESTS_BODY))
                    GuideSection(GuideTopic("staff-scan", UsageStrings.USAGE_STAFF_SCAN_TITLE, UsageStrings.USAGE_STAFF_SCAN_BODY))
                }
                GuideSection(GuideTopic("permissions", UsageStrings.USAGE_PERMISSIONS_TITLE, UsageStrings.USAGE_PERMISSIONS_BODY))
                GuideSection(GuideTopic("availability", UsageStrings.USAGE_AVAILABILITY_TITLE, UsageStrings.USAGE_AVAILABILITY_BODY))
                footer()
            }
        }
    }
}

@Composable private fun GuideButton(title: String, tag: String, action: () -> Unit) {
    Button(onClick = action, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) { Text(title) }
}

private data class GuideTopic(val id: String, val title: MobileCopyKey, val body: MobileCopyKey)

@Composable private fun GuideSection(topic: GuideTopic) {
    val copy = rememberMobileText()
    var expanded by rememberSaveable(topic.id) { mutableStateOf(false) }
    val isExpanded = expanded
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { expanded = !isExpanded }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("usage-section-${topic.id}").semantics {
                    heading()
                    if (isExpanded) collapse { expanded = false; true } else expand { expanded = true; true }
                }) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(copy(topic.title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth())
                    Icon(if (isExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, contentDescription = null)
                }
            }
            if (isExpanded) for ((index, paragraph) in copy(topic.body).split("\n\n").withIndex()) {
                Text(paragraph, modifier = Modifier.fillMaxWidth().testTag("usage-section-${topic.id}-body-$index"))
            }
        }
    }
}

private val memberSections = listOf(
    GuideTopic("home", UsageStrings.USAGE_HOME_TITLE, UsageStrings.USAGE_HOME_BODY),
    GuideTopic("inventory", UsageStrings.USAGE_INVENTORY_TITLE, UsageStrings.USAGE_INVENTORY_BODY),
    GuideTopic("bookings", UsageStrings.USAGE_BOOKINGS_TITLE, UsageStrings.USAGE_BOOKINGS_BODY),
    GuideTopic("events", UsageStrings.USAGE_EVENTS_TITLE, UsageStrings.USAGE_EVENTS_BODY),
    GuideTopic("inbox", UsageStrings.USAGE_INBOX_TITLE, UsageStrings.USAGE_INBOX_BODY),
    GuideTopic("payments", UsageStrings.USAGE_PAYMENTS_TITLE, UsageStrings.USAGE_PAYMENTS_BODY),
    GuideTopic("account", UsageStrings.USAGE_ACCOUNT_TITLE, UsageStrings.USAGE_ACCOUNT_BODY),
)
