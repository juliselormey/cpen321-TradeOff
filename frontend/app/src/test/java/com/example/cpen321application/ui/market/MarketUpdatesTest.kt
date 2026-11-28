package com.example.cpen321application.ui.market

import com.example.cpen321application.ui.market.data.MarketApiService
import com.example.cpen321application.ui.market.data.MarketRepository
import com.example.cpen321application.ui.market.data.MarketResponse
import com.example.cpen321application.ui.market.data.initialMarketStocks
import java.io.IOException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

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
    }
    private class Factory : WebSocket.Factory {
        val connections = Channel<Socket>(Channel.UNLIMITED)
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            Socket(request, listener).also { connections.trySend(it) }
    }

    @Test fun receivesServerSnapshotsAndClosesSubscriptionWithScreen() = runBlocking {
        withTimeout(5000) {
            val factory = Factory()
            val updates = Channel<MarketUpdate>(Channel.UNLIMITED)
            val job = launch { marketUpdates(factory, "ws://localhost/api/market/events").collect { updates.send(it) } }
            val socket = factory.connections.receive()
            socket.message("invalid-json")
            socket.message("""{"type":"market.snapshot","checkedAt":"now","stocks":[{"ticker":"AAPL","status":"ok","currentPrice":"123"}]}""")
            val snapshot = updates.receive() as MarketUpdate.Snapshot
            assertEquals("123", snapshot.stocks.single().currentPrice)
            socket.message("""{"type":"market.error"}""")
            assertEquals(MarketUpdate.Unavailable, updates.receive())
            job.cancelAndJoin()
            assertTrue(socket.cancelled)
        }
    }

    @Test fun reconnectsAfterDroppedConnection() = runBlocking {
        withTimeout(5000) {
            val factory = Factory()
            val updates = Channel<MarketUpdate>(Channel.UNLIMITED)
            val job = launch { marketUpdates(factory, "ws://localhost/api/market/events").collect { updates.send(it) } }
            val first = factory.connections.receive()
            first.listener.onFailure(first, IOException("offline"), null)
            assertEquals(MarketUpdate.Reconnecting, updates.receive())
            val second = factory.connections.receive()
            assertTrue(first.cancelled)
            job.cancelAndJoin()
            assertTrue(second.cancelled)
        }
    }

    @Test fun viewModelAppliesPushWithoutFetchingAnHttpSnapshot() {
        val model = MarketViewModel(MarketRepository(object : MarketApiService {
            override suspend fun getStocks(): MarketResponse = error("Push must not trigger HTTP polling")
        }))
        val stocks = initialMarketStocks().map { it.copy(currentPrice = "123", status = "ok") }
        model.onMarketUpdate(MarketUpdate.Snapshot(stocks, "now"))
        assertEquals("123", model.state.value.stocks.first().currentPrice)
        model.onMarketUpdate(MarketUpdate.Reconnecting)
        assertEquals("stale", model.state.value.stocks.first().status)
        model.onMarketUpdate(MarketUpdate.Snapshot(stocks, "later"))
        assertNull(model.state.value.error)
        assertEquals("later", model.state.value.checkedAt)
    }
}
