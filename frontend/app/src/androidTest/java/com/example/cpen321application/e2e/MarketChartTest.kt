package com.example.cpen321application.e2e

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.cpen321application.ui.market.MarketSparkline
import com.example.cpen321application.ui.market.data.MarketPricePoint
import org.junit.Rule
import org.junit.Test

// Component rendering tests with explicit test fixtures, not live integration evidence.
class MarketChartTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun timestampedPointsRenderAnAccessibleLineIncludingFlatPrices() {
        compose.setContent {
            MarketSparkline(listOf(
                MarketPricePoint("2026-10-21T04:00:00Z", 200.0),
                MarketPricePoint("2026-10-23T04:00:00Z", 200.0),
            ), Color.Green, "MSFT")
        }
        compose.onNode(hasContentDescription("2 real price points", substring = true)).assertIsDisplayed()
        compose.onNode(hasContentDescription("MSFT price history", substring = true)).assertExists()
        compose.onNodeWithText("Oct 21").assertExists()
        compose.onNodeWithText("Oct 23").assertExists()
    }

    @Test
    fun duplicateOrInvalidPointsDoNotFabricateALine() {
        compose.setContent {
            MarketSparkline(listOf(
                MarketPricePoint("2026-10-23T04:00:00Z", 200.0),
                MarketPricePoint("2026-10-23T04:00:00Z", 200.0),
                MarketPricePoint("not-a-date", 201.0),
                MarketPricePoint("2026-10-24T04:00:00Z", -1.0),
            ), Color.Green)
        }
        compose.onAllNodes(hasContentDescription("AAPL price history", substring = true)).assertCountEquals(0)
    }
}
