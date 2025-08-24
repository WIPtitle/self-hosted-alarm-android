package com.wiptitle.ntfy_webapp_android.network

import android.annotation.SuppressLint
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

data class NtfyConfig(
    val user: String,
    val password: String,
    val topic: String
)

class NtfyConfigFetcher {
    private val client = createUnsafeOkHttpClient()
    private val gson = Gson()

    private fun createUnsafeOkHttpClient(): OkHttpClient {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            val sslSocketFactory = sslContext.socketFactory

            return OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    fun fetchConfig(baseUrl: String, callback: (NtfyConfig?) -> Unit) {
        Thread {
            try {
                val cleanUrl = baseUrl.trimEnd('/')
                val url = "$cleanUrl/api/notifications-service/ntfy-config/credentials"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string()
                    val config = gson.fromJson(body, NtfyConfig::class.java)
                    callback(config)
                } else {
                    callback(null)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                callback(null)
            }
        }.start()
    }
}