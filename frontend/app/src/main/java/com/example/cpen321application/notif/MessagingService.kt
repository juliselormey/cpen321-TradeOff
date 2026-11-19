package com.example.cpen321application.notif

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.cpen321application.MainActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.example.cpen321application.R
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class MessagingService : FirebaseMessagingService() {

    override fun onRegistered(installationId: String) {
        super.onRegistered(installationId)

        runBlocking(Dispatchers.IO) {
            NotificationRegistration.upload(
                applicationContext,
                installationId
            )
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val userId = message.data["userId"] ?: return
        val title = message.data["title"] ?: return
        val body = message.data["body"] ?: return

        runBlocking(Dispatchers.IO) {
            NotificationRegistration.displayIfAllowed(
                applicationContext,
                userId
            ) {
                showNotification(title, body)
            }
        }
    }

    private fun showNotification(title: String, body: String) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            this,
            getString(R.string.notification_channel_id)
        )
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(this)
            .notify(Random.nextInt(), notification)
    }

}
