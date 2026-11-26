package com.example.cpen321application

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlin.coroutines.cancellation.CancellationException
import com.example.cpen321application.util.SessionManager
import com.example.cpen321application.notif.NotificationRegistration

data class GoogleSignInResult(val idToken: String, val displayName: String)

suspend fun googleSignIn(context: Context): GoogleSignInResult {
    val credentialManager = CredentialManager.create(context)
    val serverClientId = BuildConfig.GOOGLE_CLIENT_ID.ifEmpty {
        "417938142085-ngdhrek8t9vggugpbcadcjbcrn7vb5r3.apps.googleusercontent.com"
    }

    val googleIdOption = GetSignInWithGoogleOption.Builder(
        serverClientId = serverClientId
    ).build()

    val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

    try {
        val response = try {
            credentialManager.getCredential(request = request, context = context)
        } catch (e: NoCredentialException) {
            Log.d("GOOGLE_DEBUG", "No authorized credentials found, falling back to all accounts", e)
            val fallbackOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(serverClientId)
                .build()
            val fallbackRequest = GetCredentialRequest.Builder()
                .addCredentialOption(fallbackOption)
                .build()
            credentialManager.getCredential(request = fallbackRequest, context = context)
        }

        val credential = response.credential

        Log.d("GOOGLE_DEBUG", "Credential type: ${credential.type}")

        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val firstName = googleIdTokenCredential.givenName ?: ""
            val lastName = googleIdTokenCredential.familyName ?: ""
            val fullname = if(lastName.isNotEmpty()) "$firstName $lastName" else firstName

            return GoogleSignInResult(idToken = googleIdTokenCredential.idToken, displayName = fullname)
        }

        throw IllegalStateException("Invalid credential type: ${credential.type}")

    } catch (e: GetCredentialCancellationException) {
        Log.i("CredentialManagerExc", "User Cancelled Sign-In", e)
        throw e
    } catch (e: GetCredentialException) {
        Log.e("CredentialManagerExc", "Sign-In Failed", e)
        throw e
    }
}

internal sealed interface LoginOutcome {
    object Success : LoginOutcome
    class Failure(val message: String) : LoginOutcome
}

internal suspend fun attemptLogin(
    context: Context,
    apiService: ApiService,
    signIn: suspend (Context) -> GoogleSignInResult,
): LoginOutcome {
    return try {
        val signInResult = signIn(context)
        val response = apiService.loginWithGoogle(GoogleAuthRequest(signInResult.idToken))
        val body = response.body()

        if (response.isSuccessful && body != null) {
            SessionManager.saveToken(context, body.accessToken)
            NotificationRegistration.register(context)
            LoginOutcome.Success
        } else {
            val errorBody = response.errorBody()?.string()
            Log.e("AUTH_ERROR", "Sign-in failed with HTTP ${response.code()}: $errorBody")
            LoginOutcome.Failure("Sign-in failed: ${response.code()}")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: GoogleIdTokenParsingException) {
        LoginOutcome.Failure("Could not read the Google credential. Please try signing in again.")
    } catch (e: GetCredentialException) {
        LoginOutcome.Failure(e.message ?: "Sign-in cancelled or failed")
    } catch (_: java.io.IOException) {
        LoginOutcome.Failure("Could not connect. Please try again.")
    } catch (_: com.google.gson.JsonParseException) {
        LoginOutcome.Failure("Could not read the sign-in response.")
    } catch (e: IllegalStateException) {
        LoginOutcome.Failure(e.message ?: "Invalid sign-in response")
    }
}
