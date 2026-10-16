package org.spaceworks.app.account

import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.TokenStore

/** One controller's currently visible authentication runtime; credentials never enter identities. */
class NativeAccountRuntime(
    val origin:String,
    val tokens:TokenStore,
    val acceptedEpoch:()->Long?,
    val cachedUserId:()->String?,
    val selected:()->MemberScope?,
    val localEnd:suspend(String?)->Boolean,
    val foreground:suspend()->Unit,
    val dropMembership:(Int)->Boolean,
) {
    fun identity():AccountOwnerIdentity? {
        val epoch=acceptedEpoch()?:return null
        val user=cachedUserId()?.takeIf(String::isNotBlank)?:return null
        val snapshot=tokens.snapshot()
        if(snapshot.epoch!=epoch || snapshot.pair==null)return null
        return AccountOwnerIdentity(origin,user,epoch)
    }
}

/** Recheck the active origin/account/installation immediately before every native auth callback. */
class NativeAccountBoundary(private val active:()->NativeAccountRuntime?) {
    fun identity()=active()?.identity()
    fun selected()=active()?.takeIf {it.identity()!=null}?.selected?.invoke()
    fun ended(expected:AccountOwnerIdentity):Boolean {
        val runtime=active()?:return false
        if(runtime.origin!=expected.origin)return false
        val snapshot=runtime.tokens.snapshot()
        return snapshot.pair==null && snapshot.epoch==expected.installationEpoch+1
    }
    suspend fun end(expected:AccountOwnerIdentity,notice:String?):Boolean {
        val runtime=active()?:return false
        if(runtime.origin!=expected.origin || runtime.cachedUserId()!=expected.userId)return false
        if(runtime.identity()!=expected && !ended(expected))return false
        // No suspension between ownership proof and entering the auth holder's guarded boundary.
        return runtime.localEnd(notice)
    }
    suspend fun foreground(expected:AccountOwnerIdentity) {
        val runtime=active()?:return
        if(runtime.identity()==expected)runtime.foreground()
    }
    fun dropMembership(expected:AccountOwnerIdentity,id:Int):Boolean {
        val runtime=active()?:return false
        return runtime.identity()==expected && runtime.dropMembership(id)
    }
}
