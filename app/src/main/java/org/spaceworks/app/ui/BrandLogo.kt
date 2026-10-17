package org.spaceworks.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.spaceworks.app.R

/** Exact supplied masters; never tinted, faded, transformed or placed inside animated content. */
@Composable fun BrandLogo(compact:Boolean=false,width:Dp=180.dp,onWhite:Boolean=false,decorative:Boolean=false) {
    val dark=MaterialTheme.colorScheme.background.luminance()<.4f
    val asset=if(compact)when {onWhite->R.drawable.brand_symbol_one_colour_ink;dark->R.drawable.brand_symbol_reversed;else->R.drawable.brand_symbol}
        else when {onWhite->R.drawable.brand_stacked_one_colour_ink;dark->R.drawable.brand_stacked_reversed;else->R.drawable.brand_stacked}
    Image(painterResource(asset),if(decorative)null else stringResource(R.string.app_name),
        if(compact)Modifier.padding(12.dp).height(48.dp).aspectRatio(136f/126f)
        else Modifier.padding(16.dp).width(width).aspectRatio(360f/128f))
}
