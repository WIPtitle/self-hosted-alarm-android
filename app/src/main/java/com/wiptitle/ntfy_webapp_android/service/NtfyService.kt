package com.wiptitle.ntfy_webapp_android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.wiptitle.ntfy_webapp_android.MainActivity
import com.wiptitle.ntfy_webapp_android.data.PreferencesManager
import com.wiptitle.ntfy_webapp_android.notification.NotificationHandler

class NtfyService : Service() {
    private lateinit var prefsManager: PreferencesManager
    private lateinit var notificationHandler: NotificationHandler
    private var wsConnection: WsConnection? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var reconnectAttempts = 0

    private val MAX_RECONNECT_ATTEMPTS = 3

    override fun onCreate() {
        super.onCreate()
        prefsManager = PreferencesManager(this)
        notificationHandler = NotificationHandler(this)

        acquireWakeLock()
        startForeground(NOTIFICATION_ID, createForegroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startConnection()
            ACTION_STOP -> stopConnection()
            ACTION_RESTART -> restartConnection()
        }
        return START_STICKY
    }

    private fun startConnection() {
        if (!prefsManager.isNtfyConfigured()) return

        wsConnection?.close()
        reconnectAttempts = 0

        wsConnection = WsConnection(
            baseUrl = prefsManager.ntfyUrl,
            topic = prefsManager.ntfyTopic,
            username = prefsManager.ntfyUsername,
            password = prefsManager.ntfyPassword,
            lastMessageId = prefsManager.lastMessageId,
            onMessage = { notification ->
                notificationHandler.showNotification(notification)
                reconnectAttempts = 0  // Reset on successful message
            },
            onStateChange = { state ->
                updateForegroundNotification(state)
                if (state == WsConnection.ConnectionState.CONNECTED) {
                    reconnectAttempts = 0
                }
            },
            onLastMessageIdUpdate = { messageId ->
                prefsManager.lastMessageId = messageId
            },
            onPermanentFailure = {
                // Notify MainActivity about permanent failure
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                intent.putExtra("ntfy_error", true)
                startActivity(intent)
            }
        )

        wsConnection?.start()
    }

    private fun stopConnection() {
        wsConnection?.close()
        wsConnection = null
        stopForeground(true)
        stopSelf()
    }

    private fun restartConnection() {
        wsConnection?.close()
        wsConnection = null
        prefsManager.clearNtfyLastMessage()
        startConnection()
    }

    private fun createForegroundNotification(): Notification {
        createNotificationChannel()

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Notification service")
            .setContentText("Connected")
            .setContentIntent(pendingIntent)
            .setSound(null)
            .setShowWhen(false)
            .setOngoing(true)
            .setGroup(NOTIFICATION_GROUP_ID)
            .build()
    }

    private fun updateForegroundNotification(state: WsConnection.ConnectionState) {
        val text = when (state) {
            WsConnection.ConnectionState.CONNECTING -> "Connecting..."
            WsConnection.ConnectionState.CONNECTED -> "Connected"
            WsConnection.ConnectionState.DISCONNECTED -> "Reconnecting..."
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Notification service")
            .setContentText(text)
            .setSound(null)
            .setShowWhen(false)
            .setOngoing(true)
            .setGroup(NOTIFICATION_GROUP_ID)
            .build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "Notification Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NtfyService::WakeLock"
        )
        wakeLock?.acquire(10*60*1000L)
    }

    override fun onDestroy() {
        wsConnection?.close()
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_RESTART = "RESTART"
        private const val NOTIFICATION_ID = 2586
        private const val CHANNEL_ID_SERVICE = "ntfy_service_channel"
        private const val NOTIFICATION_GROUP_ID = "com.wiptitle.ntfy_webapp_android.NOTIFICATION_GROUP_SERVICE"
    }
}