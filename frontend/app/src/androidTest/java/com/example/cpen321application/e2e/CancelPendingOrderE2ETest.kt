package com.example.cpen321application.e2e

import androidx.compose.ui.test.*
import com.example.cpen321application.HoldingItemResponse
import com.example.cpen321application.PendingOrderItem
import com.example.cpen321application.PortfolioSummaryResponse
import com.example.cpen321application.TradeHistoryItemResponse
import com.example.cpen321application.util.SessionManager
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Cancel a pending BUY from Trade and a pending SELL from Portfolio, with actual reserves. */
class CancelPendingOrderE2ETest : TradingE2ESupport() {
    private var accountUserId: String? = null

    /** Read actual persisted state, not a market-price-dependent portfolio valuation.
     * This is test evidence only: the app still uses its ordinary production APIs.
     */
    override fun snapshot(): PortfolioSummaryResponse {
        val userId = accountUserId ?: runBlocking {
            val token = requireNotNull(SessionManager.getToken(compose.activity))
            val response = api.profile("Bearer $token")
            check(response.isSuccessful) { "Profile evidence HTTP ${response.code()}" }
            requireNotNull(response.body()).user.id
        }.also { accountUserId = it }
        val data = evidence()
        fun rows(name: String) = data.getAsJsonArray(name).map { it.asJsonObject }
            .filter { it.get("userId").asString == userId }
        val account = rows("accounts").single()
        val gson = Gson()
        val pending = rows("orders").filter { it.get("status").asString == "PENDING" }
            .map { gson.fromJson(it, PendingOrderItem::class.java) }.sortedBy { it.id }
        val reserved = pending.filter { it.side == "BUY" }.fold(java.math.BigDecimal.ZERO) { total, order ->
            total + order.quantity.toBigDecimal() * requireNotNull(order.limitPrice).toBigDecimal()
        }
        return PortfolioSummaryResponse(
            cashBalance = account.get("cashBalance").asDouble,
            startingBalance = account.get("startingBalance").asDouble,
            holdings = rows("positions").map { gson.fromJson(it, HoldingItemResponse::class.java) }
                .filter { it.quantity > 0 }.sortedBy { it.ticker },
            recentTrades = rows("trades").map { gson.fromJson(it, TradeHistoryItemResponse::class.java) }
                .sortedBy { it.id },
            pendingOrders = pending,
            reservedCash = reserved.toDouble(),
        )
    }

    @Test fun cancellationReleasesBuyCashAndSellSharesAndPersistsOnBothScreens() {
        val initial = snapshot()
        quote()
        openTrade()

        // BUY preparation uses the actual order form, not a seeded order response.
        val buy = pending("BUY", 2)
        val beforeCancel = snapshot()
        assertEquals(2.0, beforeCancel.reservedCash, 0.0001)
        assertEquals(initial.cashBalance - 2, beforeCancel.cashBalance, 0.0001)

        // Success: Trade cancellation returns exactly the cash reserve, with no trade/holding.
        cancelOnTrade()
        assertOrderStatus(buy.id, "CANCELLED")
        val cancelled = snapshot()
        assertEquals(initial.cashBalance, cancelled.cashBalance, 0.0001)
        assertEquals(0.0, cancelled.reservedCash, 0.0001)
        assertTrue(cancelled.pendingOrders.isEmpty())
        assertEquals(initial.holdings, cancelled.holdings)
        assertEquals(initial.recentTrades, cancelled.recentTrades)
        showPortfolio()
        portfolioNode(hasTestTag("portfolio-cash"))
        compose.onNodeWithTag("portfolio-cash").assertTextEquals("$10,000.00")
        compose.onNodeWithText("BUY 2 MSFT").assertDoesNotExist()

        // SELL preparation: buy actual shares, then reserve one for a distant limit sell.
        compose.activityRule.scenario.recreate(); openTrade()
        val owned = buyShares(2)
        val sell = pending("SELL", 1)
        assertEquals(1.0, position().availableQuantity, 0.0001)
        val beforeSellCancel = snapshot()
        assertEquals(owned.cashBalance, beforeSellCancel.cashBalance, 0.0001)
        assertEquals(owned.recentTrades, beforeSellCancel.recentTrades)

        // Success: cancel from Portfolio, release shares, never add sale proceeds.
        showPortfolio()
        portfolioNode(hasText("SELL 1 MSFT"))
        portfolioNode(hasText("Cancel"))
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(30_000) { snapshot().pendingOrders.isEmpty() }
        assertOrderStatus(sell.id, "CANCELLED")
        val afterSellCancel = snapshot()
        assertEquals(beforeSellCancel.cashBalance, afterSellCancel.cashBalance, 0.0001)
        assertEquals(beforeSellCancel.holdings, afterSellCancel.holdings)
        assertEquals(beforeSellCancel.recentTrades, afterSellCancel.recentTrades)
        assertEquals(2.0, position().availableQuantity, 0.0001)

        // Reopen both screens: compare persisted state without needing a fresh Alpaca valuation.
        compose.activityRule.scenario.recreate(); openTrade()
        compose.onNodeWithText("Active Pending Orders").assertDoesNotExist()
        assertEquals(afterSellCancel, snapshot())
        showPortfolio()
        portfolioNode(hasTestTag("portfolio-cash"))
        assertEquals(afterSellCancel, snapshot())
        // Duplicate cancellation / fill-vs-cancel races are backend cases, not forced UI events.
    }
}
