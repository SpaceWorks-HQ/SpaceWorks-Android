package org.spaceworks.app.actions

import org.spaceworks.app.platform.MobileText
import org.spaceworks.app.platform.MobileCopyException
import org.spaceworks.app.platform.failure
import org.spaceworks.app.copy.CoreStrings
import org.spaceworks.app.copy.apiFailure

import org.spaceworks.app.auth.requireBody
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.ApiFailure
import org.spaceworks.app.network.ApiSession
import org.spaceworks.generated.api.TenantBootstrapApi

/** Local capabilities projected from published bootstrap; never inferred from role names. */
data class MakerCapabilities(val selfService:Boolean,val memberPreferences:Boolean,val dailyDigest:Boolean,val bookableSpaces:Boolean=false,val messaging:Boolean=false) {
    companion object {val NONE=MakerCapabilities(false,false,false);val DEMO=MakerCapabilities(true,true,true,true,true)}
}
fun interface MakerCapabilitiesGateway {suspend fun load(scope:MemberScope):MakerCapabilities}
class GeneratedMakerCapabilitiesGateway(private val session:ApiSession,private val text:MobileText=MobileText.ENGLISH):MakerCapabilitiesGateway {
    override suspend fun load(scope:MemberScope):MakerCapabilities {
        val value=session.scopedApi(TenantBootstrapApi::class.java,scope.id).apiV1BootstrapRetrieve(slug=scope.slug).requireBody()
        if(value.makerspace.id!=scope.id)throw ApiFailure(403,text(CoreStrings.CORE_YOUR_MAKERSPACE_ACCESS_CHANGED_CHOOSE_AGAIN),null,null)
        val accountModules=value.modules.containsAll(listOf("membership","member_accounts"))
        return MakerCapabilities(accountModules&&"membership.self_service" in value.features,
            "notifications.member_preferences" in value.features,"notifications.digests" in value.features,"bookable_spaces" in value.modules,
            value.messaging.sms || value.messaging.whatsapp)
    }
}
