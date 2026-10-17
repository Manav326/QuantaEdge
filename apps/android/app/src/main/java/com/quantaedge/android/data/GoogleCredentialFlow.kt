package com.quantaedge.android.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.io.IOException

/** Requests a real Google ID token for server-side verification; never fabricates a verified identity. */
object GoogleCredentialFlow {
    suspend fun requestIdToken(context: Context, webClientId: String): String {
        val clientId = webClientId.trim()
        if (clientId.isBlank()) {
            throw IOException("Google sign-in is not configured. Set GOOGLE_WEB_CLIENT_ID to the Google OAuth Web client ID.")
        }
        val manager = CredentialManager.create(context)
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        val credential = manager.getCredential(context, request).credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw IOException("Google did not return a supported ID token. Please try again.")
        }
        return try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (e: Exception) {
            throw IOException("Google's sign-in response could not be read. Please try again.", e)
        }
    }
}
