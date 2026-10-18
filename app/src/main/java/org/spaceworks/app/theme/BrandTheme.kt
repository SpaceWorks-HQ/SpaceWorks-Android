package org.spaceworks.app.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.spaceworks.app.R
import org.spaceworks.app.platform.LocalReducedMotion
import org.spaceworks.app.platform.rememberReducedMotion

// The supplied faces carry legacy OS/2 weight=400 metadata. Declare each actual face's
// role explicitly, without rewriting the owner's name, outlines or metrics tables.
val BrandFontFamily = FontFamily(
    Font(R.font.gilroy_regular, FontWeight.Normal),
    Font(R.font.gilroy_medium, FontWeight.Medium),
    Font(R.font.gilroy_semibold, FontWeight.SemiBold),
    Font(R.font.gilroy_bold, FontWeight.Bold)
)

/** Preserve Material's native role sizes, line heights and scalable sp units. */
val BrandTypography = Typography().run {
    copy(
        displayLarge=displayLarge.copy(fontFamily=BrandFontFamily,fontWeight=FontWeight.Bold),
        displayMedium=displayMedium.copy(fontFamily=BrandFontFamily,fontWeight=FontWeight.SemiBold),
        displaySmall=displaySmall.copy(fontFamily=BrandFontFamily,fontWeight=FontWeight.SemiBold),
        headlineLarge=headlineLarge.copy(fontFamily=BrandFontFamily,fontWeight=FontWeight.SemiBold),
        headlineMedium=headlineMedium.copy(fontFamily=BrandFontFamily,fontWeight=FontWeight.SemiBold),
        headlineSmall=headlineSmall.copy(fontFamily=BrandFontFamily,fontWeight=FontWeight.SemiBold),
        titleLarge=titleLarge.copy(fontFamily=BrandFontFamily,fontWeight=FontWeight.SemiBold),
        titleMedium=titleMedium.copy(fontFamily=BrandFontFamily,fontWeight=FontWeight.SemiBold),
        titleSmall=titleSmall.copy(fontFamily=BrandFontFamily,fontWeight=FontWeight.SemiBold),
        bodyLarge=bodyLarge.copy(fontFamily=BrandFontFamily),
        bodyMedium=bodyMedium.copy(fontFamily=BrandFontFamily),
        bodySmall=bodySmall.copy(fontFamily=BrandFontFamily),
        labelLarge=labelLarge.copy(fontFamily=BrandFontFamily),
        labelMedium=labelMedium.copy(fontFamily=BrandFontFamily),
        labelSmall=labelSmall.copy(fontFamily=BrandFontFamily)
    )
}

@Composable fun SpaceWorksTheme(dark:Boolean,content:@Composable ()->Unit) {
    val reduced=rememberReducedMotion()
    CompositionLocalProvider(LocalReducedMotion provides reduced) {
    MaterialTheme(colorScheme=if(dark)DarkScheme else LightScheme,typography=BrandTypography,
        shapes=Shapes(small=RoundedCornerShape(10.dp),medium=RoundedCornerShape(16.dp)),content=content)
    }
}
