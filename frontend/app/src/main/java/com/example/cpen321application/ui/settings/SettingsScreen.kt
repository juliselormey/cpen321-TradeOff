package com.example.cpen321application.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cpen321application.ApiService
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    api: ApiService,
    darkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    onSignIn: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val state = remember(api, context) { SettingsState(api, context) }
    LaunchedEffect(state) { state.load() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Make TradeOff yours", color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppearanceSettings(darkMode, onDarkModeChange)
        AccountSettings(state, onSignIn)
        Text(
            "TradeOff • Practice investing",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun AppearanceSettings(darkMode: Boolean, onDarkModeChange: (Boolean) -> Unit) {
    SettingsCard {
        Text("Appearance", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dark mode", fontWeight = FontWeight.SemiBold)
                Text(
                    if (darkMode) "Dark colors across the app" else "Light colors across the app",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = darkMode, onCheckedChange = onDarkModeChange)
        }
        Text(
            "Saved on this device.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AccountSettings(state: SettingsState, onSignIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirmLogout by remember { mutableStateOf(false) }
    SettingsCard {
        Text("Account", style = MaterialTheme.typography.titleLarge)
        if (!state.signedIn) {
            Text("Sign in to manage your profile and connect with friends.")
            Button(onClick = onSignIn, enabled = !state.busy) { Text("Sign in with Google") }
        } else {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.profile != null) ProfileEditor(state, onSave = { scope.launch { state.save() } })
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (state.expired) {
                Button(onClick = onSignIn, enabled = !state.busy) { Text("Sign in again") }
            } else if (state.error != null) {
                TextButton(onClick = { scope.launch { state.load() } }, enabled = !state.busy) {
                    Text("Reload profile")
                }
            }
            HorizontalDivider()
            OutlinedButton(onClick = { confirmLogout = true }, enabled = !state.busy) { Text("Sign out") }
        }
    }
    if (confirmLogout) {
        SignOutDialog(
            onDismiss = { confirmLogout = false },
            onConfirm = {
                confirmLogout = false
                scope.launch { state.signOut() }
            }
        )
    }
}

@Composable
private fun ProfileEditor(state: SettingsState, onSave: () -> Unit) {
    Text(state.profile?.email.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        value = state.name, onValueChange = state::updateName,
        label = { Text("Display name") }, enabled = !state.busy && !state.expired, singleLine = true,
        modifier = Modifier.fillMaxWidth(), supportingText = { Text("1–60 characters; visible to friends") }
    )
    OutlinedTextField(
        value = state.username, onValueChange = state::updateUsername,
        label = { Text("Username") }, prefix = { Text("@") }, enabled = !state.busy && !state.expired,
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        supportingText = { Text("3–24 letters, numbers or underscores") }
    )
    Button(enabled = state.canSave, onClick = onSave) {
        Text(if (state.busy) "Please wait…" else "Save profile")
    }
}

@Composable
private fun SignOutDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign out?") },
        text = { Text("Your profile and portfolio will stay saved.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content
        )
    }
}
