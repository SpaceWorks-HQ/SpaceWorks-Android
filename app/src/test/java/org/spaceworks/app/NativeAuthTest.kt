package org.spaceworks.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.auth.*
import org.spaceworks.app.network.*
import org.spaceworks.generated.model.*

private const val NATIVE_USER="""{"session_authority":"member","id":1,"username":"member","email":"member@example.invalid","display_name":"Member","phone":"","email_verified":true,"role":"member","is_superuser":false,"must_change_password":false,"makerspaces":[{"id":1,"slug":"fab-lab","role":"member","role_id":1,"role_ids":[1],"role_slugs":["member"],"role_names":["Member"],"role_name":"Member","role_slug":"member","source":"membership","actions":["issue_hardware"],"can_configure_machine_types":false,"is_machine_only":false,"can_refer":false,"can_verify":false,"verified_at":null,"referrals_enabled":false}]}"""
private const val GRANT="""{"id":"00000000-0000-0000-0000-000000000001","platform":"android","app_id":"org.example","environment":"development","status":"active","attested_at":"2026-10-17T00:00:00Z","last_used_at":"2026-10-17T00:00:00Z","created_at":"2026-10-17T00:00:00Z"}"""

private fun nativeConfig(password:Boolean=true,phone:Boolean=true,google:String?="android-audience",minimum:String?=null)=PublicConfig(false,100,
    native=NativeConfig(NativeMinVersion(minimum,null),NativeProviders(password,phone,google?.let {NativeGoogle(it,"ios-audience")},null,emptyList())))

private fun grant():DeviceGrant {val at=java.time.OffsetDateTime.parse("2026-10-17T00:00:00Z")
    return DeviceGrant(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),"android","org.example","development","active",at,at,at)}
private class NativeFakeAuth:AuthGateway {
    var config=nativeConfig()
    var who=Json.decodeFromString<AuthUserPayload>(NATIVE_USER)
    var phoneResult:PhoneLoginResult=PhoneLoginResult.DeviceTokenResponseValue(DeviceTokenResponse("phone-access","phone-refresh",emptyMap(),grant()))
    var started=0;var confirmed=0;var loggedOutAll=0;var meCalls=0
    var lastChallenge:PhoneChallenge?=null
    var logoutAllFailure:Exception?=null
    override val nativeSocialReady=true
    override suspend fun config()=config
    override suspend fun login(username:String,password:String):DeviceLoginResult=throw AssertionError("unused")
    override suspend fun enroll(challenge:String)=throw AssertionError("unused")
    override suspend fun verify(challenge:String,code:String,enrollment:Boolean)=throw AssertionError("unused")
    override suspend fun me():AuthUserPayload {meCalls++;return who}
    override suspend fun logout() {}
    override suspend fun phoneStart(phone:String):PhoneChallenge {started++;return PhoneChallenge("phone-challenge-$started",120)}
    override suspend fun phoneConfirm(phone:String,code:String,challenge:PhoneChallenge):PhoneLoginResult {confirmed++;lastChallenge=challenge;return phoneResult}
    override suspend fun logoutAll(expectedEpoch:Long):LogoutAllResponse {logoutAllFailure?.let {throw it};loggedOutAll++;return LogoutAllResponse(1,2)}
}

class NativeAuthTest {
    @Test fun versionComparisonUsesNumericComponents() {
        assertTrue(AppVersion.below("1.9.0","1.10"));assertTrue(AppVersion.below("0.1.0","0.1.1"))
        assertFalse(AppVersion.below("1.10.0","1.9.9"));assertFalse(AppVersion.below("2.0","2"));assertFalse(AppVersion.below("2.0.0","1.99"))
        assertFalse(AppVersion.below("1.0.0",null));assertFalse(AppVersion.below("1.0.0","beta"));assertFalse(AppVersion.below(null,"9.0"))
    }
    @Test fun belowMinimumVersionBlocksBeforeRestoringIdentity()=runTest {
        val api=NativeFakeAuth().apply {config=nativeConfig(minimum="1.4.0")}
        val store=MemoryTokenStore().apply {replace(TokenPair("access","refresh"))}
        val holder=AuthStateHolder(api,store,appVersion="1.3.9")
        holder.initialize()
        assertEquals(AuthGate.UPDATE_REQUIRED,holder.state.value.gate);assertEquals(0,api.meCalls)
        assertNotNull(store.read());holder.dismissGate();assertEquals(AuthGate.UPDATE_REQUIRED,holder.state.value.gate)
        api.config=nativeConfig(minimum="1.3.9");holder.initialize()
        assertNull(holder.state.value.gate);assertEquals(1,holder.state.value.user!!.id)
    }
    @Test fun nativeProvidersDriveVisibleMethods()=runTest {
        val api=NativeFakeAuth().apply {config=nativeConfig(password=false,phone=false,google=" ")}
        val holder=AuthStateHolder(api,MemoryTokenStore());holder.initialize()
        assertFalse(holder.state.value.passwordEnabled);assertFalse(holder.state.value.phoneEnabled);assertNull(holder.state.value.googleClientId)
        api.config=nativeConfig();holder.initialize()
        assertTrue(holder.state.value.passwordEnabled);assertTrue(holder.state.value.phoneEnabled);assertEquals("android-audience",holder.state.value.googleClientId)
    }
    @Test fun staffToolsRequireNativeStaffSessionAuthority()=runTest {
        val api=NativeFakeAuth();val store=MemoryTokenStore().apply {replace(TokenPair("access","refresh"))}
        val holder=AuthStateHolder(api,store);holder.initialize()
        assertFalse(holder.state.value.staffAllowed)
        api.who=api.who.copy(sessionAuthority="native_staff");holder.foreground()
        assertTrue(holder.state.value.staffAllowed)
        api.who=api.who.copy(sessionAuthority="staff");holder.foreground()
        assertFalse(holder.state.value.staffAllowed)
    }
    @Test fun phoneSignInReusesTheStartedChallengeAndInstallsDeviceTokens()=runTest {
        var clock=1_000L
        val api=NativeFakeAuth();val store=MemoryTokenStore()
        val holder=AuthStateHolder(api,store,now={clock});holder.initialize()
        holder.openPhone();holder.sendPhoneCode("12345")
        assertEquals(0,api.started);assertNotNull(holder.state.value.error)
        holder.sendPhoneCode("+91 98765 43210")
        assertEquals("+919876543210",holder.state.value.phone!!.phone);assertTrue(holder.state.value.phone!!.codeSent)
        holder.verifyPhoneCode("12a")
        assertEquals(0,api.confirmed)
        clock+=5_000;holder.verifyPhoneCode("123456")
        assertEquals("phone-challenge-1",api.lastChallenge!!.challenge)
        assertEquals(TokenPair("phone-access","phone-refresh"),store.read());assertNull(holder.state.value.phone);assertEquals(1,holder.state.value.user!!.id)
    }
    @Test fun expiredPhoneCodeAsksForAFreshCodeWithoutCallingTheServer()=runTest {
        var clock=1_000L
        val api=NativeFakeAuth();val holder=AuthStateHolder(api,MemoryTokenStore(),now={clock});holder.initialize()
        holder.openPhone();holder.sendPhoneCode("+447700900123")
        clock+=121_000;holder.verifyPhoneCode("123456")
        assertEquals(0,api.confirmed);assertFalse(holder.state.value.phone!!.codeSent);assertEquals("Your code expired. Send a new code.",holder.state.value.error)
    }
    @Test fun phoneSignInCanRequireMfaAndRejectsBrowserOnlyCompletion()=runTest {
        val api=NativeFakeAuth().apply {phoneResult=PhoneLoginResult.MFAChallengeResponseValue(MFAChallengeResponse(true,false,"mfa",300))}
        val holder=AuthStateHolder(api,MemoryTokenStore());holder.initialize()
        holder.openPhone();holder.sendPhoneCode("+919876543210");holder.verifyPhoneCode("123456")
        assertEquals("mfa",holder.state.value.mfa!!.challenge);assertNull(holder.state.value.phone)
        val second=NativeFakeAuth().apply {phoneResult=PhoneLoginResult.MFACompletedValue(MFACompleted(who,"web-access","member"))}
        val store=MemoryTokenStore();val other=AuthStateHolder(second,store);other.initialize()
        other.openPhone();other.sendPhoneCode("+919876543210");other.verifyPhoneCode("123456")
        assertNull(store.read());assertNull(other.state.value.user);assertNotNull(other.state.value.error)
    }
    @Test fun signOutEverywhereClearsOnlyAfterServerConfirmation()=runTest {
        val api=NativeFakeAuth();val store=MemoryTokenStore().apply {replace(TokenPair("access","refresh"))}
        val holder=AuthStateHolder(api,store);holder.initialize()
        api.logoutAllFailure=ApiFailure(503,"Unavailable",null,null);holder.signOutEverywhere()
        assertNotNull(store.read());assertNotNull(holder.state.value.user)
        api.logoutAllFailure=null;holder.signOutEverywhere()
        assertNull(store.read());assertNull(holder.state.value.user);assertEquals(1,api.loggedOutAll)
        assertEquals("Signed out on every device and browser.",holder.state.value.notice)
    }

    @Test fun phoneStartAndConfirmSendDeviceDeliveryAndPlayIntegrityProof()=runBlocking {
        MockWebServer().use {server->
            var hashed:String?=null
            val attestation=NativeAttestation(DevelopmentAttestation(false,false),{challenge->hashed=org.spaceworks.app.platform.PlayIntegrityProofProvider.requestHash(challenge);"integrity-token"})
            val gateway=GeneratedAuthGateway(ApiSession(server.url("/")),"org.example",EnvironmentEnum.production,attestation)
            assertTrue(gateway.nativeSocialReady)
            server.enqueue(MockResponse().setBody("""{"challenge":"device-challenge","expires_in":90}"""))
            server.enqueue(MockResponse().setBody("""{"detail":"If the number can sign in, a code was sent."}"""))
            val challenge=gateway.phoneStart("+919876543210")
            assertEquals(90,challenge.expiresInSeconds);assertEquals("PhoneChallenge(redacted)",challenge.toString())
            val identity=Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("android",identity["platform"]!!.jsonPrimitive.content);assertEquals("production",identity["environment"]!!.jsonPrimitive.content)
            val start=server.takeRequest();assertEquals("/api/v1/auth/phone/login/start",start.path)
            val startBody=Json.parseToJsonElement(start.body.readUtf8()).jsonObject
            assertEquals("device",startBody["delivery"]!!.jsonPrimitive.content);assertEquals("android",startBody["client_platform"]!!.jsonPrimitive.content)
            assertEquals("device-challenge",startBody["challenge"]!!.jsonPrimitive.content);assertNull(start.getHeader("Authorization"))
            server.enqueue(MockResponse().setBody("""{"access":"a","refresh":"r","user":{},"device_grant":$GRANT}"""))
            assertTrue(gateway.phoneConfirm("+919876543210","123456",challenge) is PhoneLoginResult.DeviceTokenResponseValue)
            val confirm=server.takeRequest();assertEquals("/api/v1/auth/phone/login/confirm",confirm.path)
            val body=Json.parseToJsonElement(confirm.body.readUtf8()).jsonObject
            assertEquals("123456",body["code"]!!.jsonPrimitive.content);assertEquals("device-challenge",body["challenge"]!!.jsonPrimitive.content)
            assertEquals(buildJsonObject {put("type","play_integrity");put("token","integrity-token")},body["attestation"])
            assertEquals(org.spaceworks.app.platform.PlayIntegrityProofProvider.requestHash("device-challenge"),hashed)
        }
    }
    @Test fun phoneConfirmDecodesMfaChallenge()=runBlocking {
        MockWebServer().use {server->
            val gateway=GeneratedAuthGateway(ApiSession(server.url("/")),"org.example",EnvironmentEnum.development,DevelopmentAttestation(true,true))
            server.enqueue(MockResponse().setBody("""{"mfa_required":true,"enrollment_required":false,"challenge":"second","expires_in":300}"""))
            val result=gateway.phoneConfirm("+919876543210","123456",PhoneChallenge("c",60))
            assertEquals("second",(result as PhoneLoginResult.MFAChallengeResponseValue).value.challenge)
            assertEquals("development",Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["attestation"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
    }
    @Test fun unconfiguredProductionAttestationIsUnavailableAndFailuresAreReadable()=runBlocking {
        val none=NativeAttestation(DevelopmentAttestation(false,false),null)
        assertFalse(none.available)
        try {none.attest("c");fail()}catch(f:AuthMessage){assertEquals("Device verification is unavailable for this build. Contact your makerspace.",f.message)}
        val failing=NativeAttestation(DevelopmentAttestation(false,false),{throw IllegalStateException("provider")})
        try {failing.attest("c");fail()}catch(f:AuthMessage){assertEquals("This device could not verify the app. Try again.",f.message)}
        // A Debug emulator always uses the development proof, never Play Integrity.
        val debug=NativeAttestation(DevelopmentAttestation(true,true),{throw AssertionError("integrity used")})
        assertTrue(debug.attest("c") is DeviceAttestationPayload.JsonElementValue)
    }
    @Test fun logoutAllCleansPushThenRevokesEverySessionWithoutMakerspaceHeader()=runBlocking {
        MockWebServer().use {server->
            val tokens=MemoryTokenStore().apply {replace(TokenPair("access","refresh"))}
            val session=ApiSession(server.url("/"),tokens) {"7"}
            var pushCleanups=0;var nativeClears=0
            val gateway=GeneratedAuthGateway(session,"org.example",EnvironmentEnum.development,DevelopmentAttestation(true,true),
                onNativeSignOut={nativeClears++},onBeforeOwnedSignOut={pushCleanups++})
            server.enqueue(MockResponse().setBody("""{"web_sessions":2,"devices":3}"""))
            val result=gateway.logoutAll(tokens.snapshot().epoch)
            assertEquals(3,result.devices);assertEquals(1,pushCleanups);assertEquals(1,nativeClears)
            val request=server.takeRequest();assertEquals("/api/v1/auth/logout-all",request.path);assertEquals("POST",request.method)
            assertEquals("Bearer access",request.getHeader("Authorization"));assertNull(request.getHeader("X-Makerspace-Id"))
        }
    }
}
