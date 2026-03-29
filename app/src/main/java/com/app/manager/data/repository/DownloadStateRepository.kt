package com.app.manager.data.repository

import com.app.manager.data.local.database.DownloadStateDao
import com.app.manager.data.local.database.DownloadStateEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadStateRepository @Inject constructor(
    private val dao: DownloadStateDao
) {
    suspend fun markDownloadCompleted(packageName: String, filePath: String) {
        val now = System.currentTimeMillis()
        val existing = DownloadStateEntity(
            id = 0,
            packageName = packageName,
            appName = packageName,
            filePath = filePath,
            progress = 1f,
            status = STATUS_COMPLETED,
            createdAt = now,
            updatedAt = now
        )
        dao.insert(existing)
    }

    suspend fun getCompletedDownloads(): List<DownloadState> {
        return dao.getByStatus(STATUS_COMPLETED).map { it.toModel() }
    }

    suspend fun getActiveDownloads(): List<DownloadState> {
        return dao.getByStatus(STATUS_ACTIVE).map { it.toModel() }
    }

    suspend fun removeDownloadState(packageName: String) {
        dao.deleteByPackage(packageName)
    }

    suspend fun clearAllDownloadStates() {
        dao.clearAll()
    }

    suspend fun cleanupOldFailedDownloads() {
        val sevenDaysAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
        dao.deleteFailedOlderThan(sevenDaysAgo)
    }

    private fun DownloadStateEntity.toModel(): DownloadState {
        return DownloadState(
            packageName = packageName,
            appName = appName,
            filePath = filePath,
            progress = progress,
            status = status,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_COMPLETED = "COMPLETED"
    }
}

data class DownloadState(
    val packageName: String,
    val appName: String,
    val filePath: String?,
    val progress: Float,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long
)

