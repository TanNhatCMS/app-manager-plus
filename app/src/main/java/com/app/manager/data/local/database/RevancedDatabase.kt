package com.app.manager.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [DownloadStateEntity::class],
    version = 2,
    exportSchema = false
)
abstract class RevancedDatabase : RoomDatabase() {
    abstract fun downloadStateDao(): DownloadStateDao

    companion object {
        const val DATABASE_NAME = "app_manager.db"
    }
}

