package com.app.manager.data.local.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_states")
data class DownloadStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val filePath: String?,
    val progress: Float,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long
)

