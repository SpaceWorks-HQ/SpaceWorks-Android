package org.spaceworks.app

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.core.content.res.ResourcesCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.theme.BrandFontFamily

/** Real Android resource + Compose registration proof, using the supplied fonts' distinct M advances. */
class BrandTypographyTest {
    @Test fun suppliedWeightsResolveToTheirActualNativeGlyphMetricsAtMultipleScales() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val resolver=createFontFamilyResolver(context)
        val faces=listOf(
            Triple(R.font.gilroy_regular,FontWeight.Normal,.800f),
            Triple(R.font.gilroy_medium,FontWeight.Medium,.805f),
            Triple(R.font.gilroy_semibold,FontWeight.SemiBold,.810f),
            Triple(R.font.gilroy_bold,FontWeight.Bold,.815f)
        )
        faces.forEach {(resource,weight,advance) ->
            val native=requireNotNull(ResourcesCompat.getFont(context,resource))
            val resolved=resolver.resolve(BrandFontFamily,weight,fontSynthesis=FontSynthesis.None).value as Typeface
            listOf(16f,32f,51.2f).forEach {size ->
                val paint=Paint().apply {textSize=size;typeface=native;isSubpixelText=true;isLinearText=true}
                assertEquals("Supplied face $weight",advance*size,paint.measureText("M"),.03f)
                paint.typeface=resolved
                assertEquals("Compose must select supplied weight $weight",advance*size,paint.measureText("M"),.03f)
            }
        }
        if(android.os.Build.VERSION.SDK_INT>=31) {
            val configuration=android.content.res.Configuration(context.resources.configuration).apply {fontWeightAdjustment=300}
            val boldContext=context.createConfigurationContext(configuration)
            val boldResolver=createFontFamilyResolver(boldContext)
            val bold=boldResolver.resolve(BrandFontFamily,FontWeight.Normal,fontSynthesis=FontSynthesis.None).value as Typeface
            val paint=Paint().apply {textSize=100f;typeface=bold;isSubpixelText=true;isLinearText=true}
            assertEquals("System bold-text adjustment selects the supplied Bold face",81.5f,paint.measureText("M"),.03f)
        }
    }
}
