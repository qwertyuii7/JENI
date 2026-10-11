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
