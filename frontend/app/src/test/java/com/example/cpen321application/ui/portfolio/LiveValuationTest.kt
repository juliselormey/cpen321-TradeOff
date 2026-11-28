package com.example.cpen321application.ui.portfolio

import com.example.cpen321application.*
import com.example.cpen321application.ui.market.*
import com.example.cpen321application.ui.market.data.*
import com.example.cpen321application.ui.social.*
import com.example.cpen321application.ui.trade.TradeState
import com.google.gson.Gson
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

class LiveValuationTest {
    private class Socket(val original: Request, val listener: WebSocketListener) : WebSocket {
        var cancelled = false
        override fun request() = original
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
    }
    private class Sockets : WebSocket.Factory {
        val connections = mutableListOf<Socket>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            Socket(request, listener).also { connections.add(it) }
        fun publish(revision: Long, price: Double) {
            val json = Gson().toJson(MarketSnapshot("market.snapshot", revision, "now",
                initialMarketStocks().map { it.copy(currentPrice = price.toString(), status = "ok") }))
            connections.filterNot { it.cancelled }.forEach { it.listener.onMessage(it, json) }
        }
    }
    private suspend fun until(condition: () -> Boolean) = withTimeout(4000) { while (!condition()) delay(5) }

    @Test fun onePriceEventUpdatesMarketTradePortfolioAndLeaderboardAndReconnectRecovers() = runBlocking {
        val sockets = Sockets()
        val stream = MarketStream(sockets, "ws://localhost/api/market/events")
        var serverPrice = 200.0
        var calls = 0
        val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, _ ->
            check(method.name == "getPortfolio")
            calls++
            Response.success(Gson().fromJson("""{"cashBalance":9000,"startingBalance":10000,"reservedCash":1000,
                "totalValue":${10000 + serverPrice},"profitLoss":$serverPrice,"returnPercent":${serverPrice / 100},
                "holdings":[{"ticker":"AAPL","quantity":1,"averageCost":100,"price":$serverPrice,"value":$serverPrice}],
                "allocation":[],"pendingOrders":[],"recentTrades":[]}""", PortfolioSummaryResponse::class.java))
        } as ApiService
        val friends = Proxy.newProxyInstance(FriendsApi::class.java.classLoader, arrayOf(FriendsApi::class.java)) { _, method, _ ->
            check(method.name == "leaderboard")
            Response.success(LeaderboardResponse(listOf(LeaderboardEntry(
                FriendProfile("me", "Me", null), true, "9000", serverPrice / 100, (10000 + serverPrice).toString()))))
        } as FriendsApi
        val marketApi = object : MarketApiService {
            override suspend fun getStocks(): MarketResponse = throw IOException("Disconnected")
        }
        val market = MarketViewModel(MarketRepository(marketApi))
        val trade = TradeState("AAPL", { false }, null, marketApi, this)
        val portfolio = PortfolioState({ true }, api)
        val leaderboard = LeaderboardLoader(friends, { "token" })
        val jobs = listOf(
            launch { market.observeMarket(stream) },
            launch { market.state.collect { trade.applyMarketStock(it.stocks.first(), it.error != null) } },
            launch { portfolio.observeMarket(stream) },
            launch { leaderboard.observeMarket(stream) },
        )
        try {
            until { sockets.connections.size == 3 }
            sockets.publish(1, serverPrice)
            until { portfolio.totalPortfolioValue == 10200.0 && leaderboard.state.value.loaded }
            serverPrice = 210.0
            sockets.publish(2, serverPrice)
            until { trade.currentMarketPrice == 210.0 && portfolio.totalPortfolioValue == 10210.0 &&
                leaderboard.state.value.entries.firstOrNull()?.totalPortfolioValue == "10210.0" }
            assertEquals("210.0", market.state.value.stocks.first().currentPrice)
            assertEquals(210.0, portfolio.evaluatedHoldings.single().price!!, 0.0)
            assertEquals(9000.0, portfolio.cashBalance!!, 0.0)
            assertEquals(1000.0, portfolio.summary!!.reservedCash, 0.0)
            val settledCalls = calls
            delay(100)
            assertEquals("No recurring valuation polling between market events", settledCalls, calls)
            sockets.connections.toList().forEach { it.listener.onFailure(it, IOException("offline"), null) }
            until { portfolio.valuationUnavailable && leaderboard.state.value.error != null }
            assertNull(portfolio.totalPortfolioValue)
            assertNull(leaderboard.state.value.entries.firstOrNull()?.totalPortfolioValue)
            until { sockets.connections.size == 6 }
            serverPrice = 220.0
            sockets.publish(0, serverPrice) // Server revision may restart.
            until { portfolio.totalPortfolioValue == 10220.0 && trade.currentMarketPrice == 220.0 }
            assertNull(portfolio.errorMessage)
        } finally { jobs.forEach { it.cancelAndJoin() } }
        assertTrue(sockets.connections.all { it.cancelled })
    }

    @Test fun manualResponseArrivingAfterDisconnectCannotRestoreOldValues() = runBlocking {
        val sockets = Sockets()
        lateinit var reply: Continuation<Response<PortfolioSummaryResponse>>
        val called = CompletableDeferred<Unit>()
        val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
            check(method.name == "getPortfolio")
            @Suppress("UNCHECKED_CAST")
            val continuation = args.last() as Continuation<Response<PortfolioSummaryResponse>>
            reply = continuation
            called.complete(Unit)
            COROUTINE_SUSPENDED
        } as ApiService
        val state = PortfolioState({ true }, api)
        val observer = launch { state.observeMarket(MarketStream(sockets, "ws://localhost/api/market/events")) }
        val manual = launch { state.loadPortfolioData() }
        try {
            called.await()
            until { sockets.connections.size == 1 }
            val socket = sockets.connections.single()
            socket.listener.onFailure(socket, IOException("offline"), null)
            until { state.valuationUnavailable }
            reply.resume(Response.success(Gson().fromJson("""{"totalValue":12345}""", PortfolioSummaryResponse::class.java)))
            manual.join()
            assertNull(state.totalPortfolioValue)
            assertNull(state.summary)
            assertNotNull(state.errorMessage)
        } finally { manual.cancelAndJoin(); observer.cancelAndJoin() }
    }

    @Test fun rapidSnapshotsCancelObsoleteValuationsWithoutOverlappingRequests() = runBlocking {
        val sockets = Sockets()
        var started = 0
        var cancelled = 0
        var active = 0
        var maxActive = 0
        var completed = 0
        val job = launch {
            observeMarketRefreshes(MarketStream(sockets, "ws://localhost/api/market/events"), {}, {}, {
                started++; active++; maxActive = maxOf(maxActive, active)
                try {
                    if (started == 1) awaitCancellation()
                    completed++
                } catch (e: CancellationException) { cancelled++; throw e }
                finally { active-- }
            })
        }
        try {
            until { sockets.connections.size == 1 }
            sockets.publish(1, 200.0)
            until { started == 1 }
            sockets.publish(2, 201.0)
            sockets.publish(3, 202.0)
            until { completed > 0 }
            assertEquals(1, maxActive)
            assertEquals(1, cancelled)
            assertTrue(started <= 3)
        } finally { job.cancelAndJoin() }
    }
}
