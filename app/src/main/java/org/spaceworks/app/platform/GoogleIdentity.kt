package org.spaceworks.app.platform

import android.app.Activity
import android.content.Context
import androidx.credentials.*
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.*
import org.spaceworks.app.auth.AuthCancelled
import org.spaceworks.app.auth.AuthMessage
import kotlinx.coroutines.CancellationException

object GoogleIdentity {
    suspend fun credential(activity:Activity,clientId:String,nonce:String):String {
        require(clientId.isNotBlank() && nonce.isNotBlank())
        val option=GetSignInWithGoogleOption.Builder(clientId).setNonce(nonce).build()
        try {
            val result=CredentialManager.create(activity).getCredential(activity,GetCredentialRequest.Builder().addCredentialOption(option).build()).credential
            if(result !is CustomCredential || result.type!=GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)throw AuthMessage("Google did not return a supported sign-in credential.")
            return GoogleIdTokenCredential.createFrom(result.data).idToken
        }catch(cancelled:CancellationException){throw cancelled}
        catch(_:GetCredentialCancellationException){throw AuthCancelled()}
        catch(f:AuthMessage){throw f}
        catch(_:Exception){throw AuthMessage("Google sign-in is unavailable. Try again.")}
    }
    suspend fun clear(context:Context) {CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())}
}
