package com.eko.dcvisits.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [OutboxEntity::class, CspCacheEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
    abstract fun cspCacheDao(): CspCacheDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "eko-dc-visits.db",
                )
                    // Never wipe the outbox on an app update: it may hold unsynced
                    // attendance/visit evidence. Any future schema change ships a
                    // real Migration; only a downgrade (dev builds) may reset.
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build().also { instance = it }
            }
    }
}
