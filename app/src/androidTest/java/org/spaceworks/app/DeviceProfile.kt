package org.spaceworks.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.test.platform.app.InstrumentationRegistry

/** Instrumented-test configuration only; production appearance preferences remain unchanged. */
object DeviceProfile {
    val name: String by lazy {
        val value = InstrumentationRegistry.getArguments().getString("deviceProfile") ?: "phone"
        require(value in setOf("compact", "small-phone", "common-phone", "phone", "large", "small-tablet", "tablet", "wide-tablet", "tablet-landscape", "large-tablet"))
        value
    }
    val dark: Boolean get() = name in setOf("large", "tablet-landscape")
    val fontScale: Float get() = when (name) { "large" -> 1.3f; "tablet-landscape", "large-tablet" -> 2f; else -> 1f }
    val landscape: Boolean get() = name == "tablet-landscape"
    val portraitWidthDp: Float get() = when(name) {
        "compact" -> 320f; "small-phone" -> 360f; "common-phone" -> 393f
        "phone" -> 1080f / (420f / 160f); "large" -> 480f; "small-tablet" -> 600f
        "tablet" -> 800f; "wide-tablet" -> 840f; "large-tablet" -> 1024f
        else -> error("Landscape profile has a rotated width")
    }
    @Composable fun appearance(initialDark: Boolean = dark): AppearanceStateHolder =
        remember(name, initialDark) { AppearanceStateHolder(initialDark) {} }
}
