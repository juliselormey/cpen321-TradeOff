package com.example.cpen321application.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

object SessionManager {
    private const val PREF_NAME = "user_session"
    private const val KEY_ACCESS_TOKEN = "access_token"

    fun saveToken(context: Context, token: String) {
        val sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sharedPreferences.edit().putString(KEY_ACCESS_TOKEN, token).apply()
    }

    fun getToken(context: Context): String? {
        val sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sharedPreferences.getString(KEY_ACCESS_TOKEN, null)
    }

    fun isLoggedIn(context: Context): Boolean {
        return !getToken(context).isNullOrEmpty()
    }

    internal fun observeToken(context: Context) = callbackFlow {
        val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ACCESS_TOKEN || key == null) trySend(getToken(context))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(getToken(context))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun clearSession(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_ACCESS_TOKEN).apply()
    }
}
