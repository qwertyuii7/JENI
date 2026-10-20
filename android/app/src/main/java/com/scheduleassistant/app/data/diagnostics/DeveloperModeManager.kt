package com.scheduleassistant.app.data.diagnostics

import android.content.Context
import android.content.SharedPreferences

sealed class DeveloperTapResult {
    object Silent : DeveloperTapResult()
    data class Hint(val stepsRemaining: Int) : DeveloperTapResult()
    object Activated : DeveloperTapResult()
    object AlreadyActive : DeveloperTapResult()
}

object DeveloperModeManager {

    private const val PREFS_NAME = "schedule_assistant_developer_mode"
    private const val KEY_DEVELOPER_MODE_ENABLED = "developer_mode_enabled"
    private const val TAP_THRESHOLD = 5
    private const val TAP_TIMEOUT_MS = 2500L

    private var tapCount = 0
    private var lastTapTimeMs = 0L

    internal var prefsOverride: SharedPreferences? = null

    private fun getPrefs(context: Context?): SharedPreferences {
        return prefsOverride ?: context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?: throw IllegalStateException("Context or prefsOverride is required")
    }

    fun isDeveloperModeEnabled(context: Context? = null): Boolean {
        return getPrefs(context).getBoolean(KEY_DEVELOPER_MODE_ENABLED, false)
    }

    fun setDeveloperModeEnabled(context: Context? = null, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DEVELOPER_MODE_ENABLED, enabled).apply()
        if (!enabled) {
            tapCount = 0
        }
    }

    @Synchronized
    fun onVersionTap(context: Context? = null): DeveloperTapResult {
        if (isDeveloperModeEnabled(context)) {
            return DeveloperTapResult.AlreadyActive
        }

        val now = System.currentTimeMillis()
        if (now - lastTapTimeMs > TAP_TIMEOUT_MS) {
            tapCount = 0
        }
        lastTapTimeMs = now
        tapCount++

        return when {
            tapCount >= TAP_THRESHOLD -> {
                setDeveloperModeEnabled(context, true)
                tapCount = 0
                DeveloperTapResult.Activated
            }
            tapCount == 4 -> DeveloperTapResult.Hint(stepsRemaining = 1)
            tapCount == 3 -> DeveloperTapResult.Hint(stepsRemaining = 2)
            else -> DeveloperTapResult.Silent
        }
    }

    fun resetTapCounter() {
        tapCount = 0
    }
}
