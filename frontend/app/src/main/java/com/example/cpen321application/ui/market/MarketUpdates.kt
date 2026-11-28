package com.example.cpen321application.ui.market

import com.example.cpen321application.ui.market.data.MarketStock
import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.io.IOException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.retryWhen
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal sealed interface MarketUpdate {
    data class Snapshot(val stocks: List<MarketStock>, val checkedAt: String?) : MarketUpdate
    data object Reconnecting : MarketUpdate
    data object Unavailable : MarketUpdate
}

private data class MarketEvent(val type: String?, val stocks: List<MarketStock>?, val checkedAt: String?)

/** Receives full snapshots directly; reconnecting always obtains a fresh server snapshot. */
internal fun marketUpdates(client: WebSocket.Factory, url: String): Flow<MarketUpdate> = callbackFlow {
    val gson = Gson()
    val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = try { gson.fromJson(text, MarketEvent::class.java) } catch (_: JsonParseException) { null }
            when (event?.type) {
                "market.snapshot" -> {
                    val stocks = event.stocks
                    if (!stocks.isNullOrEmpty() && stocks.all { !it.ticker.isNullOrBlank() && !it.status.isNullOrBlank() }) {
                        trySend(MarketUpdate.Snapshot(stocks, event.checkedAt))
                    } else trySend(MarketUpdate.Unavailable)
                }
                "market.error" -> trySend(MarketUpdate.Unavailable)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
            close(IOException("Market stream closed"))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            close(IOException("Market stream closed"))
        }

        override fun onFailure(webSocket: WebSocket, failure: Throwable, response: Response?) {
            response?.close()
            close(IOException("Market stream disconnected", failure))
        }
    })
    awaitClose { socket.cancel() }
}.conflate().retryWhen { cause, attempt ->
    if (cause !is IOException) return@retryWhen false
    emit(MarketUpdate.Reconnecting)
    delay(minOf(30_000L, 1000L * (1L shl attempt.coerceAtMost(5).toInt())))
    true
}
