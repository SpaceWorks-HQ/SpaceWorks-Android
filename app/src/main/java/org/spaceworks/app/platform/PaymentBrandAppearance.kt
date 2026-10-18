package org.spaceworks.app.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import com.stripe.android.paymentsheet.PaymentSheet
import org.spaceworks.app.R
import org.spaceworks.app.theme.BrandTokens
import org.spaceworks.app.theme.DarkScheme
import org.spaceworks.app.theme.LightScheme
import java.util.Locale

/** SDK appearance only. Stripe retains native layout, type sizes, weights and system appearance. */
internal fun stripeBrandAppearance():PaymentSheet.Appearance {
    fun colors(scheme:ColorScheme)=PaymentSheet.Colors(
        // SDK primary also colors links/checkmarks. Keep these accessible on dark surfaces;
        // the independently configured primary button below retains the exact teal fill.
        primary=scheme.onPrimaryContainer.toArgb(),surface=scheme.background.toArgb(),
        component=scheme.surfaceContainer.toArgb(),componentBorder=scheme.outline.toArgb(),
        componentDivider=scheme.outlineVariant.toArgb(),onComponent=scheme.onSurface.toArgb(),
        onSurface=scheme.onBackground.toArgb(),subtitle=scheme.onSurfaceVariant.toArgb(),
        placeholderText=scheme.onSurfaceVariant.toArgb(),appBarIcon=scheme.onSurface.toArgb(),
        error=scheme.error.toArgb()
    )
    return PaymentSheet.Appearance(
        colorsLight=colors(LightScheme),colorsDark=colors(DarkScheme),
        shapes=PaymentSheet.Shapes(cornerRadiusDp=10f,borderStrokeWidthDp=1f),
        typography=PaymentSheet.Typography(sizeScaleFactor=1f,fontResId=R.font.gilroy),
        primaryButton=PaymentSheet.PrimaryButton(
            colorsLight=PaymentSheet.PrimaryButtonColors(
                background=LightScheme.primary.toArgb(),onBackground=LightScheme.onPrimary.toArgb(),
                border=LightScheme.primary.toArgb(),successBackgroundColor=BrandTokens.Light.success.toArgb(),
                onSuccessBackgroundColor=BrandTokens.Light.onSuccess.toArgb()),
            colorsDark=PaymentSheet.PrimaryButtonColors(
                background=DarkScheme.primary.toArgb(),onBackground=DarkScheme.onPrimary.toArgb(),
                border=DarkScheme.primary.toArgb(),successBackgroundColor=BrandTokens.Dark.success.toArgb(),
                onSuccessBackgroundColor=BrandTokens.Dark.onSuccess.toArgb()),
            shape=PaymentSheet.PrimaryButtonShape(cornerRadiusDp=10f,borderStrokeWidthDp=1f),
            typography=PaymentSheet.PrimaryButtonTypography(fontResId=R.font.gilroy)
        )
    )
}

/** Android Standard Checkout documents theme.color, but no supported custom-font API. */
internal fun razorpayBrandColor():String = String.format(Locale.ROOT,"#%06X",LightScheme.primary.toArgb() and 0xffffff)
