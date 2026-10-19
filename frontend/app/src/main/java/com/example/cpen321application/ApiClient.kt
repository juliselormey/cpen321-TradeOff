package com.example.cpen321application

import android.content.Context
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
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

        val sslContext =  SSLContext.getInstance("TLS").apply {
            init(null, tmf.trustManagers, null)
        }

        val trustManager = tmf.trustManagers[0] as X509TrustManager

        return OkHttpClient.Builder().sslSocketFactory(sslContext.socketFactory, trustManager).build()
    }

    fun create(context: Context): ApiService {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://34.105.116.91:3000")
            .client(createdTrustClient(context))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return retrofit.create(ApiService::class.java)
    }
}