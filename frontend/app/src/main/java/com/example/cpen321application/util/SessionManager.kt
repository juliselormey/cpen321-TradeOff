package com.example.cpen321application.util

import android.content.Context

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

    fun clearSession(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_ACCESS_TOKEN).apply()
    }
}