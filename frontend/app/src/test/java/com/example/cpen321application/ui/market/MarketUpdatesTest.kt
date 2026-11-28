package com.example.cpen321application.ui.market

import com.example.cpen321application.ui.market.data.*
import com.google.gson.Gson
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

/** Refactoring regression checks exercise the same stream used by Market and Trade. */
class MarketUpdatesTest {
    private class Socket(private val original: Request, val listener: WebSocketListener) : WebSocket {
        var cancelled = false
        override fun request() = original
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
        fun message(text: String) = listener.onMessage(this, text)
        fun snapshot() = message(Gson().toJson(MarketSnapshot("market.snapshot", 1, "now",
            initialMarketStocks().map { it.copy(currentPrice = "123", status = "ok") })))
    }
    private class Factory : WebSocket.Factory {
        val connections = Channel<Socket>(Channel.UNLIMITED)
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            Socket(request, listener).also { connections.trySend(it) }
    }

    @Test fun receivesServerSnapshotsAndClosesSubscriptionWithScreen() = runBlocking {
        withTimeout(5000) {
            val factory = Factory()
            val received = Channel<MarketSnapshot>(Channel.UNLIMITED)
            val job = launch { MarketStream(factory, "ws://localhost/api/market/events").snapshots().collect { received.send(it) } }
            val socket = factory.connections.receive()
            socket.snapshot()
            assertEquals("123", received.receive().stocks.first().currentPrice)
            job.cancelAndJoin()
            assertTrue(socket.cancelled)
        }
    }

    @Test fun rejectsMalformedSnapshotsAndClosesTheSocket() = runBlocking {
        withTimeout(5000) {
            val factory = Factory()
            val failure = async {
                runCatching { MarketStream(factory, "ws://localhost/api/market/events").snapshots().first() }.exceptionOrNull()
            }
            val socket = factory.connections.receive()
            socket.message("""{"type":"market.snapshot","stocks":[{"ticker":"AAPL"}]}""")
            assertTrue(failure.await() is IOException)
            assertTrue(socket.cancelled)
        }
    }

    @Test fun reconnectsAfterDroppedConnectionAndAppliesPushWithoutRecurringHttp() = runBlocking {
        withTimeout(5000) {
            val factory = Factory()
            var recoveryCalls = 0
            val model = MarketViewModel(MarketRepository(object : MarketApiService {
                override suspend fun getStocks(): MarketResponse {
                    recoveryCalls++
                    return MarketResponse(initialMarketStocks())
                }
            }))
            val job = launch { model.observeMarket(MarketStream(factory, "ws://localhost/api/market/events")) }
            try {
                val first = factory.connections.receive()
                first.listener.onFailure(first, IOException("offline"), null)
                val second = factory.connections.receive()
                assertTrue(first.cancelled)
                second.snapshot()
                model.state.first { it.stocks.first().currentPrice == "123" }
                assertEquals(1, recoveryCalls)
                assertNull(model.state.value.error)
            } finally { job.cancelAndJoin() }
        }
    }
}
