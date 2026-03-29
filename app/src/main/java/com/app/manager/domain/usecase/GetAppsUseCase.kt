package com.app.manager.domain.usecase

import com.app.manager.core.common.Result
import com.app.manager.domain.model.AppStatus
import com.app.manager.domain.model.RevancedApp
import com.app.manager.domain.repository.AppRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Use case for getting and sorting ReVanced apps
 * Implements business logic for app retrieval and status determination
 */
class GetAppsUseCase @Inject constructor(
    private val appRepository: AppRepository
) {
    
    /**
     * Get apps with proper sorting and status checking
     * @param forceRefresh Whether to force refresh from network
     * @return Flow of Result containing sorted list of apps
     */
    operator fun invoke(forceRefresh: Boolean = false): Flow<Result<List<RevancedApp>>> {
        return appRepository.getApps(forceRefresh).map { result ->
            when (result) {
                is Result.Success -> {
                    val sortedApps = result.data
                        .map { app -> app.copy(status = determineAppStatus(app)) }
                        .sortedWith(
                            compareBy<RevancedApp> { app ->
                                // Primary sort: installed apps first
                                when (app.status) {
                                    AppStatus.UP_TO_DATE,
                                    AppStatus.UPDATE_AVAILABLE -> 0
                                    else -> 1
                                }
                            }.thenBy { app ->
                                // Secondary sort: by index within each group
                                app.index
                            }
                        )
                    Result.Success(sortedApps)
                }
                is Result.Error -> result
                is Result.Loading -> result
            }
        }
    }
    
    /**
     * Compare two version strings
     * @param installedVersion Currently installed version
     * @param latestVersion Latest available version
     * @return Positive if installedVersion > latestVersion, negative if less, zero if equal
     */
    private fun compareVersions(version1: String, version2: String): Int {
        if (version1.isEmpty() || version2.isEmpty()) {
            return 0
        }
        
        return try {
            val installedParts = version1.split(".").map { part ->
                part.takeWhile { it.isDigit() }.ifEmpty { "0" }
            }
            val latestParts = version2.split(".").map { part ->
                part.takeWhile { it.isDigit() }.ifEmpty { "0" }
            }
            
            val length = minOf(installedParts.size, latestParts.size)
            
            for (i in 0 until length) {
                val installedNum = installedParts[i].toLongOrNull() ?: 0L
                val latestNum = latestParts[i].toLongOrNull() ?: 0L
                
                when {
                    installedNum > latestNum -> return 1
                    installedNum < latestNum -> return -1
                }
            }
            
            installedParts.size.compareTo(latestParts.size)
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Determine the status of an app based on its installed and latest versions
     * @param app The RevancedApp to check
     * @return AppStatus representing the current status
     */
     private fun determineAppStatus(app: RevancedApp): AppStatus {
        val installedVersionCode = app.currentVersion?.toLongOrNull()

        return when {
            app.currentVersion == null -> AppStatus.NOT_INSTALLED
            // Ưu tiên so sánh VersionCode (Long) nếu có thể
            installedVersionCode != null && installedVersionCode >= app.latestVersionCode -> AppStatus.UP_TO_DATE
            // Fallback: So sánh VersionName (String) nếu không thể lấy VersionCode
            installedVersionCode == null && compareVersions(app.currentVersion, app.latestVersionName) >= 0 -> AppStatus.UP_TO_DATE
            else -> AppStatus.UPDATE_AVAILABLE
        }
    }
} 
