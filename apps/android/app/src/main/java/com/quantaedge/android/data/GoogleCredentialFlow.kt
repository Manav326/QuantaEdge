package com.quantaedge.android.data

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.io.IOException

/** Raised when the server has no public Google OAuth Web client ID configured. */
class GoogleSignInConfigurationException : IOException("Google sign-in is not configured on the server.")

/** Credential Manager can misreport app/client registration failures as account re-auth cancellations. */
class GoogleSignInReauthException(cause: Throwable? = null) : Exception(
    "Google could not re-authenticate this Android app. If retrying does not work, verify that the Android OAuth client is registered with this build's exact application ID and signing-certificate SHA-1.",
    cause
)

class GoogleSignInUnavailableException(cause: Throwable? = null) : Exception(
    "No Google account is available for sign-in. Add a Google account to this device, update Google Play services, and make sure Sign in with Google is enabled in your Google Account settings.",
    cause
)

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
        // Follow the Credential Manager sign-in sequence: first offer accounts that
        // have already authorized the app. Accounts that need re-authentication are
        // handled by Google's explicit Sign in with Google flow below.
        val authorizedOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(false)
            .build()
        val authorizedRequest = GetCredentialRequest.Builder()
            .addCredentialOption(authorizedOption)
            .build()

        val credential = try {
            manager.getCredential(context, authorizedRequest).credential
        } catch (_: NoCredentialException) {
            requestExplicitGoogleSignIn(manager, context, clientId)
        } catch (error: GetCredentialCancellationException) {
            if (!isAccountReauthFailure(error)) throw error
            // [16] Account reauth failed is reported as a cancellation exception,
            // not NoCredentialException; explicitly retry instead of stopping here.
            requestExplicitGoogleSignIn(manager, context, clientId)
        }

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

    private suspend fun requestExplicitGoogleSignIn(
        manager: CredentialManager,
        context: Context,
        clientId: String
    ): Credential {
        val signInOption = GetSignInWithGoogleOption.Builder(clientId).build()
        val signInRequest = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()
        return try {
            manager.getCredential(context, signInRequest).credential
        } catch (error: NoCredentialException) {
            throw GoogleSignInUnavailableException(error)
        } catch (error: GetCredentialCancellationException) {
            if (isAccountReauthFailure(error)) throw GoogleSignInReauthException(error)
            throw error
        }
    }

    private fun isAccountReauthFailure(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("Account reauth failed", ignoreCase = true) ||
            message.contains("[16]", ignoreCase = true) && message.contains("reauth", ignoreCase = true)
    }

    /** Clear provider-side sign-in state on explicit app logout, without signing the user out of Android. */
    suspend fun clearCredentialState(context: Context) {
        CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
    }
}
