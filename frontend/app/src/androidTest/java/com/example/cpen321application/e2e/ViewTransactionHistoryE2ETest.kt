package com.example.cpen321application.e2e

import androidx.compose.ui.test.*
import com.example.cpen321application.TradeHistoryItemResponse
import com.example.cpen321application.ui.portfolio.TransactionHistoryIds
import com.example.cpen321application.util.SessionManager
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale

/** View Transaction History: actual BUY/SELL records, real UI preparation and read-only viewing. */
class ViewTransactionHistoryE2ETest : TradingE2ESupport() {
    private fun openPortfolioFromMarket() {
        compose.onNode(hasText("Portfolio") and hasClickAction()).performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("portfolio-list").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun displayMoney(value: Double) = NumberFormat.getCurrencyInstance(Locale.US).format(value)

    private fun assertHistory(expected: List<TradeHistoryItemResponse>) {
        // The header is in a LazyColumn: scroll to it only after its real response loads.
        portfolioNode(hasTestTag("transaction-history"))
        val ids = compose.onNodeWithTag("transaction-history").fetchSemanticsNode().config[TransactionHistoryIds]
        assertEquals("Only the completed trades, newest first, may appear in history", expected.map { it.id }, ids)
        assertEquals("No duplicate history entries", ids.size, ids.toSet().size)
        for (trade in expected) {
            val tag = "transaction-${trade.id}"
            portfolioNode(hasTestTag(tag))
            val card = compose.onNodeWithTag(tag)
            card.assertTextContains("${if (trade.type == "BUY") "Bought" else "Sold"} ${trade.ticker}")
            card.assertTextContains(trade.createdAt.take(10))
            val count = NumberFormat.getNumberInstance(Locale.US).apply { maximumFractionDigits = 4 }.format(trade.quantity)
            card.assertTextContains("$count shares @ ${displayMoney(trade.pricePerShare)}")
            card.assertTextContains("${if (trade.type == "BUY") "−" else "+"}${displayMoney(trade.totalAmount)}")
        }
        // With two records both cards fit on screen; verify actual visual order too.
        val newer = compose.onNodeWithTag("transaction-${expected.first().id}").fetchSemanticsNode().boundsInRoot.top
        val older = compose.onNodeWithTag("transaction-${expected.last().id}").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Newer transaction must be above the older transaction", newer < older)
    }

    @Test fun historyShowsOnlyCompletedTradesWithCorrectDetailsAndPersistsWithoutMutations() {
        val auth = "Bearer " + requireNotNull(SessionManager.getToken(compose.activity))
        val userId = runBlocking {
            val result = api.profile(auth)
            check(result.isSuccessful) { "Profile evidence HTTP ${result.code()}" }
            requireNotNull(result.body()).user.id
        }

        // Empty-history alternative: the section is absent, with no fabricated transaction.
        val empty = evidence()
        openPortfolioFromMarket()
        portfolioNode(hasText("No stock holdings yet. Use the Market tab to place trades!"))
        compose.onNodeWithTag("transaction-history").assertDoesNotExist()
        compose.onNodeWithText("Recent Activity").assertDoesNotExist()
        assertEquals("Viewing empty history cannot mutate account data", empty, evidence())

        // Automated preparation, independent of any other test: buy three and sell one via UI.
        compose.activityRule.scenario.recreate()
        quote()
        openTrade()
        chooseOrder("MARKET"); quantity("3")
        submit("BUY", "MARKET BUY filled")
        chooseOrder("MARKET"); quantity("1")
        submit("SELL", "MARKET SELL filled")

        // Prepare both a cancelled and a still-pending order, neither is a completed trade.
        chooseOrder("LIMIT"); quantity("1"); limit("1")
        submit("BUY", "LIMIT BUY placed")
        val cancelledId = evidence().getAsJsonArray("orders").single {
            it.asJsonObject.get("userId").asString == userId && it.asJsonObject.get("status").asString == "PENDING"
        }.asJsonObject.get("id").asString
        cancelOnTrade()
        assertOrderStatus(cancelledId, "CANCELLED")
        chooseOrder("LIMIT"); quantity("2"); limit("1")
        submit("BUY", "LIMIT BUY placed")

        // Viewing assertions begin only after preparation is complete.
        val beforeViewing = evidence()
        val gson = Gson()
        val trades = beforeViewing.getAsJsonArray("trades").map { it.asJsonObject }
            .filter { it.get("userId").asString == userId }
            .map { gson.fromJson(it, TradeHistoryItemResponse::class.java) }
            .sortedByDescending { Instant.parse(it.createdAt) }
        assertEquals(2, trades.size)
        assertEquals(listOf("SELL", "BUY"), trades.map { it.type })
        val orders = beforeViewing.getAsJsonArray("orders").map { it.asJsonObject }
            .filter { it.get("userId").asString == userId }
        assertEquals(1, orders.count { it.get("status").asString == "PENDING" })
        assertEquals(1, orders.count { it.get("status").asString == "CANCELLED" })

        // Success: open Portfolio, inspect every actual completed record and its fields.
        showPortfolio()
        assertHistory(trades)
        assertEquals("Viewing history must not change cash, holdings, orders or trades", beforeViewing, evidence())

        // Persistence: leave/recreate/reopen, then require identical records without duplicates.
        compose.activityRule.scenario.recreate()
        // The restored route may already be Portfolio; use the real Market navigation to reopen it.
        openTrade()
        showPortfolio()
        assertHistory(trades)
        assertEquals("Reopening history must not mutate account data", beforeViewing, evidence())
        // Cleanup in the shared support resets this disposable account and preserves Favorites.
        // No forced network/provider failure, second-account login or >100-record history is claimed.
    }
}
