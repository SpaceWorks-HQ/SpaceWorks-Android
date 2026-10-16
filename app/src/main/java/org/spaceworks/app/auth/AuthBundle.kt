package org.spaceworks.app.auth
import org.spaceworks.app.network.TokenStore
class AuthBundle(val holder:AuthStateHolder,val tokens:TokenStore,val demo:Boolean=false,val accounts:AccountStateHolder?=null,
    val previewAuth:PreviewAuthController?=null,val member:org.spaceworks.app.member.MemberStateHolder?=null,
    val actions:org.spaceworks.app.actions.MemberActionsStateHolder?=null,
    val origin:String="",val push:org.spaceworks.app.notifications.PushStateHolder?=null,
    val links:org.spaceworks.app.notifications.LinkStateHolder?=null,val pushConfigured:Boolean=false,val sessionEpoch:()->Long={0L},val pushPreview:org.spaceworks.app.notifications.PushPreviewControl?=null,
    val payments:org.spaceworks.app.payments.PaymentsStateHolder?=null,
    val staff:org.spaceworks.app.staff.StaffStateHolder?=null,val staffAvailable:()->Boolean={false},
    val management:org.spaceworks.app.account.AccountManagementCoordinator?=null,
    val deletionPreview:(List<Int>)->org.spaceworks.app.account.AccountDeletionPreview?={null})

/** Explicit preview reset. Never invokes a live API, provider SDK or another origin's store. */
fun AuthBundle.clearDemoState() {
    if(!demo)return
    management?.forget(this)
    if(tokens.read()!=null)tokens.replace(null)
    holder.invalidateInstallation(tokens.snapshot().epoch)
    accounts?.clear();staff?.resetAccount();member?.clear();actions?.clear();payments?.clear()
    links?.clear();push?.pause()
}
