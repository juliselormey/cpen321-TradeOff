package com.example.cpen321application.ui.trade

import com.example.cpen321application.*
import com.example.cpen321application.util.apiErrorMessage
import java.io.IOException
import retrofit2.Response

internal data class SubmittedOrder(val ticker: String, val side: String, val type: String, val quantity: Double, val limitPrice: Double?)
internal data class TradeAccountSnapshot(val cash: Double, val owned: Double, val available: Double, val orders: List<PendingOrderItem>)
internal class TradeRequestFailure(val status: Int, message: String) : IOException(message)

internal class TradeRepository(private val api: ApiService) {
    suspend fun account(ticker: String): TradeAccountSnapshot {
        val position = api.getPosition(ticker).requiredBody("Could not refresh your account. Please try again.")
        val pending = api.getPendingOrders().requiredBody("Could not refresh pending orders. Please try again.")
        return TradeAccountSnapshot(position.cashBalance, position.ownedQuantity, position.availableQuantity,
            pending.orders.filter { it.ticker.equals(ticker, ignoreCase = true) })
    }

    suspend fun execute(order: SubmittedOrder): ExecuteOrderResponse {
        val response = if (order.type == "LIMIT") {
            api.executeLimitOrder(ExecuteLimitOrderRequest(order.ticker, order.side, order.quantity, requireNotNull(order.limitPrice)))
        } else {
            api.executeMarketOrder(ExecuteMarketOrderRequest(order.ticker, order.side, order.quantity))
        }
        return response.requiredBody("Could not execute your order. Please refresh before trying again.")
    }

    suspend fun cancel(orderId: String) {
        api.cancelOrder(orderId).requiredBody("Failed to cancel order. Please refresh before trying again.")
    }
}

private fun <T> Response<T>.requiredBody(fallback: String): T {
    if (!isSuccessful) throw TradeRequestFailure(code(), apiErrorMessage(this, fallback))
    return body() ?: throw TradeRequestFailure(code(), "The server returned an incomplete response. Please refresh.")
}
