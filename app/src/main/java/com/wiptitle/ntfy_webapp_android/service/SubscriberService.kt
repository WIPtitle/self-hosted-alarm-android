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
import com.wiptitle.ntfy_webapp_android.MainActivity
import com.wiptitle.ntfy_webapp_android.R
import com.wiptitle.ntfy_webapp_android.data.PreferencesManager
import com.wiptitle.ntfy_webapp_android.network.NtfyConfigFetcher
import com.wiptitle.ntfy_webapp_android.notification.NotificationHandler
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SubscriberService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceStarted = false
    private lateinit var prefsManager: PreferencesManager
    private lateinit var notificationHandler: NotificationHandler
    private lateinit var configFetcher: NtfyConfigFetcher
    private var wsConnection: WsConnection? = null
    private var notificationManager: NotificationManager? = null
    private var serviceNotification: Notification? = null
    private val refreshMutex = Mutex()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand executed with startId: $startId")
        if (intent != null) {
            Log.d(TAG, "using an intent with action ${intent.action}")
            when (intent.action) {
                Action.START.name -> startService()
                Action.STOP.name -> stopService()
                else -> Log.w(TAG, "This should never happen. No action in the received intent")
            }
        } else {
            Log.d(TAG, "with a null intent. It has been probably restarted by the system.")
            if (readServiceState(this) == ServiceState.STARTED) {
                startService()
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "Subscriber service has been created")

        prefsManager = PreferencesManager(this)
        notificationHandler = NotificationHandler(this)
        configFetcher = NtfyConfigFetcher()

        notificationManager = createNotificationChannel()
        serviceNotification = createNotification("Starting...", "Home alarm system")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_SERVICE_ID, serviceNotification!!, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_SERVICE_ID, serviceNotification)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Subscriber service has been destroyed")
        stopService()
        sendBroadcast(Intent(this, AutoRestartReceiver::class.java))
        super.onDestroy()
    }

    private fun startService() {
        if (isServiceStarted) {
            refreshConnections()
            return
        }
        Log.d(TAG, "Starting the foreground service task")
        isServiceStarted = true
        saveServiceState(this, ServiceState.STARTED)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                acquire()
            }
        }
        refreshConnections()
    }

    private fun stopService() {
        Log.d(TAG, "Stopping the foreground service")

        wsConnection?.close()
        wsConnection = null

        try {
            wakeLock?.let {
                while (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null
            stopForeground(true)
            stopSelf()
        } catch (e: Exception) {
            Log.d(TAG, "Service stopped without being started: ${e.message}")
        }

        isServiceStarted = false
        saveServiceState(this, ServiceState.STOPPED)
    }

    private fun refreshConnections() {
        GlobalScope.launch {
            if (!refreshMutex.tryLock()) {
                Log.d(TAG, "Refreshing connections already in progress. Skipping.")
                return@launch
            }
            try {
                reallyRefreshConnections()
            } finally {
                refreshMutex.unlock()
            }
        }
    }

    private fun reallyRefreshConnections() {
        if (!prefsManager.isNtfyConfigured()) {
            Log.w(TAG, "Cannot refresh connection - not configured")
            return
        }

        val existingConnection = wsConnection
        if (existingConnection != null) {
            Log.d(TAG, "Connection already exists, checking if it needs refresh")
            if (existingConnection.isConnected()) {
                Log.d(TAG, "Connection is active, no refresh needed")
                return
            }
            Log.d(TAG, "Connection is not active, closing and recreating")
            existingConnection.close()
            wsConnection = null
        }

        Log.d(TAG, "Creating new connection")
        val newConnection = WsConnection(
            baseUrl = prefsManager.ntfyUrl,
            topic = prefsManager.ntfyTopic,
            username = prefsManager.ntfyUsername,
            password = prefsManager.ntfyPassword,
            lastMessageId = prefsManager.lastMessageId,
            onMessage = { notification ->
                wakeLock?.acquire(NOTIFICATION_RECEIVED_WAKELOCK_TIMEOUT_MILLIS)
                notificationHandler.showNotification(notification)
                wakeLock?.let {
                    if (it.isHeld) {
                        it.release()
                    }
                }
            },
            onStateChange = { state ->
                Log.d(TAG, "Connection state changed: $state")
                updateServiceNotification(state)
                prefsManager.isNtfyConnected = (state == WsConnection.ConnectionState.CONNECTED)
            },
            onLastMessageIdUpdate = { messageId ->
                prefsManager.lastMessageId = messageId
            },
            onCredentialsNeeded = { callback ->
                fetchFreshCredentials(callback)
            }
        )

        wsConnection = newConnection
        newConnection.start()
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

    private fun updateServiceNotification(state: WsConnection.ConnectionState) {
        val text = when (state) {
            WsConnection.ConnectionState.CONNECTING -> "Connecting..."
            WsConnection.ConnectionState.CONNECTED -> "Connected"
            WsConnection.ConnectionState.DISCONNECTED -> "Reconnecting..."
        }
        serviceNotification = createNotification(text, "Home alarm system")
        notificationManager?.notify(NOTIFICATION_SERVICE_ID, serviceNotification)
    }

    private fun createNotificationChannel(): NotificationManager? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelName = "Notification Service"
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW).let {
                it.setShowBadge(false)
                it
            }
            notificationManager.createNotificationChannel(channel)
            return notificationManager
        }
        return null
    }

    private fun createNotification(text: String, title: String): Notification {
        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setSound(null)
            .setShowWhen(false)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        val restartServiceIntent = Intent(applicationContext, SubscriberService::class.java).also {
            it.setPackage(packageName)
        }
        val restartServicePendingIntent: PendingIntent = PendingIntent.getService(this, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        applicationContext.getSystemService(Context.ALARM_SERVICE)
        val alarmService: AlarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmService.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, restartServicePendingIntent)
    }

    class BootStartReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "BootStartReceiver: onReceive called")
            SubscriberServiceManager.refresh(context)
        }
    }

    class AutoRestartReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "AutoRestartReceiver: onReceive called")
            SubscriberServiceManager.refresh(context)
        }
    }

    enum class Action {
        START,
        STOP
    }

    enum class ServiceState {
        STARTED,
        STOPPED,
    }

    companion object {
        const val TAG = "NtfySubscriberService"
        const val SERVICE_START_WORKER_VERSION = 1
        const val SERVICE_START_WORKER_WORK_NAME_PERIODIC = "NtfyAutoRestartWorkerPeriodic"

        private const val WAKE_LOCK_TAG = "SubscriberService:lock"
        private const val NOTIFICATION_CHANNEL_ID = "ntfy-subscriber"
        private const val NOTIFICATION_SERVICE_ID = 2586
        private const val NOTIFICATION_RECEIVED_WAKELOCK_TIMEOUT_MILLIS = 10*60*1000L
        private const val SHARED_PREFS_ID = "SubscriberService"
        private const val SHARED_PREFS_SERVICE_STATE = "ServiceState"

        fun saveServiceState(context: Context, state: ServiceState) {
            val sharedPrefs = context.getSharedPreferences(SHARED_PREFS_ID, Context.MODE_PRIVATE)
            sharedPrefs.edit()
                .putString(SHARED_PREFS_SERVICE_STATE, state.name)
                .apply()
        }

        fun readServiceState(context: Context): ServiceState {
            val sharedPrefs = context.getSharedPreferences(SHARED_PREFS_ID, Context.MODE_PRIVATE)
            val value = sharedPrefs.getString(SHARED_PREFS_SERVICE_STATE, ServiceState.STOPPED.name)
            return ServiceState.valueOf(value!!)
        }
    }
}