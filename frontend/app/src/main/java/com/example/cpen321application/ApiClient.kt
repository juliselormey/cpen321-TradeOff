package com.example.cpen321application

import android.content.Context
import com.example.cpen321application.ui.market.data.MarketApiService
import com.example.cpen321application.ui.social.FriendsApi
import com.example.cpen321application.util.SessionManager
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object ApiClient {
    private fun createdTrustClient(context: Context): OkHttpClient {
        val ctf = CertificateFactory.getInstance("X.509")
        val inputStream = context.resources.openRawResource(R.raw.cert)
        val ctfobj = ctf.generateCertificate(inputStream)
        inputStream.close()

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("ca", ctfobj)
        }

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, tmf.trustManagers, null)
        }

        val trustManager = tmf.trustManagers[0] as X509TrustManager

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
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

    private fun getOkHttpClient(context: Context, url: String, timeoutSeconds: Long = 15): OkHttpClient {
        return if (url.startsWith("https://")) {
            createdTrustClient(context).newBuilder()
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build()
        } else {
            OkHttpClient.Builder()
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
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
    }

    fun create(context: Context): ApiService {
        val rawUrl = BuildConfig.API_BASE_URL.ifBlank { "https://34.105.116.91:3000" }
        val baseUrl = rawUrl.trimEnd('/') + "/"

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(getOkHttpClient(context, baseUrl))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return retrofit.create(ApiService::class.java)
    }

    fun friendEventsUrl(): String {
        val base = BuildConfig.API_BASE_URL.ifBlank { "https://34.105.116.91:3000" }.trimEnd('/')
        return base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/friends/events"
    }

    fun createFriendEventsClient(context: Context): OkHttpClient {
        val base = BuildConfig.API_BASE_URL.ifBlank { "https://34.105.116.91:3000" }
        return getOkHttpClient(context, base).newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
    fun createFriends(context: Context): FriendsApi {
        val rawUrl = BuildConfig.API_BASE_URL.ifBlank { "https://34.105.116.91:3000" }
        val baseUrl = rawUrl.trimEnd('/') + "/"
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(getOkHttpClient(context, baseUrl, 15))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FriendsApi::class.java)
    }

    fun createMarket(context: Context): MarketApiService {
        val rawUrl = BuildConfig.MARKET_API_BASE_URL.ifBlank {
            BuildConfig.API_BASE_URL.ifBlank { "https://34.105.116.91:3000" }
        }
        val baseUrl = rawUrl.trimEnd('/') + "/"
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(getOkHttpClient(context, baseUrl, 12))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MarketApiService::class.java)
    }

    private fun marketBaseUrl(): String = BuildConfig.MARKET_API_BASE_URL.ifBlank {
        BuildConfig.API_BASE_URL.ifBlank { "https://34.105.116.91:3000" }
    }.trimEnd('/')

    fun marketEventsUrl(): String = marketBaseUrl()
        .replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/market/events"

    fun createMarketEventsClient(context: Context): OkHttpClient =
        getOkHttpClient(context, marketBaseUrl()).newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
}
