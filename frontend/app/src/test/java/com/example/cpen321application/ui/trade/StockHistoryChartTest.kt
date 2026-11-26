package com.example.cpen321application.ui.trade

import com.example.cpen321application.ui.market.data.MarketPricePoint
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class StockHistoryChartTest {
    @Test
    fun periodsUseObservationTimeSoClosedMarketsStillShowAvailableHistory() {
        val points = listOf(
            MarketPricePoint("2026-11-01T12:00:00Z", 100.0),
            MarketPricePoint("2026-11-20T12:00:00Z", 105.0),
            MarketPricePoint("2026-11-25T12:00:00Z", 110.0),
            MarketPricePoint("2026-11-25T12:00:04Z", 111.0),
        )
        assertEquals(listOf(110.0, 111.0), chartObservations(points, 1).map { it.price })
        assertEquals(listOf(105.0, 110.0, 111.0), chartObservations(points, 7).map { it.price })
        assertEquals(4, chartObservations(points, 30).size)
    }

    @Test
    fun invalidDataDoesNotBecomeFabricatedChartPointsAndDuplicateTimesUseLatestValue() {
        val points = listOf(
            MarketPricePoint("2026-11-25T12:00:04Z", 102.0),
            MarketPricePoint("bad timestamp", 50.0),
            MarketPricePoint("2026-11-25T12:00:00Z", 100.0),
            MarketPricePoint("2026-11-25T12:00:01Z", Double.NaN),
            MarketPricePoint("2026-11-25T12:00:02Z", -1.0),
            MarketPricePoint("2026-11-25T12:00:03Z", 0.0),
            MarketPricePoint("2026-11-25T12:00:04Z", 103.0),
        )
        val result = chartObservations(points, 1)
        assertEquals(listOf(100.0, 103.0), result.map { it.price })
        assertEquals(Instant.parse("2026-11-25T12:00:00Z").toEpochMilli(), result.first().time)
        assertTrue(chartObservations(emptyList(), 30).isEmpty())
    }
}
