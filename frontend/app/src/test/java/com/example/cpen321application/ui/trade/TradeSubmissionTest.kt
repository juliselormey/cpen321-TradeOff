package com.example.cpen321application.ui.trade

import com.example.cpen321application.*
import com.example.cpen321application.ui.market.data.MarketApiService
import com.example.cpen321application.ui.market.data.MarketResponse
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class TradeSubmissionTest {
    private val market = object : MarketApiService { override suspend fun getStocks() = MarketResponse(emptyList()) }

    @Test fun doubleTapSendsOneImmutableRequestAndUnlocksAfterSuccess() = runBlocking {
        val requests = mutableListOf<ExecuteMarketOrderRequest>()
        lateinit var reply: Continuation<Response<ExecuteOrderResponse>>
        val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
            when (method.name) {
                "executeMarketOrder" -> {
                    requests.add(args[0] as ExecuteMarketOrderRequest)
                    @Suppress("UNCHECKED_CAST")
                    reply = args.last() as Continuation<Response<ExecuteOrderResponse>>
                    COROUTINE_SUSPENDED
                }
                "getPosition" -> Response.success(UserPositionResponse(9600.0, 2.0, 200.0, 2.0))
                "getPendingOrders" -> Response.success(PendingOrdersResponse(emptyList()))
                else -> error(method.name)
            }
        } as ApiService
        val state = TradeState("AAPL", { true }, api, market, this)
        state.currentMarketPrice = 200.0
        state.updateQuantity("2")
        assertFalse(state.accountLoaded)
        assertFalse(state.isBuyEnabled) // A quote cannot unlock an account that has not loaded.
        assertEquals(0.0, state.availableCash, 0.0)
        state.refreshAccount()
        state.submit("BUY")
        assertTrue(state.isSubmitting)
        state.updateQuantity("99")
        state.selectOrderType("LIMIT")
        state.submit("BUY")
        assertEquals(1, requests.size)
        assertEquals(2.0, requests.single().quantity, 0.0)
        assertEquals("2", state.quantityText)
        assertFalse(state.isBuyEnabled)
        reply.resume(Response.success(ExecuteOrderResponse(true, "Filled", "FILLED", 9600.0, 2.0, 2.0)))
        yield()
        assertFalse(state.isSubmitting)
        assertEquals("", state.quantityText)
        assertEquals(9600.0, state.availableCash, 0.001)
    }

    @Test fun expiredLiveSessionNeverFallsBackToDemoTrading() = runBlocking {
        var calls = 0
        val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, _, _ ->
            calls++; error("No live request should be made after expiry")
        } as ApiService
        val state = TradeState("AAPL", { false }, api, market, this)
        state.currentMarketPrice = 100.0
        state.updateQuantity("1")
        assertFalse(state.isBuyEnabled)
        state.submit("BUY")
        assertEquals(0, calls)
        assertFalse(state.accountLoaded)
        assertEquals(0.0, state.availableCash, 0.0)
        assertNull(state.successMessage)
    }

    @Test fun amountValidationMatchesServerPrecisionAndLimits() {
        listOf("0", "-1", "NaN", "Infinity", "1000001", "0.00001", "9".repeat(400)).forEach {
            assertFalse(it, validOrderAmount(it))
        }
        listOf("1", "0.0001", "1000000", "1.00000").forEach { assertTrue(it, validOrderAmount(it)) }
    }
}
