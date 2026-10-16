package org.spaceworks.app.auth
import org.spaceworks.app.network.TokenStore
class AuthBundle(val holder:AuthStateHolder,val tokens:TokenStore,val demo:Boolean=false,val accounts:AccountStateHolder?=null,
    val previewAuth:PreviewAuthController?=null,val member:org.spaceworks.app.member.MemberStateHolder?=null,
    val actions:org.spaceworks.app.actions.MemberActionsStateHolder?=null)
