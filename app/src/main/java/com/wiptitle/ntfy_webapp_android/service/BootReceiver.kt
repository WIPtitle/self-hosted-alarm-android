package com.wiptitle.ntfy_webapp_android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.wiptitle.ntfy_webapp_android.data.PreferencesManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefsManager = PreferencesManager(context)
            if (prefsManager.isNtfyConfigured()) {
                val serviceIntent = Intent(context, NtfyService::class.java).apply {
                    action = NtfyService.ACTION_START
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}