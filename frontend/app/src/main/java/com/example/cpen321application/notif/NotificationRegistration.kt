package com.example.cpen321application.notif

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.example.cpen321application.ApiClient
import com.example.cpen321application.NotificationDeviceRequest
import com.example.cpen321application.util.SessionManager
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object NotificationRegistration {
    private const val PREF_NAME = "notification_registration"
    private const val KEY_FID = "installation_id"
    private const val TAG = "NotificationRegistration"

    private val mutex = Mutex()

    fun register(context: Context) {
        if (!SessionManager.isLoggedIn(context)) return

        FirebaseMessaging.getInstance().register()
            .addOnFailureListener {
                Log.w(TAG, "Firebase registration failed", it)
            }
    }

    suspend fun upload(context: Context, installationId: String) {
        mutex.withLock {
            if (!SessionManager.isLoggedIn(context)) return@withLock

            val preferences = context.getSharedPreferences(
                PREF_NAME,
                Context.MODE_PRIVATE
            )

            try {
                val api = ApiClient.create(context)
                val previousId = preferences.getString(KEY_FID, null)

                if (previousId != null && previousId != installationId) {
                    val removal = api.unregisterNotificationDevice(
                        NotificationDeviceRequest(previousId)
                    )

                    if (!removal.isSuccessful) {
                        Log.w(TAG, "Previous device removal failed: ${removal.code()}")
                        return@withLock
                    }
                }

                preferences.edit()
                    .putString(KEY_FID, installationId)
                    .apply()

                val response = api.registerNotificationDevice(
                    NotificationDeviceRequest(installationId)
                )

                if (!response.isSuccessful) {
                    Log.w(TAG, "Device registration failed: ${response.code()}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Device registration failed", e)
            }
        }
    }

    suspend fun signOut(context: Context): Boolean = mutex.withLock {
        val preferences = context.getSharedPreferences(
            PREF_NAME,
            Context.MODE_PRIVATE
        )

        val installationId = preferences.getString(KEY_FID, null)

        try {
            if (installationId != null) {
                val response = ApiClient.create(context)
                    .unregisterNotificationDevice(
                        NotificationDeviceRequest(installationId)
                    )

                if (!response.isSuccessful) {
                    Log.w(TAG, "Device unregistration failed: ${response.code()}")
                    return@withLock false
                }
            }

            preferences.edit().remove(KEY_FID).apply()
            SessionManager.clearSession(context)
            NotificationManagerCompat.from(context).cancelAll()

            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Device unregistration failed", e)
            false
        }
    }
}