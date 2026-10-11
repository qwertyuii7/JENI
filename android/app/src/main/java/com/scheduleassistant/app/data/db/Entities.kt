package com.scheduleassistant.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["hlc", "device_id"]),
        Index(value = ["device_id", "seq"]),
        Index(value = ["entity_id"]),
        Index(value = ["type"])
    ]
)
data class SyncEventEntity(
    @PrimaryKey
    val id: String,
    val timestamp: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    val seq: Long,
    val hlc: String,
    val type: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    val payload: String, // Stored as JSON string
    @ColumnInfo(name = "created_at", defaultValue = "CURRENT_TIMESTAMP")
    val createdAt: String = ""
)

@Entity(
    tableName = "raw_messages",
    indices = [Index(value = ["created_at"])]
)
data class RawMessageEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "raw_text")
    val rawText: String,
    val source: String = "whatsapp",
    @ColumnInfo(name = "created_at")
    val createdAt: String
)

@Entity(
    tableName = "diagnostic_logs",
    indices = [Index(value = ["created_at"])]
)
data class DiagnosticLogEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "chat_title")
    val chatTitle: String?,
    val sender: String?,
    @ColumnInfo(name = "is_group")
    val isGroup: Boolean,
    @ColumnInfo(name = "raw_text")
    val rawText: String,
    @ColumnInfo(name = "prefilter_result")
    val prefilterResult: String,
    @ColumnInfo(name = "parser_output")
    val parserOutput: String?, // JSON serialized ExtractionOutput or null
    val mode: String = "LOG_ONLY" // "LOG_ONLY" or "NORMAL"
)

@Entity(
    tableName = "soak_checkpoints",
    indices = [Index(value = ["timestamp"])]
)
data class SoakCheckpointEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "hour_index")
    val hourIndex: Int,
    val timestamp: String,
    @ColumnInfo(name = "listener_connected")
    val listenerConnected: Boolean,
    @ColumnInfo(name = "last_notification_time")
    val lastNotificationTime: Long?,
    @ColumnInfo(name = "notifications_captured_count")
    val notificationsCapturedCount: Int,
    @ColumnInfo(name = "is_log_only_mode")
    val isLogOnlyMode: Boolean,
    val notes: String? = null
)
