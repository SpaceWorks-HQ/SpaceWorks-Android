package org.spaceworks.app.auth

import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.spaceworks.app.network.*
import org.spaceworks.generated.model.*

object DemoSupport {
    fun create():AuthBundle {
        val tokens=MemoryTokenStore()
        val gateway=DemoAuthGateway()
        val holder=AuthStateHolder(gateway,tokens)
        return AuthBundle(holder,tokens,true,AccountStateHolder(gateway,{true},{holder.foreground()}),DemoAuthFlow(holder))
    }
}
class DemoAuthGateway:AuthGateway {
    private var username="meera.demo"
    private var verified=false
    private var passwordChanged=false
    private val registrations=mutableMapOf<String,String>()
    override suspend fun config()=PublicConfig(true,10485760,passwordLogin=mapOf("enabled" to JsonPrimitive(true)))
    override suspend fun login(username:String,password:String):DeviceLoginResult {
        delay(350);this.username=username
        when {
            username.contains("offline") -> throw java.io.IOException("Preview network failure")
            username.contains("throttle") -> throw ApiFailure(429,"Too many requests. Try again later.","throttled",5)
            password=="incorrect" -> throw ApiFailure(401,"Check your email or username and password.","invalid_credentials",null)
            username.contains("mfa") || username.contains("enroll") || username.contains("expired") -> return DeviceLoginResult.MFAChallengeResponseValue(MFAChallengeResponse(true,username.contains("enroll"),"demo-first-factor-proof-only",if(username.contains("expired"))0 else 300))
        }
        return DeviceLoginResult.DeviceTokenResponseValue(DeviceTokenResponse("demo-access","demo-refresh",emptyMap(),grant()))
    }
    override suspend fun enroll(challenge:String)=MFAEnrollment("JBSWY3DPEHPK3PXP","otpauth://totp/Space%20Works:demo?secret=JBSWY3DPEHPK3PXP&issuer=Space%20Works","data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAcIAAAHCAQAAAABUY/ToAAAENElEQVR4nO1cbW6jMBAdD0j5CVIO0KOQm1V7pL0BHKUHWAl+VnI0q/GMCbRCbiBtipknFQj4qSE8zZfHOIJ16HAlEcCYKWByxBKMmQImRyzBmClgcsQSjJkCJkcswZgpYHLEEoyZ4y/kFCVAV1+dbGAoQTYdX4Ahjro8+duuAa5iMYyZQim7puXtcAbX9CBhtoPKA3SXgo96PlN4vRBgv20KeDDmEO0LmyD3+nYiaHo2RozmrYRwVUzV87/t/cAVHIExUyg/nRlKoK4ueNMTBBPUvXggGObj7LdNAQ/KpLby4F77K5ulKwdFzlE7Wqlv+Z9fB94xdg5jfr8dqjgICpYmRNKspu5yItfwed4EtzYtSNpTSQEPwixl10nkU3BMffaO42c+Ao6zvWv+njmcjuHR874tGvPXaohuJzT2Yb1U7yyaq4sf59Mi9jxTwIMwgRjQhF1fzGTCuX3TF0Qt3DbKoHZf94nG/GYNUVuFnYcglSApaPgj9WGI/8ywp5ICHoQJoohgghrWi5gbNkEQ1aTiCjLjYmM4ZxrKTAm4MR5y/EfdC7HzOvPHE8Ug+lpC06qTcxwUWZ06SyXg1ngoQO2QHk0dGqkvU3tldihDJeDWvKy7FB7YDoW5MSdHQ83X1ASpziRXe9a3RWP+5ngIJAcLhkdSsjEeAv6oWVvI1cwOfQl4vNwegt+65fEjQK5Klm95WaZKwM2+zLG3Gs5j6Mx2aajDOR+uAs/DhgYQJ40i+7pPNOZPxNQ0z94lsIZqlvlruchy+68ADxYPUcy8gpB4E6MgiLladGgWD2WpBNxuhwoWTZSP5vZe5zrYDoW6o1QbzQ7lqAR8RF5G7cShRUlRrF3LaHFo5ssyVAJuZlYxo+e+Vy0DDY5n67UlTf0bR9zWk5+zElaAxKqIpSk+WiRQJ+encZPVh3JUAq5mwqQONLqxiXyasfjIrk39m/myDJWA23s//HSWLFyQILrX3o+YnJkdylQJuLlO3WthWszNmI3BmJzNHJrVh/JTAm7XEMT5slhK1PCIYqVI5j+sfyhXJeB2ZqETqgznXnT6zIUFQiwu99rzhVonzXZ7n3cD76cceY3iIM0eHrqL4wmynjs+CpJVH13N3WfVP0d7vE805o/kZQxtRJMWDz/G2RpJa7TEA60XNgk8YB8jxLYPuSAuq9JW6hgt2XxZpkrAh60NIu2FHRd39NNI2upD+SoBH5aXURCSVIVuNmccPdYAzJflpgR8HLOrb2nau77N6rZo6E9t82X7ep5PYVac0YfXV7nTuPaVc3vieVjryd/b83zOez+8rirr65jHy6L70ClbkK0vy1IJ+LC8DGReQ5Z0+EkjmvUxZq4EfNh7PwiA3/shR0Xs/Zic06O93ScaMwFMDViEMVPA5IglGDMFTI5YgjFTwOSIJRgzBUyOWIIxU1j/C/0HRAdYqSwb7u4AAAAASUVORK5CYII=")
    override suspend fun verify(challenge:String,code:String,enrollment:Boolean):MFACompleted {
        delay(250)
        if(code!="123456" && code!="DEMO-001-ABCD") throw ApiFailure(400,"Check the code and try again.","invalid_code",null)
        return MFACompleted(me(),"demo-access","device",refresh="demo-refresh",deviceGrant=mapOf("id" to JsonPrimitive("00000000-0000-0000-0000-000000000001")),recoveryCodes=if(enrollment) listOf("DEMO-001-ABCD","DEMO-002-EFGH","DEMO-003-IJKL") else null)
    }
    override suspend fun me():AuthUserPayload=AuthUserPayload(
        id=1,username=username,email="meera@example.invalid",displayName=registrations[username] ?: if(username.contains("staff")) "Arjun Rao" else "Meera Nair",
        phone="",emailVerified=!username.contains("unverified") || verified,role="member",isSuperuser=username.contains("superadmin"),mustChangePassword=username.contains("temporary") && !passwordChanged,
        makerspaces=if(username.contains("empty") || registrations.containsKey(username))emptyList() else if(username.contains("single"))listOf(membership(1,"fab-lab-kochi")) else listOf(membership(1,"fab-lab-kochi"),membership(2,"maker-space-bengaluru")))
    override suspend fun forgotPassword(email:String):String {if(email.contains("unavailable"))throw ApiFailure(503,"Recovery unavailable","recovery_unavailable",null);return "If the details are valid, a verification email has been sent."}
    override suspend fun resetPassword(email:String,code:String,replacement:String):String {if(code!="123456")throw ApiFailure(400,"This account does not exist.",null,null);return "Password updated."}
    override suspend fun signUp(name:String,email:String,password:String,phone:String):String {registrations[email]=name;return "Account created"}
    override suspend fun confirmEmail(code:String):String {if(code!="123456")throw ApiFailure(400,"Invalid verification code.",null,null);verified=true;return "Your email is verified."}
    override suspend fun resendEmail()="If the details are valid, a verification email has been sent."
    override suspend fun logout() {delay(100)}
    override suspend fun changePassword(current:String,replacement:String) {delay(200);passwordChanged=true}
    private fun membership(id:Int,slug:String)=AuthMembership(id,slug,"member",1,"Member","member",AuthMembershipSourceEnum.membership,emptyList(),false,false,false,false,null,false)
    private fun grant()=DeviceGrant(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),"android","org.spaceworks.app","development","active",java.time.OffsetDateTime.parse("2026-10-16T00:00:00Z"),java.time.OffsetDateTime.parse("2026-10-16T00:00:00Z"),java.time.OffsetDateTime.parse("2026-10-16T00:00:00Z"))
}

