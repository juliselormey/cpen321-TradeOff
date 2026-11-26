package com.example.cpen321application.ui.market

import com.example.cpen321application.ui.market.data.*
import com.google.gson.Gson
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class MarketStreamTest {
    private class Connection : WebSocket.Factory {
        lateinit var listener: WebSocketListener
        var cancelled = false
        val socket = object : WebSocket {
            override fun request() = Request.Builder().url("http://localhost/").build()
            override fun queueSize() = 0L
            override fun send(text: String) = true
            override fun send(bytes: ByteString) = true
            override fun close(code: Int, reason: String?) = true
            override fun cancel() { cancelled = true }
        }
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.listener = listener
            return socket
        }
        fun snapshot(revision: Long, price: String) = listener.onMessage(socket, Gson().toJson(
            MarketSnapshot("market.snapshot", revision, "2026-11-25T12:00:00Z",
                initialMarketStocks().map { it.copy(currentPrice = price, status = "ok") })
        ))
    }

    @Test
    fun pushedPricesUpdateStateWithoutHttpPollingAndCancellationClosesSocket() = runBlocking {
        val connection = Connection()
        var httpCalls = 0
        val model = MarketViewModel(MarketRepository(object : MarketApiService {
            override suspend fun getStocks(): MarketResponse { httpCalls++; return MarketResponse(initialMarketStocks()) }
        }))
        val job = launch { model.observeMarket(MarketStream(connection, "ws://localhost/api/market/events")) }
        try {
            yield(); yield()
            connection.snapshot(2, "200")
            withTimeout(2_000) { model.state.first { it.stocks.first().currentPrice == "200" } }
            connection.snapshot(1, "100") // Older frames cannot overwrite a newer snapshot.
            yield(); yield()
            assertEquals("200", model.state.value.stocks.first().currentPrice)
            connection.snapshot(3, "201")
            withTimeout(2_000) { model.state.first { it.stocks.first().currentPrice == "201" } }
            assertEquals(0, httpCalls)
        } finally { job.cancelAndJoin() }
        assertTrue(connection.cancelled)
    }

    @Test
    fun delayedHttpFailureCannotOverwriteNewerLivePrices() = runBlocking {
        val connection = Connection()
        val response = CompletableDeferred<MarketResponse>()
        val model = MarketViewModel(MarketRepository(object : MarketApiService {
            override suspend fun getStocks() = response.await()
        }))
        val refresh = launch(start = CoroutineStart.UNDISPATCHED) { model.refresh() }
        val live = launch { model.observeMarket(MarketStream(connection, "ws://localhost/api/market/events")) }
        try {
            yield(); yield()
            connection.snapshot(1, "202")
            withTimeout(2_000) { model.state.first { it.stocks.first().currentPrice == "202" } }
            response.completeExceptionally(IOException("late REST failure"))
            refresh.join()
            assertEquals("202", model.state.value.stocks.first().currentPrice)
            assertEquals("ok", model.state.value.stocks.first().status)
            assertNull(model.state.value.error)
        } finally { refresh.cancelAndJoin(); live.cancelAndJoin() }
    }

    @Test
    fun disconnectRetainsPricesAndReconnectReceivesFreshSnapshot() = runBlocking {
        val connection = Connection()
        var httpCalls = 0
        val stocks = initialMarketStocks().map { it.copy(currentPrice = "200", status = "ok") }
        val model = MarketViewModel(MarketRepository(object : MarketApiService {
            override suspend fun getStocks(): MarketResponse { httpCalls++; return MarketResponse(stocks) }
        }))
        val job = launch { model.observeMarket(MarketStream(connection, "ws://localhost/api/market/events")) }
        try {
            yield(); yield()
            connection.snapshot(5, "200")
            withTimeout(2_000) { model.state.first { it.stocks.first().currentPrice == "200" } }
            val oldListener = connection.listener
            oldListener.onFailure(connection.socket, IOException("offline"), null)
            withTimeout(2_000) { model.state.first { it.error != null } }
            assertEquals("200", model.state.value.stocks.first().currentPrice)
            assertEquals("stale", model.state.value.stocks.first().status)
            assertEquals(1, httpCalls)
            withTimeout(3_000) { while (connection.listener === oldListener) delay(10) }
            // Revision may restart when the backend restarts.
            connection.snapshot(0, "202")
            withTimeout(2_000) { model.state.first { it.stocks.first().currentPrice == "202" } }
            assertNull(model.state.value.error)
        } finally { job.cancelAndJoin() }
    }
}
