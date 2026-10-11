package com.scheduleassistant.app.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

class HlcClock(val nodeId: String) {
    private var lastWallTime: Long = 0
    private var logical: Int = 0

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Synchronized
    fun now(physicalTimeMs: Long = System.currentTimeMillis()): String {
        if (physicalTimeMs > lastWallTime) {
            lastWallTime = physicalTimeMs
            logical = 0
        } else {
            logical++
        }
        val iso = isoFormat.format(Date(lastWallTime))
        return String.format(Locale.US, "%s_%04d", iso, logical)
    }

    @Synchronized
    fun update(remoteHlc: String, physicalTimeMs: Long = System.currentTimeMillis()): String {
        val parts = remoteHlc.split("_")
        val remoteWall = try {
            isoFormat.parse(parts[0])?.time ?: physicalTimeMs
        } catch (_: Exception) {
            physicalTimeMs
        }
        val remoteCount = if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0

        val maxWall = max(physicalTimeMs, max(lastWallTime, remoteWall))
        if (maxWall == lastWallTime && maxWall == remoteWall) {
            logical = max(logical, remoteCount) + 1
        } else if (maxWall == remoteWall) {
            lastWallTime = remoteWall
            logical = remoteCount + 1
        } else {
            lastWallTime = maxWall
            logical = 0
        }

        val iso = isoFormat.format(Date(lastWallTime))
        return String.format(Locale.US, "%s_%04d", iso, logical)
    }

    companion object {
        fun compare(hlcA: String, deviceA: String, hlcB: String, deviceB: String): Int {
            val hlcCmp = hlcA.compareTo(hlcB)
            if (hlcCmp != 0) return hlcCmp
            return deviceA.compareTo(deviceB)
        }
    }
}
