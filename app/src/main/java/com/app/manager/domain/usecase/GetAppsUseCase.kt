package com.app.manager.domain.usecase

import com.app.manager.core.common.PatchUpdate
import com.app.manager.core.common.Result
import com.app.manager.core.common.VersionComparator
import com.app.manager.core.common.InstalledBuildStore
import com.app.manager.data.local.preferences.PreferencesManager
import com.app.manager.data.manager.AppManager
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
    private val appRepository: AppRepository,
    private val appManager: AppManager,
    private val installedBuildStore: InstalledBuildStore,
    private val preferencesManager: PreferencesManager
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
                    val patchUpdatesEnabled = runCatching {
                        preferencesManager.getAppConfig().patchUpdatesEnabled
                    }.getOrDefault(true)
                    val sortedApps = result.data
                        .map { app -> determineAppStatus(app, patchUpdatesEnabled) }
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
     * Determine the status of an app and mark patch-only updates.
     *
     * The versionName is compared against the catalog's versionName — the versionCode is useless
     * for this: some catalog entries publish versionCode 2147483647 so no installed code can
     * match, which used to keep every such app stuck on UPDATE_AVAILABLE.
     *
     * A same-versionName entry can still be an update when its provider rebuilt the app with
     * newer patches only ([PatchUpdate]); the settings switch turns that detection off.
     */
    private fun determineAppStatus(app: RevancedApp, patchUpdatesEnabled: Boolean): RevancedApp {
        val installed = app.currentVersion ?: return app.copy(status = AppStatus.NOT_INSTALLED)
        val cmp = VersionComparator.compare(installed, app.latestVersionName)
        val (status, isPatchUpdate) = when {
            cmp > 0 -> AppStatus.UP_TO_DATE to false
            cmp == 0 -> {
                if (!patchUpdatesEnabled) {
                    AppStatus.UP_TO_DATE to false
                } else {
                    val patchNewer = PatchUpdate.hasNewerPatch(
                        app = app,
                        record = installedBuildStore.get(app.packageName),
                        installedLastUpdateTime = appManager.getInstalledLastUpdateTime(app.packageName) ?: 0L
                    )
                    (if (patchNewer) AppStatus.UPDATE_AVAILABLE else AppStatus.UP_TO_DATE) to patchNewer
                }
            }
            else -> AppStatus.UPDATE_AVAILABLE to false
        }
        return app.copy(status = status, isPatchUpdate = isPatchUpdate)
    }
}
