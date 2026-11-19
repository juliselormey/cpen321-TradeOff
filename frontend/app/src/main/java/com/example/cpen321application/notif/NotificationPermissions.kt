package com.example.cpen321application.notif

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

@Composable
fun NotificationPermission() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember {
        mutableStateOf(
            NotificationRegistration.isEnabled(context) &&
                    NotificationManagerCompat.from(context)
                        .areNotificationsEnabled()
        )
    }

    var busy by remember { mutableStateOf(false) }

    fun updateEnabled(value: Boolean) {
        enabled = value
        busy = true

        scope.launch {
            try {
                NotificationRegistration.setEnabled(context, value)
            } finally {
                busy = false
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateEnabled(granted)
    }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Push notifications", fontWeight = FontWeight.SemiBold)
            Text(
                if (enabled) "Receive trading and social alerts"
                else "Notifications disabled",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Switch(
            checked = enabled,
            enabled = !busy,
            onCheckedChange = { checked ->
                if (checked &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    updateEnabled(checked)
                }
            }
        )
    }
}
