package com.example.cpen321application

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.cpen321application.notif.NotificationRegistration
import com.example.cpen321application.notif.createNotificationChannel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel(this)
        NotificationRegistration.register(applicationContext)
        val api = ApiClient.create(applicationContext)
        enableEdgeToEdge()
        setContent { TradeOffApp(api) }
    }
}