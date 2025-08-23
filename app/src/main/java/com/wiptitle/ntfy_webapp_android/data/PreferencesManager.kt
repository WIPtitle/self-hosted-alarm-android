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
        private const val KEY_NTFY_ENABLED = "ntfy_enabled"
        private const val KEY_LAST_MESSAGE_ID = "last_message_id"
    }

    var webAppUrl: String
        get() = prefs.getString(KEY_WEBAPP_URL, "about:blank") ?: "about:blank"
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

    var ntfyEnabled: Boolean
        get() = prefs.getBoolean(KEY_NTFY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_NTFY_ENABLED, value).apply()

    var lastMessageId: String?
        get() = prefs.getString(KEY_LAST_MESSAGE_ID, null)
        set(value) = prefs.edit().putString(KEY_LAST_MESSAGE_ID, value).apply()

    fun isNtfyConfigured(): Boolean {
        return ntfyUrl.isNotEmpty() && ntfyTopic.isNotEmpty()
    }

    fun clearNtfyLastMessage() {
        prefs.edit().remove(KEY_LAST_MESSAGE_ID).apply()
    }
}