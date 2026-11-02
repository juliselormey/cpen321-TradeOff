package com.example.cpen321application

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

data class GoogleAuthRequest(val idToken: String)
data class GoogleAuthResponse(val accessToken: String)

// Trade & Order API Models
data class UserPositionResponse(
    val cashBalance: Double,
    val ownedQuantity: Double,
    val averageCost: Double
)

data class ExecuteOrderRequest(
    val ticker: String,
    val side: String, // "BUY" or "SELL"
    val type: String = "MARKET", // "MARKET" or "LIMIT", default is MARKET
    val quantity: Double,
    val pricePerShare: Double,
    val limitPrice: Double? = null
)

data class ExecuteMarketOrderRequest(
    val ticker: String,
    val side: String, // "BUY" or "SELL"
    val quantity: Double,
    val pricePerShare: Double
)

data class ExecuteLimitOrderRequest(
    val ticker: String,
    val side: String, // "BUY" or "SELL"
    val quantity: Double,
    val pricePerShare: Double,
    val limitPrice: Double
)

data class ExecuteOrderResponse(
    val success: Boolean,
    val message: String,
    val status: String, // "FILLED" or "PENDING"
    val cashBalance: Double,
    val ownedQuantity: Double
)

data class PendingOrderItem(
    val id: String,
    val ticker: String,
    val side: String,
    val type: String,
    val status: String,
    val quantity: Double,
    val limitPrice: Double?,
    val createdAt: String
)

data class PendingOrdersResponse(
    val orders: List<PendingOrderItem>
)

data class CancelOrderResponse(
    val success: Boolean,
    val message: String
)

interface ApiService {
    @POST("/api/auth/google")
    suspend fun loginWithGoogle(@Body request: GoogleAuthRequest): Response<GoogleAuthResponse>

    @GET("/api/trades/position/{ticker}")
    suspend fun getPosition(@Path("ticker") ticker: String): Response<UserPositionResponse>

    @POST("/api/trades/execute")
    suspend fun executeTrade(@Body request: ExecuteOrderRequest): Response<ExecuteOrderResponse>

    @POST("/api/trades/execute/market")
    suspend fun executeMarketOrder(@Body request: ExecuteMarketOrderRequest): Response<ExecuteOrderResponse>

    @POST("/api/trades/execute/limit")
    suspend fun executeLimitOrder(@Body request: ExecuteLimitOrderRequest): Response<ExecuteOrderResponse>

    @GET("/api/trades/orders/pending")
    suspend fun getPendingOrders(): Response<PendingOrdersResponse>

    @POST("/api/trades/orders/{id}/cancel")
    suspend fun cancelOrder(@Path("id") orderId: String): Response<CancelOrderResponse>
}
