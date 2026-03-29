package com.app.manager.data.manager

sealed class InstallationResult {
    data class Success(val packageName: String) : InstallationResult()
    data class Failed(
        val packageName: String,
        val error: String,
        val statusCode: Int = -1
    ) : InstallationResult()

    data class PendingUserAction(val packageName: String) : InstallationResult()
}

