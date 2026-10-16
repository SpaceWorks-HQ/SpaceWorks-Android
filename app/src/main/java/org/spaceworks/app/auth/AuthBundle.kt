package org.spaceworks.app.auth
import org.spaceworks.app.network.TokenStore
class AuthBundle(val holder:AuthStateHolder,val tokens:TokenStore,val demo:Boolean=false)
