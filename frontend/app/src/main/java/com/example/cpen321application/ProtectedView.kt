package com.example.cpen321application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.example.cpen321application.util.SessionManager

@Composable
fun ProtectedView(apiService: ApiService, onBack: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val tokens = remember(context) { SessionManager.observeToken(context) }
    val token by tokens.collectAsState(initial = SessionManager.getToken(context))
    if (token == null) LoginView(apiService = apiService, onBack = onBack) else content()
}
