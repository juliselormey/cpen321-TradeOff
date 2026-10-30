package com.example.cpen321application

import android.content.Context
import com.example.cpen321application.util.SessionManager
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import com.example.cpen321application.ui.market.data.MarketApiService
import java.util.concurrent.TimeUnit

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
                val request = if (!token.isNullOrEmpty()) {
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
        val rawUrl = BuildConfig.API_BASE_URL.ifEmpty { "https://34.105.116.91:3000" }
        val baseUrl = rawUrl.trimEnd('/') + "/"

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(createdTrustClient(context))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return retrofit.create(ApiService::class.java)
    }

    fun createFriends(context: Context): com.example.cpen321application.ui.social.FriendsApi {
        val url = BuildConfig.API_BASE_URL.ifBlank { "https://34.169.187.216/" }
        return Retrofit.Builder()
            .baseUrl(url.trimEnd('/') + "/")
            .client(createdTrustClient(context).newBuilder().callTimeout(15, TimeUnit.SECONDS).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(com.example.cpen321application.ui.social.FriendsApi::class.java)
    }
    fun createMarket(context: Context): MarketApiService {
        val rawUrl = BuildConfig.MARKET_API_BASE_URL.ifBlank {
            BuildConfig.API_BASE_URL.ifBlank { "https://34.105.116.91:3000" }
        }
        return Retrofit.Builder()
            .baseUrl(rawUrl.trimEnd('/') + "/")
            .client(createdTrustClient(context).newBuilder().callTimeout(12, TimeUnit.SECONDS).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MarketApiService::class.java)
    }
}
