package com.wiptitle.ntfy_webapp_android.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wiptitle.ntfy_webapp_android.MainActivity
import com.wiptitle.ntfy_webapp_android.R
import com.wiptitle.ntfy_webapp_android.data.PreferencesManager
import com.wiptitle.ntfy_webapp_android.network.NtfyConfigFetcher
import com.wiptitle.ntfy_webapp_android.notification.NotificationHandler

class NtfyService : Service() {
    private lateinit var prefsManager: PreferencesManager
    private lateinit var notificationHandler: NotificationHandler
    private lateinit var configFetcher: NtfyConfigFetcher
    private var wsConnection: WsConnection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceStarted = false

    companion object {
        private const val TAG = "NtfyService"
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_RESTART = "RESTART"
        const val ACTION_ENSURE_RUNNING = "ENSURE_RUNNING"
        private const val NOTIFICATION_ID = 2586
        private const val CHANNEL_ID_SERVICE = "ntfy_service_channel"
        private const val NOTIFICATION_GROUP_ID = "com.wiptitle.ntfy_webapp_android.NOTIFICATION_GROUP_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        prefsManager = PreferencesManager(this)
        notificationHandler = NotificationHandler(this)
        configFetcher = NtfyConfigFetcher()

        acquireWakeLock()

        val notification = createForegroundNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (prefsManager.isNtfyConfigured()) {
            startConnection()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                if (wsConnection == null || !isServiceStarted) {
                    startConnection()
                } else {
                    Log.d(TAG, "Connection already active, skipping start")
                }
            }
            ACTION_STOP -> stopConnection()
            ACTION_RESTART -> restartConnection()
            ACTION_ENSURE_RUNNING -> {
                if (!isServiceStarted || wsConnection == null) {
                    Log.d(TAG, "Service not running properly, starting connection")
                    startConnection()
                } else {
                    Log.d(TAG, "Service already running and connected")
                }
            }
        }

        return START_STICKY
    }

    private fun startConnection() {
        if (!prefsManager.isNtfyConfigured()) {
            Log.w(TAG, "Cannot start connection - not configured")
            return
        }

        Log.d(TAG, "Starting connection...")
        isServiceStarted = true

        wsConnection?.close()

        wsConnection = WsConnection(
            baseUrl = prefsManager.ntfyUrl,
            topic = prefsManager.ntfyTopic,
            username = prefsManager.ntfyUsername,
            password = prefsManager.ntfyPassword,
            lastMessageId = prefsManager.lastMessageId,
            onMessage = { notification ->
                notificationHandler.showNotification(notification)
            },
            onStateChange = { state ->
                Log.d(TAG, "Connection state changed: $state")
                updateForegroundNotification(state)
                prefsManager.isNtfyConnected = (state == WsConnection.ConnectionState.CONNECTED)
            },
            onLastMessageIdUpdate = { messageId ->
                prefsManager.lastMessageId = messageId
            },
            onCredentialsNeeded = { callback ->
                fetchFreshCredentials(callback)
            }
        )

        wsConnection?.start()
    }

    private fun fetchFreshCredentials(callback: (String, String, String?, String?) -> Unit) {
        val webAppUrl = prefsManager.webAppUrl
        if (webAppUrl.isNullOrEmpty()) {
            callback(prefsManager.ntfyUrl, prefsManager.ntfyTopic, prefsManager.ntfyUsername, prefsManager.ntfyPassword)
            return
        }

        configFetcher.fetchConfig(webAppUrl) { config ->
            if (config != null) {
                try {
                    val url = java.net.URL(webAppUrl)
                    val ntfyUrl = "${url.protocol}://${url.authority}"

                    prefsManager.ntfyUrl = ntfyUrl
                    prefsManager.ntfyTopic = config.topic
                    prefsManager.ntfyUsername = config.user
                    prefsManager.ntfyPassword = config.password

                    callback(ntfyUrl, config.topic, config.user, config.password)
                } catch (e: Exception) {
                    e.printStackTrace()
                    callback(prefsManager.ntfyUrl, prefsManager.ntfyTopic, prefsManager.ntfyUsername, prefsManager.ntfyPassword)
                }
            } else {
                callback(prefsManager.ntfyUrl, prefsManager.ntfyTopic, prefsManager.ntfyUsername, prefsManager.ntfyPassword)
            }
        }
    }

    private fun stopConnection() {
        Log.d(TAG, "Stopping connection...")
        isServiceStarted = false
        prefsManager.isNtfyConnected = false
        wsConnection?.close()
        wsConnection = null
        stopForeground(true)
        stopSelf()
    }

    private fun restartConnection() {
        Log.d(TAG, "Restarting connection...")
        wsConnection?.close()
        wsConnection = null
        prefsManager.isNtfyConnected = false
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
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle("Home alarm system")
            .setContentText("Starting...")
            .setContentIntent(pendingIntent)
            .setSilent(true)
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
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle("Home alarm system")
            .setContentText(text)
            .setSilent(true)
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
                "Home alarm system",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                description = "Keeps connection alive for instant notifications"
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
        wakeLock?.acquire()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy - Service is being destroyed!")

        if (isServiceStarted && prefsManager.isNtfyConfigured()) {
            Log.d(TAG, "Scheduling automatic restart...")

            val restartServiceIntent = Intent(applicationContext, NtfyService::class.java).also {
                it.setPackage(packageName)
                it.action = ACTION_START
            }

            val restartServicePendingIntent = PendingIntent.getService(
                this, 1, restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmService = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmService.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent
            )

            val intent = Intent(this, AutoRestartReceiver::class.java)
            sendBroadcast(intent)
        }

        prefsManager.isNtfyConnected = false
        wsConnection?.close()
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved - App task was removed")

        if (prefsManager.isNtfyConfigured()) {
            val restartServiceIntent = Intent(applicationContext, NtfyService::class.java).also {
                it.setPackage(packageName)
                it.action = ACTION_RESTART
            }

            val restartServicePendingIntent = PendingIntent.getService(
                this, 1, restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmService = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmService.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    class AutoRestartReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "AutoRestartReceiver triggered")
            val prefsManager = PreferencesManager(context)
            if (prefsManager.isNtfyConfigured()) {
                val serviceIntent = Intent(context, NtfyService::class.java).apply {
                    action = ACTION_START
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}