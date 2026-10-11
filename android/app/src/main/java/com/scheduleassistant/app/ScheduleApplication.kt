package com.scheduleassistant.app

import android.app.Application
import android.content.Intent
import android.os.Build
import com.scheduleassistant.app.service.ScheduleForegroundService

class ScheduleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
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
