package com.scheduleassistant.app

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.scheduleassistant.app.data.diagnostics.DiagnosticsManager
import com.scheduleassistant.app.data.diagnostics.HeartbeatWorker
import com.scheduleassistant.app.service.ScheduleForegroundService
import java.util.concurrent.TimeUnit

class ScheduleApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Requirement 1: Automatic heartbeat on process start + ApplicationExitInfo
        DiagnosticsManager.logProcessStartAndExitInfo(this)

        // Requirement 1: Periodic 15 min heartbeat via WorkManager
        try {
            val heartbeatRequest = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "heartbeat_worker",
                ExistingPeriodicWorkPolicy.KEEP,
                heartbeatRequest
            )
        } catch (_: Exception) {}

        try {
            val serviceIntent = Intent(this, ScheduleForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: Exception) {}
    }
}
