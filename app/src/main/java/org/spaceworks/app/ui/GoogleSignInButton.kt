package org.spaceworks.app.ui

import org.spaceworks.app.platform.*

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.spaceworks.app.R

@Composable fun GoogleSignInButton(enabled:Boolean,onClick:()->Unit) {
    val copyText=rememberMobileText()

    OutlinedButton(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),
        shape=RoundedCornerShape(10.dp),border=BorderStroke(1.dp,Color(0xFF747775)),
        colors=ButtonDefaults.outlinedButtonColors(containerColor=Color.White,contentColor=Color(0xFF1F1F1F))) {
        Image(painterResource(R.drawable.google_g),null,Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp));Text(copyText(UiStrings.MOBILE_CONTINUE_WITH_GOOGLE))
    }
}
