package com.example.cpen321application.ui.social

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp

@Composable
internal fun AddFriendDialog(
    target: String, busy: Boolean, error: String?,
    onChange: (String) -> Unit, onDismiss: () -> Unit, onSend: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Add friend") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter your friend’s unique username from Settings or their Friends screen.")
                OutlinedTextField(value = target, onValueChange = onChange, enabled = !busy,
                    label = { Text("Friend username") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && target.isNotBlank(), onClick = onSend) {
                Text(if (busy) "Sending…" else "Send request")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun RemoveFriendDialog(
    name: String, busy: Boolean, error: String?, onDismiss: () -> Unit, onRemove: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Remove $name?") },
        text = {
            Column {
                Text("You can send a new friend request later.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onRemove) { Text("Remove") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}
