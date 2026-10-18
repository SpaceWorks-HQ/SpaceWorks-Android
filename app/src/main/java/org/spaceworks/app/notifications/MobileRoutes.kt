package org.spaceworks.app.notifications

import okhttp3.HttpUrl
import org.spaceworks.app.platform.PushHint
import java.net.URI
import java.util.UUID

enum class MobileSection { HOME, INVENTORY, ACTIVITY, INBOX, PROFILE, PAYMENTS, BOOKINGS, EVENTS, REGISTRATION, SPACES }
data class MobileDestination(val makerspaceId:Int,val section:MobileSection,val resourceId:String?=null)
/** A slug-addressed web landing from the server link contract; the slug is always freshly authorized. */
data class LegacyMemberRoute(val slug:String,val staff:Boolean,
    val section:MobileSection=if(staff)MobileSection.INBOX else MobileSection.HOME,val resource:String?=null)

/** Product routes, not browser/API paths. Neither a link nor a push can select a deployment. */
object MobileRoutes {
    fun hint(value:PushHint):MobileDestination =
        if(value.version==2) value.link?.let(::relative)?.takeIf {it.makerspaceId==value.makerspaceId}
            // A verified custom-domain form names no slug: the hint's own makerspace owns it.
            ?: value.link?.let {link->PushHint.safeInAppPath(link)?.let {contract(segments(it))}}?.let {(section,id)->MobileDestination(value.makerspaceId,section,id)}
            ?: MobileDestination(value.makerspaceId,MobileSection.INBOX)
        else MobileDestination(value.makerspaceId,MobileSection.INBOX)
    private fun segments(path:String)=path.removePrefix("/").removeSuffix("/").split('/')
    private val tabs=mapOf("overview" to MobileSection.HOME,"loans" to MobileSection.ACTIVITY,"bookings" to MobileSection.BOOKINGS,
        "events" to MobileSection.EVENTS,"payments" to MobileSection.PAYMENTS,"inbox" to MobileSection.INBOX,
        "account" to MobileSection.PROFILE,"membership" to MobileSection.PROFILE)
    private fun uuid(value:String)=runCatching {UUID.fromString(value).toString().takeIf {it==value.lowercase()}}.getOrNull()
    /**
     * Member destinations of the server's mobile link contract (backend mobile_link_contract.py),
     * without the `/m/{slug}` prefix. Staff/admin/control/API paths are never claimed.
     */
    private fun contract(parts:List<String>):Pair<MobileSection,String?>? = when {
        parts==listOf("member") -> MobileSection.HOME to null
        parts.size==2 && parts[0]=="member" -> (tabs[parts[1]]?:MobileSection.HOME) to null
        parts.size==4 && parts[0]=="member" && parts[1]=="events" && parts[3]=="ticket" -> positive(parts[2])?.let {MobileSection.REGISTRATION to it.toString()}
        parts.size==2 && parts[0]=="events" -> uuid(parts[1])?.let {MobileSection.EVENTS to it}
        parts.size==2 && parts[0]=="bookings" -> uuid(parts[1])?.let {MobileSection.BOOKINGS to it}
        parts.size==2 && parts[0]=="items" -> positive(parts[1])?.let {MobileSection.INVENTORY to it.toString()}
        else -> null
    }

    fun external(raw:String,server:HttpUrl,scheme:String="spaceworks"):MobileDestination? = externalPath(raw,server,scheme)?.let(::relative)
    /** Exact published web landings only; caller freshly resolves slug and native staff authority. */
    fun legacy(raw:String,server:HttpUrl,scheme:String="spaceworks"):LegacyMemberRoute? = externalPath(raw,server,scheme)?.let(::legacyPath)
    fun legacyPath(raw:String):LegacyMemberRoute? {
        val parts=PushHint.safeInAppPath(raw)?.let(::segments)?:return null
        val slug=parts.getOrNull(1)?.takeIf {it.matches(Regex("[A-Za-z0-9_-]{1,256}"))}?:return null
        if(parts[0]!="m")return null
        if(parts.size==4 && parts[2]=="admin" && parts[3]=="notifications")return LegacyMemberRoute(slug,true)
        val (section,resource)=contract(parts.drop(2))?:return null
        return LegacyMemberRoute(slug,false,section,resource)
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
