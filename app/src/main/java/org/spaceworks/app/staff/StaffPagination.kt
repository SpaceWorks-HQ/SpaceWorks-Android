package org.spaceworks.app.staff

import org.spaceworks.app.platform.MobileText

import java.net.URI
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** next links are validated and projected to a page number, never sent as arbitrary requests. */
object StaffPagination {
    fun validatedNext(value:String?,after:Int,origin:HttpUrl,path:String,search:String?=null):Int? {
        if(value==null)return null
        val message=org.spaceworks.app.platform.MobileCopyException(StaffStrings.STAFF_THE_NEXT_STAFF_PAGE_IS_UNAVAILABLE_REFRESH_AND_TRY_AGAIN)
        try {
            require(value.toByteArray().size<=8192&&'\\' !in value&&value.none(Char::isISOControl))
            val input=URI(value)
            require(input.rawFragment==null&&input.rawUserInfo==null&&input.rawAuthority?.contains('@')!=true)
            require(input.rawPath==path || (input.rawPath.orEmpty().isEmpty()&&input.scheme==null&&input.rawAuthority==null&&value.startsWith('?')))
            val base=requireNotNull(origin.resolve(path));val target=requireNotNull(base.resolve(value))
            require(target.scheme==origin.scheme&&target.host==origin.host&&target.port==origin.port&&target.encodedPath==path&&target.fragment==null&&target.username.isEmpty()&&target.password.isEmpty())
            require(target.queryParameterValues("page").size==1)
            require(target.queryParameterNames.all{it=="page"||(search!=null&&it=="q")})
            if("q" in target.queryParameterNames)require(target.queryParameterValues("q").size==1&&target.queryParameter("q")==search)
            return pageValue(target.queryParameter("page"),after)
        }catch(_:Exception){throw message}
    }
    /** State holders may use this after the live gateway's origin/resource validation. */
    fun page(next:String?,after:Int):Int? {
        if(next==null)return null
        require(next.toByteArray().size<=8192)
        val url=requireNotNull("https://page.invalid/".toHttpUrl().resolve(next))
        return pageValue(url.queryParameterValues("page").singleOrNull(),after)
    }
    private fun pageValue(raw:String?,after:Int):Int {
        require(!raw.isNullOrEmpty()&&raw.all{it in '0'..'9'}) {throw org.spaceworks.app.platform.MobileCopyException(StaffStrings.STAFF_THE_NEXT_STAFF_PAGE_IS_UNAVAILABLE_REFRESH_AND_TRY_AGAIN)}
        return requireNotNull(raw.toIntOrNull()).also{require(it>after)}
    }
}
