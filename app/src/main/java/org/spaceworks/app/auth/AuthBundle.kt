package org.spaceworks.app.auth
import org.spaceworks.app.network.TokenStore
class AuthBundle(val holder:AuthStateHolder,val tokens:TokenStore,val demo:Boolean=false,val accounts:AccountStateHolder?=null,
    val previewAuth:PreviewAuthController?=null,val member:org.spaceworks.app.member.MemberStateHolder?=null,
    val actions:org.spaceworks.app.actions.MemberActionsStateHolder?=null,
    val origin:String="",val push:org.spaceworks.app.notifications.PushStateHolder?=null,
    val links:org.spaceworks.app.notifications.LinkStateHolder?=null,val pushConfigured:Boolean=false,val sessionEpoch:()->Long={0L},val pushPreview:org.spaceworks.app.notifications.PushPreviewControl?=null,
    val payments:org.spaceworks.app.payments.PaymentsStateHolder?=null,
    val staff:org.spaceworks.app.staff.StaffStateHolder?=null,val staffAvailable:()->Boolean={false})
