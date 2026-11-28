package com.example.cpen321application

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object ApiConfiguration {
    val apiBaseUrl: String get() = baseUrl(BuildConfig.API_BASE_URL, "API_BASE_URL")
    val marketBaseUrl: String get() = baseUrl(BuildConfig.MARKET_API_BASE_URL.ifBlank { apiBaseUrl }, "MARKET_API_BASE_URL")
    val googleClientId: String get() = BuildConfig.GOOGLE_CLIENT_ID.trim().also {
        check(it.isNotEmpty()) { "Set GOOGLE_CLIENT_ID in frontend/local.properties and rebuild the app." }
    }
    private fun baseUrl(value: String, setting: String): String {
        val url = value.trim().toHttpUrlOrNull()
        check(url != null) { "Set a valid $setting in frontend/local.properties and rebuild the app." }
        return url.toString().trimEnd('/') + "/"
    }
    fun eventsUrl(base: String, path: String): String = base.trimEnd('/')
        .replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + path
}
