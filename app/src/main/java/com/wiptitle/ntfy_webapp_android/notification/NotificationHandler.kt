package com.wiptitle.ntfy_webapp_android.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.wiptitle.ntfy_webapp_android.MainActivity
import com.wiptitle.ntfy_webapp_android.R

data class NotificationData(
    val id: String,
    val time: Long,
    val event: String,
    val topic: String,
    val title: String?,
    val message: String,
    val priority: Int?
)

class NotificationHandler(private val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelMin = NotificationChannel(
                CHANNEL_ID_MIN,
                "Low Priority Messages",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Low priority notifications"
            }

            val channelDefault = NotificationChannel(
                CHANNEL_ID_DEFAULT,
                "Normal Messages",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Normal priority notifications"
            }

            val channelHigh = NotificationChannel(
                CHANNEL_ID_HIGH,
                "Important Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority notifications"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 100, 100, 100)
            }

            val channelMax = NotificationChannel(
                CHANNEL_ID_MAX,
                "Urgent Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent notifications"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 100, 100, 100, 100, 100)
                enableLights(true)
                setBypassDnd(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build()
                )
            }

            notificationManager.createNotificationChannels(
                listOf(channelMin, channelDefault, channelHigh, channelMax)
            )
        }
    }

    fun showNotification(data: NotificationData) {
        // Create intent with special flag to indicate we're coming from a notification
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("from_notification", true)
        }

        // Use a unique request code based on notification ID to ensure each notification gets its own PendingIntent
        val requestCode = data.id.hashCode()

        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = data.title ?: data.topic
        val channelId = getChannelId(data.priority)
        val priority = mapPriority(data.priority)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(title)
            .setContentText(data.message)
            .setPriority(priority)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        notificationManager.notify(requestCode, notification)
    }

    private fun getChannelId(priority: Int?): String {
        return when (priority) {
            1, 2 -> CHANNEL_ID_MIN
            4 -> CHANNEL_ID_HIGH
            5 -> CHANNEL_ID_MAX
            else -> CHANNEL_ID_DEFAULT
        }
    }

    private fun mapPriority(priority: Int?): Int {
        return when (priority) {
            1 -> NotificationCompat.PRIORITY_MIN
            2 -> NotificationCompat.PRIORITY_LOW
            3 -> NotificationCompat.PRIORITY_DEFAULT
            4 -> NotificationCompat.PRIORITY_HIGH
            5 -> NotificationCompat.PRIORITY_MAX
            else -> NotificationCompat.PRIORITY_DEFAULT
        }
    }

    companion object {
        private const val CHANNEL_ID_MIN = "ntfy_priority_min"
        private const val CHANNEL_ID_DEFAULT = "ntfy_priority_default"
        private const val CHANNEL_ID_HIGH = "ntfy_priority_high"
        private const val CHANNEL_ID_MAX = "ntfy_priority_max"
    }
}