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

@Dao
interface SoakCheckpointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSoakCheckpoint(checkpoint: SoakCheckpointEntity)

    @Query("SELECT * FROM soak_checkpoints ORDER BY hour_index ASC")
    fun getAllSoakCheckpointsFlow(): Flow<List<SoakCheckpointEntity>>

    @Query("SELECT * FROM soak_checkpoints ORDER BY hour_index ASC")
    suspend fun getAllSoakCheckpoints(): List<SoakCheckpointEntity>

    @Query("SELECT * FROM soak_checkpoints ORDER BY hour_index DESC LIMIT 1")
    suspend fun getLatestSoakCheckpoint(): SoakCheckpointEntity?

    @Query("SELECT COUNT(*) FROM soak_checkpoints")
    suspend fun getCheckpointCount(): Int

    @Query("DELETE FROM soak_checkpoints")
    suspend fun clearAllCheckpoints()
}

@Dao
interface HeartbeatLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHeartbeat(heartbeat: HeartbeatLogEntity): Long

    @Query("SELECT * FROM heartbeat_logs ORDER BY timestamp DESC")
    fun getAllHeartbeatsFlow(): Flow<List<HeartbeatLogEntity>>

    @Query("SELECT * FROM heartbeat_logs ORDER BY timestamp ASC")
    suspend fun getAllHeartbeats(): List<HeartbeatLogEntity>

    @Query("SELECT * FROM heartbeat_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentHeartbeats(limit: Int): List<HeartbeatLogEntity>

    @Query("SELECT COUNT(*) FROM heartbeat_logs")
    suspend fun getHeartbeatCount(): Int
}

@Dao
interface AlarmTestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTestRecord(record: AlarmTestEntity): Long

    @androidx.room.Update
    suspend fun updateTestRecord(record: AlarmTestEntity)

    @Query("SELECT * FROM alarm_test_records WHERE id = :id")
    suspend fun getRecordById(id: String): AlarmTestEntity?

    @Query("SELECT * FROM alarm_test_records ORDER BY scheduled_time_ms DESC")
    fun getAllRecordsFlow(): Flow<List<AlarmTestEntity>>

    @Query("SELECT * FROM alarm_test_records ORDER BY scheduled_time_ms DESC")
    suspend fun getAllRecords(): List<AlarmTestEntity>

    @Query("SELECT * FROM alarm_test_records WHERE status = 'SCHEDULED' AND scheduled_time_ms > :nowMs")
    suspend fun getPendingRecords(nowMs: Long): List<AlarmTestEntity>
}

@Dao
interface AlarmAuditDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudit(audit: AlarmAuditEntity): Long

    @Query("SELECT * FROM alarm_audit_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentAudits(limit: Int = 50): List<AlarmAuditEntity>

    @Query("SELECT * FROM alarm_audit_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentAuditsFlow(limit: Int = 50): Flow<List<AlarmAuditEntity>>

    @Query("SELECT * FROM alarm_audit_logs WHERE result = 'ERROR' ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastError(): AlarmAuditEntity?

    @Query("SELECT * FROM alarm_audit_logs WHERE result = 'ERROR' ORDER BY timestamp DESC LIMIT 1")
    fun getLastErrorFlow(): Flow<AlarmAuditEntity?>

    @Query("SELECT * FROM alarm_audit_logs ORDER BY timestamp DESC")
    suspend fun getAllAudits(): List<AlarmAuditEntity>

    @Query("DELETE FROM alarm_audit_logs")
    suspend fun clearAllAudits()
}

@Dao
interface IngestionDecisionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDecision(decision: IngestionDecisionEntity): Long

    @Query("SELECT * FROM ingestion_decisions ORDER BY timestamp_ms DESC LIMIT :limit")
    suspend fun getRecentDecisions(limit: Int = 500): List<IngestionDecisionEntity>

    @Query("SELECT * FROM ingestion_decisions WHERE timestamp_ms >= :cutoff ORDER BY timestamp_ms DESC")
    suspend fun getDecisionsSince(cutoff: Long): List<IngestionDecisionEntity>

    @Query("SELECT * FROM ingestion_decisions WHERE timestamp_ms >= :cutoff ORDER BY timestamp_ms DESC")
    fun getDecisionsSinceFlow(cutoff: Long): Flow<List<IngestionDecisionEntity>>

    @Query("SELECT COUNT(*) FROM ingestion_decisions")
    suspend fun getDecisionCount(): Int
}



