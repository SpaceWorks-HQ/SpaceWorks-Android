package org.spaceworks.app
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import org.spaceworks.app.theme.BrandTokens
import androidx.compose.ui.graphics.toArgb
class BrandTest {
    @Test fun themeMatchesPinnedSource() {
        assertEquals(0xFFFFF4E8.toInt(),BrandTokens.Light.bg.toArgb())
        assertEquals(0xFF142C2A.toInt(),BrandTokens.Dark.bg.toArgb())
        assertEquals(0xFF006E63.toInt(),BrandTokens.Light.accent.toArgb())
    }
    @Test fun applicationIdReadsBrandConfiguration() { val brand=Json.parseToJsonElement(File("../branding/brand.json").readText()).jsonObject; assertEquals(brand.getValue("android_application_id").jsonPrimitive.content,BuildConfig.APPLICATION_ID) }
}
