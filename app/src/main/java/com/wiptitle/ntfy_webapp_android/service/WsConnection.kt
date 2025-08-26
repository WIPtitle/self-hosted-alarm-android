package com.wiptitle.ntfy_webapp_android.service

import android.annotation.SuppressLint
import android.util.Log
import com.google.gson.Gson
import com.wiptitle.ntfy_webapp_android.notification.NotificationData
import okhttp3.*
import okio.ByteString
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.*

class WsConnection(
    private var baseUrl: String,
    private var topic: String,
    private var username: String? = null,
    private var password: String? = null,
    private val lastMessageId: String? = null,
    private val onMessage: (NotificationData) -> Unit,
    private val onStateChange: (ConnectionState) -> Unit,
    private val onLastMessageIdUpdate: (String) -> Unit,
    private val onCredentialsNeeded: (callback: (String, String, String?, String?) -> Unit) -> Unit
) {
    private var webSocket: WebSocket? = null
    private val client = createUnsafeOkHttpClient()
    private val gson = Gson()
    private val closed = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private var currentLastMessageId: String? = lastMessageId
    private var reconnectAttempts = 0

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
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    fun start() {
        if (closed.get() || connected.get()) {
            Log.d(TAG, "Not starting: closed=${closed.get()}, connected=${connected.get()}")
            return
        }

        webSocket?.close(1000, "Reconnecting")
        webSocket = null

        if (currentLastMessageId == null) {
            pollForLastMessageId { lastId ->
                currentLastMessageId = lastId
                if (lastId != null) {
                    onLastMessageIdUpdate(lastId)
                }
                connectWebSocket()
            }
        } else {
            connectWebSocket()
        }
    }

    fun isConnected(): Boolean {
        return connected.get()
    }

    private fun pollForLastMessageId(callback: (String?) -> Unit) {
        val pollUrl = "$baseUrl/$topic/json?poll=1&since=all"
        val requestBuilder = Request.Builder().url(pollUrl)

        if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
            val credentials = Credentials.basic(username!!, password!!)
            requestBuilder.addHeader("Authorization", credentials)
        }

        Thread {
            try {
                val response = client.newCall(requestBuilder.build()).execute()
                if (response.isSuccessful) {
                    var lastId: String? = null
                    response.body?.string()?.lines()?.forEach { line ->
                        if (line.isNotBlank()) {
                            try {
                                val notification = gson.fromJson(line, NotificationData::class.java)
                                if (notification.event == "message") {
                                    lastId = notification.id
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing poll message", e)
                            }
                        }
                    }
                    callback(lastId)
                } else {
                    Log.e(TAG, "Poll failed with code: ${response.code}")
                    callback(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error polling for last message", e)
                callback(null)
            }
        }.start()
    }

    private fun connectWebSocket() {
        if (closed.get() || connected.get()) {
            Log.d(TAG, "Not connecting WebSocket: closed=${closed.get()}, connected=${connected.get()}")
            return
        }

        val sinceParam = currentLastMessageId ?: "none"
        val wsUrl = baseUrl.replace("http://", "ws://")
            .replace("https://", "wss://")

        val url = "$wsUrl/$topic/ws?since=$sinceParam"

        val requestBuilder = Request.Builder().url(url)

        if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
            val credentials = Credentials.basic(username!!, password!!)
            requestBuilder.addHeader("Authorization", credentials)
        }

        val request = requestBuilder.build()
        Log.d(TAG, "Connecting to WebSocket: $url")

        onStateChange(ConnectionState.CONNECTING)
        webSocket = client.newWebSocket(request, WebSocketListener())
    }

    fun updateCredentials(newBaseUrl: String, newTopic: String, newUsername: String?, newPassword: String?) {
        baseUrl = newBaseUrl
        topic = newTopic
        username = newUsername
        password = newPassword
    }

    fun close() {
        Log.d(TAG, "Closing connection")
        closed.set(true)
        connected.set(false)
        webSocket?.close(1000, "User closed")
        webSocket = null
        onStateChange(ConnectionState.DISCONNECTED)
    }

    private inner class WebSocketListener : okhttp3.WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connected successfully")
            connected.set(true)
            reconnectAttempts = 0
            onStateChange(ConnectionState.CONNECTED)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Message received: $text")
            try {
                val notification = gson.fromJson(text, NotificationData::class.java)
                when (notification.event) {
                    "message" -> {
                        currentLastMessageId = notification.id
                        onLastMessageIdUpdate(notification.id)
                        onMessage(notification)
                    }
                    "keepalive" -> {
                        Log.d(TAG, "Keepalive received")
                    }
                    "open" -> {
                        Log.d(TAG, "Connection opened event")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing message: $text", e)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onMessage(webSocket, bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code $reason")
            connected.set(false)
            onStateChange(ConnectionState.DISCONNECTED)
            attemptReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure - Code: ${response?.code}", t)
            connected.set(false)
            onStateChange(ConnectionState.DISCONNECTED)
            attemptReconnect()
        }
    }

    private fun attemptReconnect() {
        if (closed.get()) {
            Log.d(TAG, "Not reconnecting, connection is closed")
            return
        }

        connected.set(false)
        webSocket = null
        reconnectAttempts++

        Log.d(TAG, "Requesting fresh credentials before reconnect attempt $reconnectAttempts")
        onCredentialsNeeded { newBaseUrl, newTopic, newUsername, newPassword ->
            if (!closed.get()) {
                updateCredentials(newBaseUrl, newTopic, newUsername, newPassword)

                val delay = minOf(2000L * reconnectAttempts, 30000L)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!closed.get() && !connected.get()) {
                        Log.d(TAG, "Reconnecting with fresh credentials (attempt $reconnectAttempts)")
                        start()
                    }
                }, delay)
            }
        }
    }

    enum class ConnectionState {
        CONNECTING,
        CONNECTED,
        DISCONNECTED
    }

    companion object {
        private const val TAG = "WsConnection"
    }
}