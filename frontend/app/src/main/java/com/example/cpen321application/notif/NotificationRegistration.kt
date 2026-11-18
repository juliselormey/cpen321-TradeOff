package com.example.cpen321application.notif

import android.content.Context
import android.util.Log
import com.example.cpen321application.ApiClient
import com.example.cpen321application.NotificationDeviceRequest
import com.example.cpen321application.util.SessionManager
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException

object NotificationRegistration {
    private const val PREF_NAME = "notification_registration"
    private const val KEY_FID = "installation_id"
    private const val TAG = "NotificationRegistration"

    fun register(context: Context) {
        if (!SessionManager.isLoggedIn(context)) return

        FirebaseMessaging.getInstance().register()
            .addOnFailureListener {
                Log.w(TAG, "Firebase registration failed", it)
            }
    }

    suspend fun upload(context: Context, installationId: String) {
        if (!SessionManager.isLoggedIn(context)) return

        try {
            val response = ApiClient.create(context)
                .registerNotificationDevice(
                    NotificationDeviceRequest(installationId)
                )

            if (response.isSuccessful) {
                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_FID, installationId)
                    .apply()
            } else {
                Log.w(TAG, "Device registration failed: ${response.code()}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Device registration failed", e)
        }
    }

    suspend fun unregister(context: Context): Boolean {
        val preferences = context.getSharedPreferences(
            PREF_NAME,
            Context.MODE_PRIVATE
        )

        val installationId = preferences.getString(KEY_FID, null)
            ?: return true

        return try {
            val response = ApiClient.create(context)
                .unregisterNotificationDevice(
                    NotificationDeviceRequest(installationId)
                )

            if (response.isSuccessful) {
                preferences.edit().remove(KEY_FID).apply()
                true
            } else {
                Log.w(TAG, "Device unregistration failed: ${response.code()}")
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Device unregistration failed", e)
            false
        }
    }
}