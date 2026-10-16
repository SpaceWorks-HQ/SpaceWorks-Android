package org.spaceworks.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.*
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
enum class FieldRole { TEXT, NAME, IDENTITY, EMAIL, PHONE, PASSWORD, NEW_PASSWORD, SERVER, CODE, ONE_TIME_CODE }
val BrandAccentFont=FontFamily(Font(R.font.unbounded,FontWeight.Medium))
@Composable fun BrandField(value:String,onValueChange:(String)->Unit,label:String,modifier:Modifier=Modifier,
    enabled:Boolean=true,readOnly:Boolean=false,secret:Boolean=false,
    role:FieldRole=if(secret)FieldRole.PASSWORD else FieldRole.TEXT,onDone:(()->Unit)?=null,errorMessage:String?=null) {
    var visible by remember {mutableStateOf(false)}
    val fieldFocus=remember {FocusRequester()}
    val focus=LocalFocusManager.current
    val keyboard=when(role){FieldRole.PHONE->KeyboardType.Phone;FieldRole.EMAIL,FieldRole.IDENTITY->KeyboardType.Email;FieldRole.SERVER->KeyboardType.Uri;FieldRole.CODE,FieldRole.ONE_TIME_CODE->KeyboardType.Number;FieldRole.PASSWORD,FieldRole.NEW_PASSWORD->KeyboardType.Password;else->KeyboardType.Text}
    OutlinedTextField(value=value,onValueChange=onValueChange,label={Text(label)},singleLine=true,enabled=enabled,readOnly=readOnly,
        isError=errorMessage!=null,supportingText=errorMessage?.let {{Text(it,Modifier.semantics {liveRegion=LiveRegionMode.Polite})}},
        visualTransformation=if(secret && !visible) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=keyboard,autoCorrectEnabled=false,capitalization=if(role==FieldRole.NAME)KeyboardCapitalization.Words else KeyboardCapitalization.None,imeAction=if(onDone==null)ImeAction.Next else ImeAction.Done),
        keyboardActions=KeyboardActions(onNext={focus.moveFocus(FocusDirection.Next)},onDone={onDone?.invoke()}),
        trailingIcon=if(secret) {{BrandTextButton(if(visible)"Hide" else "Show",{visible=!visible;fieldFocus.requestFocus()},modifier=Modifier.semantics {contentDescription=if(visible)"Hide password" else "Show password";stateDescription=if(visible)"Password visible" else "Password hidden"})}} else null,
        shape=RoundedCornerShape(10.dp),modifier=modifier.fillMaxWidth().focusRequester(fieldFocus).semantics {
            when(role){FieldRole.PHONE->contentType=ContentType.PhoneNumber;FieldRole.NAME->contentType=ContentType.PersonFullName;FieldRole.ONE_TIME_CODE->contentType=ContentType.SmsOtpCode;FieldRole.IDENTITY->contentType=ContentType.Username+ContentType.EmailAddress;FieldRole.EMAIL->contentType=ContentType.EmailAddress;FieldRole.PASSWORD->contentType=ContentType.Password;FieldRole.NEW_PASSWORD->contentType=ContentType.NewPassword;else->Unit}
        },
        colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=MaterialTheme.colorScheme.onPrimaryContainer,
            focusedLabelColor=MaterialTheme.colorScheme.onPrimaryContainer,cursorColor=MaterialTheme.colorScheme.onPrimaryContainer))
}
@Composable fun AuthLayout(title:String,subtitle:String,content:@Composable ColumnScope.()->Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
        val narrow=maxWidth<360.dp
        val compact=LocalDensity.current.fontScale>=1.6f || maxHeight<600.dp || WindowInsets.ime.getBottom(LocalDensity.current)>0
        val expanded=maxWidth>=960.dp && maxHeight>=640.dp && !compact
        Row(Modifier.widthIn(max=1040.dp).fillMaxSize(),horizontalArrangement=Arrangement.Center) {
            if(expanded) Surface(Modifier.weight(1f).fillMaxHeight().padding(start=24.dp,bottom=24.dp),
                color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxSize().padding(40.dp),verticalArrangement=Arrangement.Center) {
                    BrandLogo(width=280.dp)
                    Spacer(Modifier.height(32.dp))
                    Text("Less searching.\nMore making.",style=MaterialTheme.typography.displaySmall,fontFamily=BrandAccentFont,color=MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(24.dp))
                    Text("Tools, time and a place to make things happen.",style=MaterialTheme.typography.titleLarge)
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight(),contentAlignment=Alignment.TopCenter) {
                Column(Modifier.widthIn(max=460.dp).fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                    .padding(if(narrow)16.dp else 24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                    if(!expanded)BrandLogo(compact=compact)
                    Text(title,style=MaterialTheme.typography.headlineLarge)
                    if(subtitle.isNotBlank())Text(subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyLarge)
                    OutlinedCard(colors=CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer),
                        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),shape=RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp),content=content)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
