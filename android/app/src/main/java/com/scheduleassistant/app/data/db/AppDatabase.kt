package com.scheduleassistant.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SyncEventEntity::class,
        RawMessageEntity::class,
        DiagnosticLogEntity::class,
        SoakCheckpointEntity::class,
        HeartbeatLogEntity::class,
        AlarmTestEntity::class,
        AlarmAuditEntity::class,
        IngestionDecisionEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun rawMessageDao(): RawMessageDao
    abstract fun diagnosticLogDao(): DiagnosticLogDao
    abstract fun soakCheckpointDao(): SoakCheckpointDao
    abstract fun heartbeatLogDao(): HeartbeatLogDao
    abstract fun alarmTestDao(): AlarmTestDao
    abstract fun alarmAuditDao(): AlarmAuditDao
    abstract fun ingestionDecisionDao(): IngestionDecisionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "schedule_assistant.db"
                ).fallbackToDestructiveMigration().addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Install strict append-only triggers on events table
                        db.execSQL("""
                            CREATE TRIGGER IF NOT EXISTS prevent_event_update
                            BEFORE UPDATE ON events
                            BEGIN
                                SELECT RAISE(FAIL, 'Events table is append-only: updates are strictly prohibited');
                            END;
                        """.trimIndent())

                        db.execSQL("""
                            CREATE TRIGGER IF NOT EXISTS prevent_event_delete
                            BEFORE DELETE ON events
                            BEGIN
                                SELECT RAISE(FAIL, 'Events table is append-only: deletes are strictly prohibited');
                            END;
                        """.trimIndent())

                        // Install strict append-only triggers on heartbeat_logs table
                        db.execSQL("""
                            CREATE TRIGGER IF NOT EXISTS prevent_heartbeat_update
                            BEFORE UPDATE ON heartbeat_logs
                            BEGIN
                                SELECT RAISE(FAIL, 'Heartbeat log table is append-only: updates are strictly prohibited');
                            END;
                        """.trimIndent())

                        db.execSQL("""
                            CREATE TRIGGER IF NOT EXISTS prevent_heartbeat_delete
                            BEFORE DELETE ON heartbeat_logs
                            BEGIN
                                SELECT RAISE(FAIL, 'Heartbeat log table is append-only: deletes are strictly prohibited');
                            END;
                        """.trimIndent())
                    }
                }).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
