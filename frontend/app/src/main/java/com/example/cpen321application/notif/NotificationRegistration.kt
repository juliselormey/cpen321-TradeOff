package com.example.cpen321application.notif

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.example.cpen321application.ApiClient
import com.example.cpen321application.NotificationDeviceRequest
import com.example.cpen321application.util.SessionManager
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import com.google.firebase.installations.FirebaseInstallations
import java.util.concurrent.atomic.AtomicBoolean

object NotificationRegistration {
    private const val PREF_NAME = "notification_registration"
    private const val KEY_FID = "installation_id"
    private const val KEY_ENABLED = "notifications_enabled"
    private const val TAG = "NotificationRegistration"
    private const val CLEANUP_TIMEOUT_MS = 3000L

    private val mutex = Mutex()
    private val resettingInstallation = AtomicBoolean(false)

    fun register(context: Context) {
        if (!SessionManager.isLoggedIn(context) || !isEnabled(context) ||
            resettingInstallation.get()
        ) return
        FirebaseMessaging.getInstance().register()
            .addOnFailureListener {
                Log.w(TAG, "Firebase registration failed", it)
            }
    }

    suspend fun upload(context: Context, installationId: String) {
        mutex.withLock {
            if (!SessionManager.isLoggedIn(context) || !isEnabled(context)) return@withLock

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

                if (response.code() == 409) {
                    resetInstallation(context, installationId)
                } else if (!response.isSuccessful) {
                    Log.w(TAG, "Device registration failed: ${response.code()}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Device registration failed", e)
            }
        }
    }

    private fun resetInstallation(context: Context, rejectedId: String) {
        if (!resettingInstallation.compareAndSet(false, true)) return

        val installations = FirebaseInstallations.getInstance()

        installations.id.addOnSuccessListener { currentId ->
            if (currentId != rejectedId) {
                resettingInstallation.set(false)
                register(context)
            } else {
                installations.delete()
                    .addOnSuccessListener {
                        resettingInstallation.set(false)
                        register(context)
                    }
                    .addOnFailureListener {
                        resettingInstallation.set(false)
                        Log.w(TAG, "Installation reset failed", it)
                    }
            }
        }.addOnFailureListener {
            resettingInstallation.set(false)
            Log.w(TAG, "Installation lookup failed", it)
        }
    }

    suspend fun signOut(context: Context): Boolean = mutex.withLock {
        try {
            unregisterDevice(context)
        } finally {
            SessionManager.clearSession(context)
            NotificationManagerCompat.from(context).cancelAll()
        }

        true
    }

    private suspend fun unregisterDevice(context: Context) {
        val preferences = context.getSharedPreferences(
            PREF_NAME,
            Context.MODE_PRIVATE
        )

        val installationId = preferences.getString(KEY_FID, null)
            ?: return

        try {
            val response = withTimeoutOrNull(CLEANUP_TIMEOUT_MS) {
                ApiClient.create(context).unregisterNotificationDevice(
                    NotificationDeviceRequest(installationId)
                )
            }

            if (response?.isSuccessful == true) {
                preferences.edit().remove(KEY_FID).apply()
            } else {
                Log.w(TAG, "Device cleanup unsuccessful: ${response?.code()}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Device cleanup failed", e)
        }
    }

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        mutex.withLock {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply()

            if (enabled) {
                register(context)
            } else {
                NotificationManagerCompat.from(context).cancelAll()

                if (SessionManager.isLoggedIn(context)) {
                    unregisterDevice(context)
                }
            }
        }
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    private fun currentUserId(context: Context): String? {
        val token = SessionManager.getToken(context) ?: return null

        return runCatching {
            val payload = Base64.decode(
                token.split(".")[1],
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

            JSONObject(String(payload, Charsets.UTF_8)).getString("sub")
        }.getOrNull()
    }

    suspend fun displayIfAllowed(
        context: Context,
        userId: String,
        display: () -> Unit
    ) {
        mutex.withLock {
            if (isEnabled(context) && currentUserId(context) == userId) {
                display()
            }
        }
    }
}