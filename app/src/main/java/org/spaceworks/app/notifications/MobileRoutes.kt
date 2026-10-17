package org.spaceworks.app.notifications

import okhttp3.HttpUrl
import org.spaceworks.app.platform.PushHint
import java.net.URI
import java.util.UUID

enum class MobileSection { HOME, INVENTORY, ACTIVITY, INBOX, PROFILE, PAYMENTS, BOOKINGS, EVENTS, REGISTRATION, SPACES }
data class MobileDestination(val makerspaceId:Int,val section:MobileSection,val resourceId:String?=null)
data class LegacyMemberRoute(val slug:String,val staff:Boolean)

/** Product routes, not browser/API paths. Neither a link nor a push can select a deployment. */
object MobileRoutes {
    fun hint(value:PushHint):MobileDestination =
        if(value.version==2) value.link?.let(::relative)?.takeIf {it.makerspaceId==value.makerspaceId}
            ?: MobileDestination(value.makerspaceId,MobileSection.INBOX)
        else MobileDestination(value.makerspaceId,MobileSection.INBOX)

    fun external(raw:String,server:HttpUrl,scheme:String="spaceworks"):MobileDestination? = externalPath(raw,server,scheme)?.let(::relative)
    /** Exact published web landings only; caller freshly resolves slug and native staff authority. */
    fun legacy(raw:String,server:HttpUrl,scheme:String="spaceworks"):LegacyMemberRoute? = externalPath(raw,server,scheme)?.let(::legacyPath)
    fun legacyPath(raw:String):LegacyMemberRoute? {
        val parts=PushHint.safeInAppPath(raw)?.removePrefix("/")?.split('/')?:return null
        val slug=parts.getOrNull(1)?.takeIf {it.matches(Regex("[A-Za-z0-9_-]{1,256}"))}?:return null
        if(parts[0]!="m")return null
        return when {
            parts.size==3 && parts[2]=="member"->LegacyMemberRoute(slug,false)
            parts.size==4 && parts[2]=="admin" && parts[3]=="notifications"->LegacyMemberRoute(slug,true)
            else->null
        }
    }
    private fun externalPath(raw:String,server:HttpUrl,scheme:String):String? {
        if(raw.toByteArray(Charsets.UTF_8).size>2048 || raw.any {it=='%'||it=='\\'||it.isISOControl()||Character.getType(it)==Character.FORMAT.toInt()})return null
        val uri=runCatching {URI(raw)}.getOrNull()?:return null
        if(uri.rawQuery!=null||uri.rawFragment!=null||uri.rawUserInfo!=null)return null
        val path=when {
            uri.scheme==scheme && uri.port==-1 && (uri.rawAuthority==null||uri.host=="makerspaces") ->
                if(uri.host=="makerspaces") "/makerspaces${uri.path.orEmpty()}" else uri.path.orEmpty()
            uri.scheme==server.scheme && uri.host==server.host && (if(uri.port<0)if(uri.scheme=="https")443 else 80 else uri.port)==server.port -> uri.path.orEmpty()
            else->return null
        }
        return PushHint.safeInAppPath(path)
    }

    fun relative(raw:String):MobileDestination? {
        val path=PushHint.safeInAppPath(raw)?:return null
        val segments=path.removePrefix("/").split('/')
        if(segments.size !in 3..4 || segments[0]!="makerspaces")return null
        val maker=positive(segments[1])?:return null
        val id=segments.getOrNull(3)
        val section=when(segments[2]) {
            "home"->MobileSection.HOME;"inventory"->MobileSection.INVENTORY;"activity"->MobileSection.ACTIVITY
            "inbox"->MobileSection.INBOX;"profile"->MobileSection.PROFILE;"payments"->MobileSection.PAYMENTS
            "bookings"->MobileSection.BOOKINGS;"events"->MobileSection.EVENTS;"registrations"->MobileSection.REGISTRATION;"spaces"->MobileSection.SPACES
            else->return MobileDestination(maker,MobileSection.INBOX)
        }
        if(id==null)return if(section==MobileSection.REGISTRATION)null else MobileDestination(maker,section)
        val resource=when(section) {
            MobileSection.INVENTORY,MobileSection.PAYMENTS,MobileSection.REGISTRATION->positive(id)?.toString()
            MobileSection.BOOKINGS,MobileSection.EVENTS,MobileSection.SPACES->runCatching {UUID.fromString(id).toString().takeIf {it==id.lowercase()}}.getOrNull()
            else->null
        }?:return null
        return MobileDestination(maker,section,resource)
    }
    private fun positive(value:String)=value.takeIf {it.isNotEmpty()&&it.all {c->c in '0'..'9'}}?.toIntOrNull()?.takeIf {it>0}
}
