package com.example.cpen321application.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.cpen321application.R

fun createNotificationChannel(context: Context) {
    val channel = NotificationChannel(
        context.getString(R.string.notification_channel_id),
        context.getString(R.string.notification_channel_name),
        NotificationManager.IMPORTANCE_HIGH
    )

    val manager = context.getSystemService(
        NotificationManager::class.java
    )

    manager.createNotificationChannel(channel)
}
