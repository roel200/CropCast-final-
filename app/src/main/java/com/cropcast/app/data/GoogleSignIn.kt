package com.cropcast.app.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

object GoogleSignIn {
    suspend fun getIdToken(context: Context): String {
        // Resolved dynamically so demo builds also work without google-services.json.
        val resource = context.resources.getIdentifier(
            "default_web_client_id", "string", context.packageName
        )
        check(resource != 0) {
            "Google sign-in is not configured. Enable Google in Firebase Authentication and download an updated google-services.json."
        }
        val option = GetSignInWithGoogleOption.Builder(context.getString(resource)).build()
        val result = CredentialManager.create(context).getCredential(
            context = context,
            request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        )
        val credential = result.credential
        check(credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Google returned an unsupported credential. Please try again."
        }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
}
