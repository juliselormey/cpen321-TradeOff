package com.example.cpen321application.ui.market.data

import com.google.gson.Gson
import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class MarketSnapshot(
    val type: String,
    val revision: Long,
    val checkedAt: String,
    val stocks: List<MarketStock>,
)

class MarketStream(private val client: WebSocket.Factory, private val url: String) {
    fun snapshots() = callbackFlow {
        val gson = Gson()
        var latestRevision = -1L
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val snapshot = gson.fromJson(text, MarketSnapshot::class.java)
                    require(snapshot.type == "market.snapshot" && snapshot.stocks.isNotEmpty())
                    require(snapshot.stocks.any { it.ticker == "AAPL" })
                    require(snapshot.stocks.all { !it.ticker.isNullOrBlank() && !it.status.isNullOrBlank() })
                    if (snapshot.revision >= latestRevision) {
                        latestRevision = snapshot.revision
                        trySend(snapshot)
                    }
                } catch (error: Exception) {
                    close(IOException("Could not read live market data", error))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                response?.close()
                close(IOException("Market connection lost", t))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                close(IOException("Market connection closed"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close(IOException("Market connection closed"))
            }
        })
        awaitClose { socket.cancel() }
    }.buffer(Channel.CONFLATED)
}
