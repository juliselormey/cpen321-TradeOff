package com.example.cpen321application

import android.content.Context
import com.example.cpen321application.ui.market.data.MarketApiService
import com.example.cpen321application.ui.social.FriendsApi
import com.example.cpen321application.util.SessionManager
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private fun getOkHttpClient(context: Context): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val token = SessionManager.getToken(context)
                val request = if (original.header("Authorization") == null && !token.isNullOrEmpty()) {
                    original.newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                } else {
                    original
                }
                val response = chain.proceed(request)
                if (response.code == 401) {
                    request.header("Authorization")?.removePrefix("Bearer ")?.let { SessionManager.invalidate(context, it) }
                }
                response
            }
            .build()
    }

    private fun <T> service(context: Context, url: String, type: Class<T>): T = Retrofit.Builder()
        .baseUrl(url).client(getOkHttpClient(context))
        .addConverterFactory(GsonConverterFactory.create()).build().create(type)

    fun create(context: Context): ApiService = service(context, ApiConfiguration.apiBaseUrl, ApiService::class.java)
    fun createFriends(context: Context): FriendsApi = service(context, ApiConfiguration.apiBaseUrl, FriendsApi::class.java)
    fun createMarket(context: Context): MarketApiService = service(context, ApiConfiguration.marketBaseUrl, MarketApiService::class.java)
    fun friendEventsUrl(): String = ApiConfiguration.eventsUrl(ApiConfiguration.apiBaseUrl, "/api/friends/events")
    fun marketEventsUrl(): String = ApiConfiguration.eventsUrl(ApiConfiguration.marketBaseUrl, "/api/market/events")
    private fun eventsClient(context: Context): OkHttpClient = getOkHttpClient(context).newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    fun createFriendEventsClient(context: Context): OkHttpClient = eventsClient(context)
    fun createMarketEventsClient(context: Context): OkHttpClient = eventsClient(context)
}
