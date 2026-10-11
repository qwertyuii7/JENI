package com.scheduleassistant.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: SyncEventEntity): Long

    @Query("SELECT * FROM events ORDER BY hlc ASC, device_id ASC")
    fun getAllEventsFlow(): Flow<List<SyncEventEntity>>

    @Query("SELECT * FROM events ORDER BY hlc ASC, device_id ASC")
    suspend fun getAllEvents(): List<SyncEventEntity>

    @Query("SELECT * FROM events WHERE entity_id = :entityId ORDER BY hlc ASC, device_id ASC")
    suspend fun getEventsByEntityId(entityId: String): List<SyncEventEntity>

    @Query("SELECT COALESCE(MAX(seq), 0) + 1 FROM events WHERE device_id = :deviceId")
    suspend fun getNextSeq(deviceId: String): Long
}

@Dao
interface RawMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRawMessage(message: RawMessageEntity)

    @Query("SELECT raw_text FROM raw_messages WHERE id = :id")
    suspend fun getRawMessage(id: String): String?

    @Query("DELETE FROM raw_messages WHERE created_at < :cutoffDate")
    suspend fun pruneRawMessages(cutoffDate: String): Int
}

@Dao
interface DiagnosticLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDiagnosticLog(log: DiagnosticLogEntity)

    @Query("SELECT * FROM diagnostic_logs ORDER BY created_at DESC")
    fun getAllDiagnosticLogsFlow(): Flow<List<DiagnosticLogEntity>>

    @Query("SELECT * FROM diagnostic_logs ORDER BY created_at DESC")
    suspend fun getAllDiagnosticLogs(): List<DiagnosticLogEntity>

    @Query("SELECT * FROM diagnostic_logs ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentDiagnosticLogs(limit: Int): List<DiagnosticLogEntity>

    @Query("SELECT COUNT(*) FROM diagnostic_logs")
    suspend fun getDiagnosticLogCount(): Int

    @Query("DELETE FROM diagnostic_logs")
    suspend fun clearAllDiagnosticLogs()
}

