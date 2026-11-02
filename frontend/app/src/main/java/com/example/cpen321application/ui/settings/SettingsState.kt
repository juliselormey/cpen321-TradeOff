package com.example.cpen321application.ui.settings

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.ClearCredentialException
import com.example.cpen321application.ApiService
import com.example.cpen321application.ProfileResponse
import com.example.cpen321application.ProfileUpdate
import com.example.cpen321application.UserProfile
import com.example.cpen321application.util.SessionManager
import com.google.gson.JsonParseException
import java.io.IOException
import java.util.Locale
import retrofit2.Response

internal class SettingsState(private val api: ApiService, private val context: Context) {
    var signedIn by mutableStateOf(SessionManager.isLoggedIn(context))
        private set
    var profile by mutableStateOf<UserProfile?>(null)
        private set
    var name by mutableStateOf("")
    var username by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var expired by mutableStateOf(false)
        private set

    val canSave: Boolean
        get() {
            val current = profile ?: return false
            val valid = name.trim().length in 1..60 && USERNAME.matches(username.trim())
            val changed = name.trim() != current.displayName.orEmpty() ||
                username.trim().lowercase(Locale.ROOT) != current.username.orEmpty()
            return valid && changed && !busy && !expired
        }

    fun updateName(value: String) {
        name = value
        notice = null
    }

    fun updateUsername(value: String) {
        username = value
        notice = null
    }

    suspend fun load() {
        if (signedIn && !busy) requestProfile(save = false)
    }

    suspend fun save() {
        if (canSave) requestProfile(save = true)
    }

    private suspend fun requestProfile(save: Boolean) {
        busy = true
        error = null
        notice = null
        try {
            val authorization = "Bearer " + SessionManager.getToken(context)
            val response = if (save) {
                api.updateProfile(authorization, ProfileUpdate(name.trim(), username.trim()))
            } else api.profile(authorization)
            acceptResponse(response, save)
        } catch (_: IOException) {
            error = "Couldn't connect. Check your connection and reload your profile."
        } catch (_: JsonParseException) {
            error = "Couldn't read your profile. Please try again."
        } finally {
            busy = false
        }
    }

    private fun acceptResponse(response: Response<ProfileResponse>, saved: Boolean) {
        val user = response.body()?.user
        if (response.isSuccessful && user != null) {
            profile = user
            name = user.displayName.orEmpty()
            username = user.username.orEmpty()
            expired = false
            if (saved) notice = "Profile saved."
        } else {
            expired = response.code() == 401
            error = when (response.code()) {
                401 -> "Your session expired. Sign in again to continue."
                409 -> "That username is already taken. Try another."
                400 -> "Check your name and username, then try again."
                404, 405 -> "Profile editing is temporarily unavailable. Please try again later."
                else -> "Couldn't save or load your profile. Please retry."
            }
        }
    }

    suspend fun signOut() {
        busy = true
        SessionManager.clearSession(context)
        signedIn = false
        profile = null
        name = ""
        username = ""
        error = null
        notice = null
        expired = false
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (error: ClearCredentialException) {
            Log.w("Settings", "Provider sign-out failed; local session has been cleared.", error)
        } finally {
            busy = false
        }
    }

    companion object {
        private val USERNAME = Regex("[A-Za-z0-9_]{3,24}")
    }
}
