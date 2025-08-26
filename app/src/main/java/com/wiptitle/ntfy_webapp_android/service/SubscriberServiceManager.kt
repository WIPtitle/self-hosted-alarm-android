package com.wiptitle.ntfy_webapp_android.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.*
import com.wiptitle.ntfy_webapp_android.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SubscriberServiceManager(private val context: Context) {
    fun refresh() {
        val workManager = WorkManager.getInstance(context)
        val startServiceRequest = OneTimeWorkRequest.Builder(ServiceStartWorker::class.java).build()
        workManager.enqueueUniqueWork(WORK_NAME_ONCE, ExistingWorkPolicy.KEEP, startServiceRequest)
    }

    fun restart() {
        Intent(context, SubscriberService::class.java).also { intent ->
            context.stopService(intent)
        }
    }

    class ServiceStartWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            withContext(Dispatchers.IO) {
                val prefsManager = PreferencesManager(context)
                val action = if (prefsManager.isNtfyConfigured()) SubscriberService.Action.START else SubscriberService.Action.STOP
                val serviceState = SubscriberService.readServiceState(context)
                if (serviceState == SubscriberService.ServiceState.STOPPED && action == SubscriberService.Action.STOP) {
                    return@withContext Result.success()
                }
                Intent(context, SubscriberService::class.java).also {
                    it.action = action.name
                    ContextCompat.startForegroundService(context, it)
                }
            }
            return Result.success()
        }
    }

    companion object {
        const val TAG = "NtfySubscriberMgr"
        const val WORK_NAME_ONCE = "ServiceStartWorkerOnce"

        fun refresh(context: Context) {
            val manager = SubscriberServiceManager(context)
            manager.refresh()
        }
    }
}