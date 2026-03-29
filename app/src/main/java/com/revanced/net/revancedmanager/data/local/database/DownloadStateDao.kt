package com.revanced.net.revancedmanager.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DownloadStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: DownloadStateEntity)

    @Query("SELECT * FROM download_states WHERE status = :status")
    suspend fun getByStatus(status: String): List<DownloadStateEntity>

    @Query("DELETE FROM download_states WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String)

    @Query("DELETE FROM download_states")
    suspend fun clearAll()

    @Query("DELETE FROM download_states WHERE status = 'FAILED' AND updatedAt < :cutoffMillis")
    suspend fun deleteFailedOlderThan(cutoffMillis: Long)
}
