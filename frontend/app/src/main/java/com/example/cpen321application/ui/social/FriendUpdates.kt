package com.example.cpen321application.ui.social

import java.io.IOException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.retryWhen
import com.google.gson.JsonParser
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal enum class FriendUpdate { REFRESH, RECONNECTING, UNAUTHORIZED }
private class FriendSessionExpired : IOException()

/** Reconnects the push subscription; HTTP snapshots run only on ready/change events. */
internal fun friendUpdates(client: WebSocket.Factory, url: String): Flow<FriendUpdate> = callbackFlow {
    val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val type = runCatching { JsonParser.parseString(text).asJsonObject.get("type")?.asString }.getOrNull()
            if (type == "friends.ready" || type == "friends.changed") trySend(FriendUpdate.REFRESH)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
            close(if (code == 1008) FriendSessionExpired() else IOException("Live connection closed"))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            close(IOException("Live connection closed"))
        }

        override fun onFailure(webSocket: WebSocket, failure: Throwable, response: Response?) {
            val unauthorized = response?.code == 401 || response?.code == 403
            response?.close()
            close(if (unauthorized) FriendSessionExpired() else IOException("Live connection failed", failure))
        }
    })
    awaitClose { socket.cancel() }
}.conflate().retryWhen { cause, attempt ->
    if (cause is FriendSessionExpired) return@retryWhen false
    if (cause !is IOException) return@retryWhen false
    emit(FriendUpdate.RECONNECTING)
    delay(minOf(30_000L, 1000L * (1L shl attempt.coerceAtMost(5).toInt())))
    true
}.catch { cause ->
    if (cause is FriendSessionExpired) emit(FriendUpdate.UNAUTHORIZED) else throw cause
}
