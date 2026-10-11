package com.scheduleassistant.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SyncEventEntity::class, RawMessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun rawMessageDao(): RawMessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "schedule_assistant.db"
                ).addCallback(object : Callback() {
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
                    }
                }).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
