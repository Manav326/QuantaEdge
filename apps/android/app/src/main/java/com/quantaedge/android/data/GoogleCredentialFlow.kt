package com.quantaedge.android.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.io.IOException

/** Raised when the server has no public Google OAuth Web client ID configured. */
class GoogleSignInConfigurationException : IOException("Google sign-in is not configured on the server.")

/** Loads the server's public OAuth audience and requests a real Google ID token for server-side verification. */
object GoogleCredentialFlow {
    suspend fun requestIdToken(context: Context): String {
        val config = try {
            ApiClient.getObject("/api/v1/auth/google/config")
        } catch (error: Exception) {
            throw IOException("Google sign-in configuration could not be loaded. Check your connection and retry.", error)
        }
        val clientId = config.optString("clientId").trim()
        if (clientId.isBlank() || !config.optBoolean("configured", false)) {
            throw GoogleSignInConfigurationException()
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
