package org.spaceworks.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject
import org.spaceworks.app.theme.DarkScheme
import org.spaceworks.app.theme.LightScheme
import java.io.File
import kotlin.math.abs

/** Verify actual root appearance/window/font, not only adb's requested configuration. */
class DeviceProfileJourneyTest {
    @get:Rule val compose = createComposeRule()
    @Test fun declaredProfileMatchesVisibleAppThemeOrientationAndTextScale() {
        var observedFont = 0f
        var observedDensity = 0f
        compose.setContent {
            val density = LocalDensity.current
            SideEffect { observedFont = density.fontScale; observedDensity = density.density }
            SpaceWorksApp(ConnectionStateHolder("", false, true, {}, {}), appearance = DeviceProfile.appearance())
        }
        DeviceWindowGuard.awaitClear()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val observedWidthDp = bitmap.width / observedDensity
        val expectedWidthDp = if (DeviceProfile.landscape) 1280f else DeviceProfile.portraitWidthDp
        val expected = (if (DeviceProfile.dark) DarkScheme else LightScheme).background.toArgb()
        val observed = bitmap.getPixel(2, 2)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir("validation"))
        directory.mkdirs()
        val evidence = JSONObject().put("profile", DeviceProfile.name).put("app_dark", DeviceProfile.dark)
            .put("window_width_pixels", bitmap.width).put("window_height_pixels", bitmap.height)
            .put("observed_font_scale", observedFont).put("observed_root_argb", Integer.toHexString(observed))
            .put("observed_width_dp", observedWidthDp).put("observed_density", observedDensity)
            .put("expected_width_dp", expectedWidthDp).put("expected_landscape", DeviceProfile.landscape)
            .put("observed_landscape", bitmap.width > bitmap.height)
        File(directory, "a8-observed-app-profile.json").writeText(evidence.toString(2))
        File(directory, "a8-observed-app-profile.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        DeviceWindowGuard.awaitClear()
        val full = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "a8-observed-app-profile-screen.png").outputStream().use { full.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { full.recycle() }
        // Keep observed geometry/images even when the promised profile fails validation.
        compose.onNodeWithText(if (DeviceProfile.dark) "Light mode" else "Dark mode").assertIsDisplayed()
        compose.runOnIdle { assertEquals(DeviceProfile.fontScale, observedFont, 0.01f) }
        assertEquals("The app must actually receive the promised logical width", expectedWidthDp, observedWidthDp, 1f)
        assertEquals("Requested orientation must be visible in the actual app window", DeviceProfile.landscape, bitmap.width > bitmap.height)
        assertTrue("App root background must use its declared theme", Color.alpha(observed) >= 250 &&
            abs(Color.red(expected) - Color.red(observed)) <= 3 &&
            abs(Color.green(expected) - Color.green(observed)) <= 3 &&
            abs(Color.blue(expected) - Color.blue(observed)) <= 3)
    }
}
