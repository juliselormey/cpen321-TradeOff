package com.example.cpen321application.ui.portfolio

import java.time.LocalDate

// Feature-local display models. Replace the mock source when portfolio APIs exist.
internal data class PortfolioHolding(
    val symbol: String,
    val companyName: String,
    val shares: Double,
    val currentValue: Double,
    val costBasis: Double,
) {
    val profitLoss: Double get() = currentValue - costBasis
    val returnPercent: Double get() = profitLoss / costBasis * 100
}

internal enum class TransactionSide { Buy, Sell }

internal data class PortfolioTransaction(
    val id: String,
    val side: TransactionSide,
    val symbol: String,
    val shares: Double,
    val pricePerShare: Double,
    val date: LocalDate,
) {
    val total: Double get() = shares * pricePerShare
}

internal object PortfolioMockData {
    val holdings = listOf(
        PortfolioHolding("AAPL", "Apple Inc.", 25.4, 4012.30, 3600.12),
        PortfolioHolding("NVDA", "NVIDIA Corporation", 8.0, 3496.28, 2933.07),
        PortfolioHolding("MSFT", "Microsoft Corporation", 6.2, 2423.10, 2296.76),
        PortfolioHolding("TSLA", "Tesla, Inc.", 5.0, 1571.24, 1670.00),
    )
    const val availableCash = 1250.00
    val investedValue: Double get() = holdings.sumOf { it.currentValue }
    val totalValue: Double get() = investedValue + availableCash
    val totalProfitLoss: Double get() = holdings.sumOf { it.profitLoss }
    // Unrealized return on invested cost; available cash is excluded from the basis.
    val totalReturnPercent: Double get() = totalProfitLoss / holdings.sumOf { it.costBasis } * 100

    // Recent activity is a sample excerpt, not the complete holdings ledger.
    val transactions = listOf(
        PortfolioTransaction("txn-003", TransactionSide.Buy, "AAPL", 5.0, 182.20, LocalDate.of(2026, 10, 16)),
        PortfolioTransaction("txn-002", TransactionSide.Sell, "TSLA", 2.0, 242.15, LocalDate.of(2026, 10, 14)),
        PortfolioTransaction("txn-001", TransactionSide.Buy, "NVDA", 3.0, 830.50, LocalDate.of(2026, 10, 12)),
    )
}
