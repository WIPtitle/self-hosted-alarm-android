package com.wiptitle.ntfy_webapp_android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    companion object {
        private const val KEY_WEBAPP_URL = "webapp_url"
        private const val KEY_NTFY_URL = "ntfy_url"
        private const val KEY_NTFY_TOPIC = "ntfy_topic"
        private const val KEY_NTFY_USERNAME = "ntfy_username"
        private const val KEY_NTFY_PASSWORD = "ntfy_password"
        private const val KEY_LAST_MESSAGE_ID = "last_message_id"
        private const val KEY_NTFY_CONNECTED = "ntfy_connected"
        private const val KEY_AUTO_RESTART_WORKER_VERSION = "auto_restart_worker_version"
    }

    var webAppUrl: String?
        get() = prefs.getString(KEY_WEBAPP_URL, null)
        set(value) = prefs.edit().putString(KEY_WEBAPP_URL, value).apply()

    var ntfyUrl: String
        get() = prefs.getString(KEY_NTFY_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NTFY_URL, value).apply()

    var ntfyTopic: String
        get() = prefs.getString(KEY_NTFY_TOPIC, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NTFY_TOPIC, value).apply()

    var ntfyUsername: String?
        get() = prefs.getString(KEY_NTFY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_NTFY_USERNAME, value).apply()

    var ntfyPassword: String?
        get() = prefs.getString(KEY_NTFY_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_NTFY_PASSWORD, value).apply()

    var lastMessageId: String?
        get() = prefs.getString(KEY_LAST_MESSAGE_ID, null)
        set(value) = prefs.edit().putString(KEY_LAST_MESSAGE_ID, value).apply()

    var isNtfyConnected: Boolean
        get() = prefs.getBoolean(KEY_NTFY_CONNECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_NTFY_CONNECTED, value).apply()

    fun getAutoRestartWorkerVersion(): Int {
        return prefs.getInt(KEY_AUTO_RESTART_WORKER_VERSION, 0)
    }

    fun setAutoRestartWorkerVersion(version: Int) {
        prefs.edit()
            .putInt(KEY_AUTO_RESTART_WORKER_VERSION, version)
            .apply()
    }

    fun isNtfyConfigured(): Boolean {
        return ntfyUrl.isNotEmpty() && ntfyTopic.isNotEmpty()
    }

    fun clearNtfyConfig() {
        prefs.edit()
            .remove(KEY_NTFY_URL)
            .remove(KEY_NTFY_TOPIC)
            .remove(KEY_NTFY_USERNAME)
            .remove(KEY_NTFY_PASSWORD)
            .remove(KEY_LAST_MESSAGE_ID)
            .remove(KEY_NTFY_CONNECTED)
            .apply()
    }

    fun clearNtfyLastMessage() {
        prefs.edit().remove(KEY_LAST_MESSAGE_ID).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}