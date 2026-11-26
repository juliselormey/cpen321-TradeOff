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
                chain.proceed(request)
            }
            .build()
    }

    fun create(context: Context): ApiService {
        val rawUrl = BuildConfig.API_BASE_URL.ifBlank { "https://34.105.116.91" }
        val baseUrl = rawUrl.trimEnd('/') + "/"

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(getOkHttpClient(context))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return retrofit.create(ApiService::class.java)
    }

    fun friendEventsUrl(): String {
        val base = BuildConfig.API_BASE_URL.ifBlank { "https://34.105.116.91" }.trimEnd('/')
        return base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/friends/events"
    }

    fun createFriendEventsClient(context: Context): OkHttpClient {
        return getOkHttpClient(context).newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
    fun createFriends(context: Context): FriendsApi {
        val rawUrl = BuildConfig.API_BASE_URL.ifBlank { "https://34.105.116.91" }
        val baseUrl = rawUrl.trimEnd('/') + "/"
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(getOkHttpClient(context))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FriendsApi::class.java)
    }

    fun createMarket(context: Context): MarketApiService {
        val rawUrl = BuildConfig.MARKET_API_BASE_URL.ifBlank {
            BuildConfig.API_BASE_URL.ifBlank { "https://34.105.116.91" }
        }
        val baseUrl = rawUrl.trimEnd('/') + "/"
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(getOkHttpClient(context))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MarketApiService::class.java)
    }
}
