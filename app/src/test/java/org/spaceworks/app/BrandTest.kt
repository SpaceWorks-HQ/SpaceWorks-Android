package org.spaceworks.app
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import org.spaceworks.app.theme.BrandTokens
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.spaceworks.app.theme.LightScheme
import org.spaceworks.app.theme.DarkScheme
import org.spaceworks.app.theme.BrandTypography
import org.spaceworks.app.theme.BrandFontFamily
import androidx.compose.material3.Typography
class BrandTest {
    @Test fun allMaterialRolesUseGilroyWithoutChangingNativeTypeScale() {
        fun roles(t:Typography)=listOf(t.displayLarge,t.displayMedium,t.displaySmall,
            t.headlineLarge,t.headlineMedium,t.headlineSmall,t.titleLarge,t.titleMedium,t.titleSmall,
            t.bodyLarge,t.bodyMedium,t.bodySmall,t.labelLarge,t.labelMedium,t.labelSmall)
        roles(BrandTypography).zip(roles(Typography())).forEach {(brand,native) ->
            assertEquals(BrandFontFamily,brand.fontFamily)
            assertEquals(native.fontSize,brand.fontSize)
            assertEquals(native.lineHeight,brand.lineHeight)
            assertEquals(native.letterSpacing,brand.letterSpacing)
        }
    }
    @Test fun themeMatchesPinnedSource() {
        assertEquals(0xFFFFF4E8.toInt(),BrandTokens.Light.bg.toArgb())
        assertEquals(0xFF142C2A.toInt(),BrandTokens.Dark.bg.toArgb())
        assertEquals(0xFF006E63.toInt(),BrandTokens.Light.accent.toArgb())
    }
    @Test fun interactiveTextAndLabelsHaveAccessibleContrast() {
        fun contrast(a:Color,b:Color):Double { val x=a.luminance().toDouble();val y=b.luminance().toDouble();return (maxOf(x,y)+0.05)/(minOf(x,y)+0.05) }
        listOf(LightScheme,DarkScheme).forEach {scheme ->
            assertTrue(contrast(scheme.onPrimary,scheme.primary)>=4.5)
            listOf(scheme.background,scheme.surface).forEach {surface ->
                assertTrue(contrast(scheme.onPrimaryContainer,surface)>=4.5)
                assertTrue(contrast(scheme.onSurface,surface)>=4.5)
            }
        }
    }
    @Test fun applicationIdReadsBrandConfiguration() { val brand=Json.parseToJsonElement(File("../branding/brand.json").readText()).jsonObject; assertEquals(brand.getValue("android_application_id").jsonPrimitive.content,BuildConfig.APPLICATION_ID) }
}
