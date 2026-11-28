package com.example.cpen321application.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

object SessionManager {
    private const val PREF_NAME = "user_session"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private fun preferences(context: Context) = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun saveToken(context: Context, token: String) {
        synchronized(this) { preferences(context).edit().putString(KEY_ACCESS_TOKEN, token).apply() }
    }
    fun getToken(context: Context): String? {
        val token = preferences(context).getString(KEY_ACCESS_TOKEN, null) ?: return null
        if (tokenIsCurrent(token)) return token
        invalidate(context, token)
        return null
    }
    fun isLoggedIn(context: Context): Boolean = getToken(context) != null

    /** A delayed response from an old session must not clear a newer login. */
    internal fun invalidate(context: Context, rejectedToken: String) {
        val prefs = preferences(context)
        synchronized(this) {
            if (prefs.getString(KEY_ACCESS_TOKEN, null) == rejectedToken) prefs.edit().remove(KEY_ACCESS_TOKEN).apply()
        }
    }
    private fun tokenChanges(context: Context) = callbackFlow {
        val prefs = preferences(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ACCESS_TOKEN || key == null) trySend(prefs.getString(KEY_ACCESS_TOKEN, null))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(prefs.getString(KEY_ACCESS_TOKEN, null))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun observeToken(context: Context) = tokenChanges(context).flatMapLatest { token ->
        flow {
            val remaining = (tokenExpiryMillis(token) ?: 0) - System.currentTimeMillis()
            if (token != null && remaining > 0) {
                emit(token)
                delay(remaining)
            }
            if (token != null) invalidate(context, token)
            emit(null)
        }
    }.distinctUntilChanged()

    fun clearSession(context: Context) { preferences(context).edit().remove(KEY_ACCESS_TOKEN).apply() }
}
