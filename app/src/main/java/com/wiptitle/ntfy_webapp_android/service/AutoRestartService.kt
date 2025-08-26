package com.wiptitle.ntfy_webapp_android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.wiptitle.ntfy_webapp_android.data.PreferencesManager

class AutoRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "AutoRestartReceiver triggered")
        val prefsManager = PreferencesManager(context)
        if (prefsManager.isNtfyConfigured()) {
            val serviceIntent = Intent(context, NtfyService::class.java).apply {
                action = NtfyService.ACTION_START
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }

    companion object {
        private const val TAG = "AutoRestartReceiver"
    }
}