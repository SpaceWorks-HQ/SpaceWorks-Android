package org.spaceworks.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.spaceworks.app.account.*
import org.spaceworks.app.auth.*
import org.spaceworks.app.network.*
import org.spaceworks.app.theme.SpaceWorksTheme
import org.spaceworks.app.ui.AuthScreen
import org.spaceworks.generated.model.*

/** Production auth/deletion UI against in-test gateways shaped like the generated native contract. */
private class JourneyAuth(var minimum:String?=null):AuthGateway {
    var codes=mutableListOf<String>()
    override val nativeSocialReady=true
    override suspend fun config()=PublicConfig(false,100,native=NativeConfig(NativeMinVersion(minimum,null),NativeProviders(true,true,null,null,emptyList())))
    override suspend fun login(username:String,password:String):DeviceLoginResult=throw ApiFailure(401,"Wrong password.",null,null)
    override suspend fun enroll(challenge:String)=throw AssertionError()
    override suspend fun verify(challenge:String,code:String,enrollment:Boolean)=throw AssertionError()
    override suspend fun me()=AuthUserPayload("member",1,"phone","","Phone Member","+919876543210",true,"member",false,false,emptyList())
    override suspend fun logout() {}
    override suspend fun phoneStart(phone:String)=PhoneChallenge("journey-challenge",300)
    override suspend fun phoneConfirm(phone:String,code:String,challenge:PhoneChallenge):PhoneLoginResult {
        codes+=code
        if(code!="123456")throw ApiFailure(400,"Invalid or expired code.",null,null)
        val at=java.time.OffsetDateTime.parse("2026-10-17T00:00:00Z")
        return PhoneLoginResult.DeviceTokenResponseValue(DeviceTokenResponse("a","r",emptyMap(),
            DeviceGrant(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),"android","org.spaceworks.app","development","active",at,at,at)))
    }
}
private class JourneyDeletion:AccountDeletionGateway {
    var blocked=true;var deleted=0
    override suspend fun preview()=if(blocked)DeletionPreview(false,true,"You can restore it within 90 days.",
        listOf(DeletionBlocker("unpaid_charges",7,"fab-lab",null,2,"Two charges need payment."),DeletionBlocker("future_code",null,null,null,1,"A server-only review is open.")),true,listOf("password"))
        else DeletionPreview(true,true,"You can restore it within 90 days.",emptyList(),true,listOf("password"))
    override suspend fun password(password:String,code:String?)=if(password=="correct")DeletionProof.Verified(600) else throw ApiFailure(0,"x","reauth_failed",null)
    override suspend fun startPhone()=throw AssertionError()
    override suspend fun confirmPhone(token:PhoneProofToken,code:String)=throw AssertionError()
    override suspend fun startSocial(provider:String)=throw AssertionError()
    override suspend fun completeSocial(intent:SocialProofIntent,idToken:String)=throw AssertionError()
    override suspend fun factor(token:String,code:String)=throw AssertionError()
    override suspend fun delete():DeletionOutcome {deleted++;return DeletionOutcome.Deleted("2026-10-18T00:00:00Z","2027-01-16T00:00:00Z")}
}

class NativeContractJourneyTest {
    @get:Rule val compose=createComposeRule()
    private val journey by lazy {NativeJourney(compose,"c-native")}
    private fun show(content:@androidx.compose.runtime.Composable ()->Unit) {
        DeviceWindowGuard.awaitClear()
        compose.setContent {SpaceWorksTheme(DeviceProfile.dark) {Surface(Modifier.fillMaxSize()) {Box(Modifier.fillMaxSize().safeDrawingPadding()) {content()}}}}
    }
    @Test fun phoneSignInSendsCodeRejectsWrongCodeAndSignsIn() {
        val api=JourneyAuth();val store=MemoryTokenStore();val holder=AuthStateHolder(api,store)
        show {AuthScreen(AuthBundle(holder,store))}
        journey.waitText("Continue with phone");journey.shot("c3-phone-entry")
        journey.clickTag("phoneSignIn");journey.waitText("We'll text a six-digit code to this number.")
        journey.replace(compose.onNodeWithTag("phoneNumber"),"9876543210");journey.clickTag("sendPhoneCode")
        journey.waitText("Enter your verification code");journey.shot("c3-phone-code")
        journey.replace(compose.onNodeWithTag("phoneCode"),"000000");journey.clickTag("verifyPhoneCode")
        journey.waitText("Invalid or expired code.");journey.shot("c3-phone-invalid")
        journey.replace(compose.onNodeWithTag("phoneCode"),"123456");journey.clickTag("verifyPhoneCode")
        compose.waitUntil(10000) {holder.state.value.user!=null}
        assertEquals(listOf("000000","123456"),api.codes);assertEquals(TokenPair("a","r"),store.read())
    }
    @Test fun belowMinimumVersionShowsUpdateRequired() {
        val api=JourneyAuth(minimum="999.0");val store=MemoryTokenStore();val holder=AuthStateHolder(api,store,appVersion=BuildConfig.VERSION_NAME)
        show {AuthScreen(AuthBundle(holder,store))}
        journey.waitText("Update required")
        journey.visible(compose.onNodeWithText("Open Google Play"));journey.shot("c3-update-required")
        compose.onNodeWithText("Email or username").assertDoesNotExist()
    }
    @Test fun deletionShowsBlockersThenReauthenticatesAndRequiresTypedConfirmation() {
        val api=JourneyDeletion();val holder=AccountDeletionStateHolder(api)
        show {AccountDeletionScreen(holder,{},{_,_->})}
        journey.waitText("Resolve these before deleting")
        journey.visible(compose.onNodeWithTag("deletion-blocker-unpaid_charges"));journey.waitText("A server-only review is open.")
        journey.shot("c4-deletion-blockers")
        api.blocked=false;journey.clickTag("deletion-recheck")
        journey.waitText("Verify it is you")
        journey.replace(compose.onNodeWithTag("deletion-password"),"wrong");journey.clickTag("deletion-authenticate")
        journey.waitText("Verification failed. Check your password or code.");journey.shot("c4-deletion-proof-failed")
        journey.replace(compose.onNodeWithTag("deletion-password"),"correct");journey.clickTag("deletion-authenticate")
        journey.waitText("Confirm account deletion")
        journey.replace(compose.onNodeWithTag("deletion-confirmation"),"delete")
        compose.onNodeWithTag("deletion-submit").assertIsNotEnabled()
        journey.replace(compose.onNodeWithTag("deletion-confirmation"),"DELETE");journey.shot("c4-deletion-confirm")
        journey.clickTag("deletion-submit")
        journey.waitText("Your account was deleted and this device is signed out.");assertEquals(1,api.deleted)
    }
}
