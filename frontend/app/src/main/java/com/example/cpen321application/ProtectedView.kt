package com.example.cpen321application

import android.content.Context
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.cpen321application.util.SessionManager

@Composable
fun ProtectedView(
    apiService: ApiService,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var isUserLoggedIn by remember { mutableStateOf(SessionManager.isLoggedIn(context)) }

    DisposableEffect(context) {
        val preferences = context.getSharedPreferences("user_session", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            isUserLoggedIn = SessionManager.isLoggedIn(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        isUserLoggedIn = SessionManager.isLoggedIn(context)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    if (!isUserLoggedIn) {
        LoginView(
            apiService = apiService,
            onBack = onBack,
            onSuccess = {
                isUserLoggedIn = true
            }
        )
    } else {
        content()
    }
}
