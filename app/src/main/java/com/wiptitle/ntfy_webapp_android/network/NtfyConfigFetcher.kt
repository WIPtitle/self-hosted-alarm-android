package com.wiptitle.ntfy_webapp_android.network

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class NtfyConfig(
    val user: String,
    val password: String,
    val topic: String
)

class NtfyConfigFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

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