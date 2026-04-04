package com.wiptitle.ntfy_webapp_android.service

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.wiptitle.ntfy_webapp_android.data.PreferencesManager
import com.wiptitle.ntfy_webapp_android.network.FirebaseTokenRegistrar
import com.wiptitle.ntfy_webapp_android.notification.NotificationData
import com.wiptitle.ntfy_webapp_android.notification.NotificationHandler

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["title"]
            ?: "Alert"
        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["body"]
            ?: ""
        val priority = remoteMessage.data["priority"]?.toIntOrNull()

        val notificationData = NotificationData(
            id = remoteMessage.messageId ?: System.currentTimeMillis().toString(),
            time = System.currentTimeMillis() / 1000,
            event = "message",
            topic = "firebase",
            title = title,
            message = body,
            priority = priority
        )

        NotificationHandler(this).showNotification(notificationData)
    }

    override fun onNewToken(token: String) {
        val webAppUrl = PreferencesManager(this).webAppUrl ?: return
        Thread {
            FirebaseTokenRegistrar().registerToken(token, webAppUrl)
        }.start()
    }
}
