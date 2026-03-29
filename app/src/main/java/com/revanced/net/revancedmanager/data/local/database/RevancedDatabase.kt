package com.revanced.net.revancedmanager.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [DownloadStateEntity::class],
    version = 1,
    exportSchema = false
)
abstract class RevancedDatabase : RoomDatabase() {
    abstract fun downloadStateDao(): DownloadStateDao

    companion object {
        const val DATABASE_NAME = "revanced_manager.db"
    }
}
