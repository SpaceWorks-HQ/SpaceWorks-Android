package org.spaceworks.app.account

import org.spaceworks.app.auth.AuthBundle
import org.spaceworks.app.platform.MobileText
import java.util.IdentityHashMap

/** UI ownership is controller-local. Only encrypted credentials/rotation locks are process-shared. */
class AccountManagementCoordinator(
    text:(AccountCopy)->String={it.english},
    failureText:MobileText=MobileText.ENGLISH,
) {
    private class Entry(val bundle:AuthBundle,val runtime:NativeAccountRuntime,
        val gateway:(AccountManagementContext)->AccountGateway)
    private val entries=IdentityHashMap<AuthBundle,Entry>()
    private var active:Entry?=null
    private var visible:AccountManagementStateHolder?=null
    private val boundary=NativeAccountBoundary{active?.runtime}
    val owner=AccountManagementOwner(boundary::identity,boundary::selected,boundary::ended,
        boundary::end,boundary::foreground,boundary::dropMembership,
        gatewayFactory={context->
            val entry=active?:throw kotlinx.coroutines.CancellationException("No active account")
            if(boundary.identity()!=context.identity)throw kotlinx.coroutines.CancellationException("Account changed")
            entry.gateway(context)
        },text=text,failureText=failureText)
    fun register(bundle:AuthBundle,runtime:NativeAccountRuntime,gateway:(AccountManagementContext)->AccountGateway) {
        check(!entries.containsKey(bundle))
        entries[bundle]=Entry(bundle,runtime,gateway)
    }
    /** Called before publishing another origin or demo bundle to the UI. */
    fun show(bundle:AuthBundle?) {
        val replacement=bundle?.let {requireNotNull(entries[it])}
        if(active!==replacement) {visible=null;owner.resetAccount();active=replacement}
    }
    fun scopeChanged(runtime:NativeAccountRuntime) {if(active?.runtime===runtime){visible=null;owner.clearScope()}}
    fun sessionEnded(runtime:NativeAccountRuntime) {if(active?.runtime===runtime){visible=null;owner.resetAccount()}}
    fun showing(holder:AccountManagementStateHolder){if(owner.current()===holder)visible=holder}
    fun hidden(holder:AccountManagementStateHolder){if(visible===holder)visible=null}
    fun protectsNavigation()=visible!=null
    fun forget(bundle:AuthBundle) {
        if(active?.bundle===bundle)show(null)
        entries.remove(bundle)
    }
    fun background(){owner.background()}
}
