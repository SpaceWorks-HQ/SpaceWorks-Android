package org.spaceworks.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import org.spaceworks.app.R

@Composable fun BrandTextButton(text:String,onClick:()->Unit,enabled:Boolean=true,modifier:Modifier=Modifier) {
    TextButton(onClick=onClick,enabled=enabled,modifier=modifier.heightIn(min=48.dp),
        colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.onPrimaryContainer)) {Text(text)}
}
@Composable fun BrandButton(text:String,onClick:()->Unit,enabled:Boolean=true,modifier:Modifier=Modifier) {
    Button(onClick=onClick,enabled=enabled,shape=RoundedCornerShape(10.dp),modifier=modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(text)}
}
@Composable fun BrandError(message:String?) {message?.let {
    Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.fillMaxWidth().semantics {liveRegion=LiveRegionMode.Polite})
}}
@Composable fun BrandField(value:String,onValueChange:(String)->Unit,label:String,modifier:Modifier=Modifier,
    enabled:Boolean=true,readOnly:Boolean=false,secret:Boolean=false) {
    var visible by remember {mutableStateOf(false)}
    OutlinedTextField(value=value,onValueChange=onValueChange,label={Text(label)},singleLine=true,enabled=enabled,readOnly=readOnly,
        visualTransformation=if(secret && !visible) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=if(secret) androidx.compose.ui.text.input.KeyboardType.Password else androidx.compose.ui.text.input.KeyboardType.Text),
        trailingIcon=if(secret) {{BrandTextButton(if(visible)"Hide" else "Show",{visible=!visible})}} else null,
        shape=RoundedCornerShape(10.dp),modifier=modifier.fillMaxWidth(),
        colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=MaterialTheme.colorScheme.onPrimaryContainer,
            focusedLabelColor=MaterialTheme.colorScheme.onPrimaryContainer,cursorColor=MaterialTheme.colorScheme.onPrimaryContainer))
}
@Composable fun AuthLayout(title:String,subtitle:String,content:@Composable ColumnScope.()->Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow=maxWidth<360.dp
        val expanded=maxWidth>=840.dp && LocalDensity.current.fontScale<1.6f
        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.Center) {
            if(expanded) Surface(Modifier.weight(1f).fillMaxHeight().padding(start=24.dp,bottom=24.dp),
                color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxSize().padding(40.dp),verticalArrangement=Arrangement.Center) {
                    Image(painterResource(R.drawable.ic_brand),null,Modifier.size(96.dp))
                    Spacer(Modifier.height(32.dp))
                    Text("Less searching.\nMore making.",style=MaterialTheme.typography.displayMedium,color=MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(24.dp))
                    Text("Tools, time and a place to make things happen.",style=MaterialTheme.typography.titleLarge)
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight(),contentAlignment=Alignment.TopCenter) {
                Column(Modifier.widthIn(max=520.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .imePadding().padding(if(narrow)16.dp else 24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                    Image(painterResource(R.drawable.ic_brand),null,Modifier.size(64.dp))
                    Text(title,style=MaterialTheme.typography.headlineLarge)
                    if(subtitle.isNotBlank())Text(subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyLarge)
                    content()
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
