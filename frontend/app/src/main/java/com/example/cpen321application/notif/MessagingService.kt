package com.example.cpen321application.notif

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService

class MessagingService : FirebaseMessagingService() {

    override fun onRegistered(installationId: String) {
        super.onRegistered(installationId)
        Log.d("TradeOffFCM", "firebase reg successful")
    }

}