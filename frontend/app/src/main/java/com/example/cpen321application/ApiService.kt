package com.example.cpen321application

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path


data class NameResponse(val firstName: String, val lastName: String)

data class GoogleAuthRequest(val idToken: String)
data class GoogleAuthResponse(val accessToken: String)

// Trade API Models
data class UserPositionResponse(
    val cashBalance: Double,
    val ownedQuantity: Double,
    val averageCost: Double
)

data class ExecuteTradeRequest(
    val ticker: String,
    val type: String, // "BUY" or "SELL"
    val quantity: Double,
    val pricePerShare: Double
)

data class ExecuteTradeResponse(
    val success: Boolean,
    val message: String,
    val cashBalance: Double,
    val ownedQuantity: Double
)
interface ApiService {
    @POST("/api/auth/google")
    suspend fun loginWithGoogle(@Body request: GoogleAuthRequest): Response<GoogleAuthResponse>

    @GET("/api/trades/position/{ticker}")
    suspend fun getPosition(@Path("ticker") ticker: String): Response<UserPositionResponse>

    @POST("/api/trades/execute")
    suspend fun executeTrade(@Body request: ExecuteTradeRequest): Response<ExecuteTradeResponse>
}