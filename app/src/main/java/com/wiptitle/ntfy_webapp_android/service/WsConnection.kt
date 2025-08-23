package com.wiptitle.ntfy_webapp_android.service

import android.util.Log
import com.google.gson.Gson
import com.wiptitle.ntfy_webapp_android.notification.NotificationData
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

class WsConnection(
    private val baseUrl: String,
    private val topic: String,
    private val username: String? = null,
    private val password: String? = null,
    private val lastMessageId: String? = null,
    private val onMessage: (NotificationData) -> Unit,
    private val onStateChange: (ConnectionState) -> Unit,
    private val onLastMessageIdUpdate: (String) -> Unit
) {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private var closed = false
    private var currentLastMessageId: String? = lastMessageId

    fun start() {
        if (closed || webSocket != null) return

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

    private fun pollForLastMessageId(callback: (String?) -> Unit) {
        val pollUrl = "$baseUrl/$topic/json?poll=1&since=all"
        val requestBuilder = Request.Builder().url(pollUrl)

        if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
            val credentials = Credentials.basic(username, password)
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
                    callback(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error polling for last message", e)
                callback(null)
            }
        }.start()
    }

    private fun connectWebSocket() {
        val sinceParam = currentLastMessageId ?: "none"
        val protocol = if (baseUrl.startsWith("https")) "wss" else "ws"
        val wsUrl = baseUrl.replace("http://", "ws://")
            .replace("https://", "wss://")

        val url = "$wsUrl/$topic/ws?since=$sinceParam"

        val requestBuilder = Request.Builder().url(url)

        if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
            val credentials = Credentials.basic(username, password)
            requestBuilder.addHeader("Authorization", credentials)
        }

        val request = requestBuilder.build()
        Log.d(TAG, "Connecting to WebSocket: $url")

        onStateChange(ConnectionState.CONNECTING)
        webSocket = client.newWebSocket(request, WebSocketListener())
    }

    fun close() {
        closed = true
        webSocket?.close(1000, "User closed")
        webSocket = null
        onStateChange(ConnectionState.DISCONNECTED)
    }

    private inner class WebSocketListener : okhttp3.WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket connected successfully")
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
            onStateChange(ConnectionState.DISCONNECTED)
            this@WsConnection.webSocket = null
            attemptReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure - Code: ${response?.code}", t)
            onStateChange(ConnectionState.DISCONNECTED)
            this@WsConnection.webSocket = null
            attemptReconnect()
        }
    }

    private fun attemptReconnect() {
        if (!closed) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!closed && webSocket == null) {
                    Log.d(TAG, "Attempting to reconnect...")
                    connectWebSocket()
                }
            }, 5000)
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