package org.spaceworks.app.auth

import kotlinx.coroutines.flow.StateFlow

// Local debug UI protocol, never an API payload or production authentication mechanism.
enum class PreviewAuthPage { METHODS, PROVIDER, PHONE, CODE, UPDATE_REQUIRED, SESSION_ENDED }
data class PreviewAuthState(val page:PreviewAuthPage?=null,val provider:String="",val phone:String="",
    val country:String="+91",val busy:Boolean=false,val error:String?=null,val notice:String?=null,
    val resendAt:Long=0,val expiresAt:Long=0,val requireMfa:Boolean=false)
interface PreviewAuthController {
    val state:StateFlow<PreviewAuthState>
    fun open()
    fun choose(provider:String)
    fun country(value:String)
    fun phone(value:String)
    fun requireMfa(value:Boolean)
    fun cancel()
    fun unavailable()
    fun expire()
    fun updateRequired()
    fun sessionEnded()
    fun sendCode()
    suspend fun continueProvider()
    suspend fun verify(code:String)
}
