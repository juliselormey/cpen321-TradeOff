package com.example.cpen321application.ui.trade


internal const val MAX_ORDER_AMOUNT = 1_000_000.0
private const val MAX_DECIMAL_PLACES = 4

/** Match the server's positive, finite Decimal(18,4) input contract. */
internal fun validOrderAmount(text: String): Boolean {
    val value = text.toDoubleOrNull() ?: return false
    if (!value.isFinite() || value <= 0 || value > MAX_ORDER_AMOUNT) return false
    return text.toBigDecimalOrNull()?.stripTrailingZeros()?.scale()?.let { it <= MAX_DECIMAL_PLACES } == true
}

internal data class DemoTradeResult(val cash: Double, val owned: Double)

/** Explicit demo execution, used only when no live API was supplied. */
internal fun executeDemoOrder(order: SubmittedOrder, price: Double, cash: Double, owned: Double): DemoTradeResult {
    val direction = if (order.side == "BUY") 1 else -1
    val total = order.quantity * (order.limitPrice ?: price)
    return DemoTradeResult(cash - direction * total,
        if (order.type == "MARKET") owned + direction * order.quantity else owned)
}
