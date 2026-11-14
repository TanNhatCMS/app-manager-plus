package com.app.manager.core.common

import com.app.manager.domain.model.RevancedApp

/**
 * Whether a newer build of the *same app version* is published — a patch-only update.
 *
 * The CI rebuilds an app whose app version did not change when its patches move on: same
 * versionName, newer patches. Comparing versionName alone never offered those builds, so a
 * YouTube installed on patches 1.39 stayed there until YouTube itself moved.
 *
 * The installed APK does not carry its patch version, so it comes from one of two places:
 * - **A record** this manager wrote at install time ([InstalledBuildRecord]). Exact.
 * - **The install time**, when there is no valid record. An APK installed before the current
 *   build was published cannot be that build, and for the same app version the only thing that
 *   makes the server publish again is a newer patch. [PUBLISH_MARGIN_MS] absorbs clock skew and
 *   the gap between building and uploading.
 */
object PatchUpdate {

    const val PUBLISH_MARGIN_MS: Long = 2 * 60 * 60 * 1000L

    /**
     * @param app a catalog entry whose [RevancedApp.latestVersionName], [RevancedApp.patchVersion]
     *   and [RevancedApp.updatedAt] describe the build chosen for this device.
     * @param record what this manager last installed for the package, or null when unknown/stale.
     * @param installedLastUpdateTime PackageInfo.lastUpdateTime of the installed build (0 when unknown).
     * @return true when the catalog build's patches are newer than what is on the device, for the
     *   same app version.
     */
    fun hasNewerPatch(
        app: RevancedApp,
        record: InstalledBuildRecord?,
        installedLastUpdateTime: Long
    ): Boolean {
        val catalogPatch = app.patchVersion ?: return false
        if (record != null && record.versionName == app.latestVersionName) {
            val stillOurs = installedLastUpdateTime == 0L ||
                record.lastUpdateTime == installedLastUpdateTime
            if (stillOurs) {
                return VersionComparator.compare(record.patchVersion, catalogPatch) < 0
            }
        }
        if (installedLastUpdateTime <= 0L) return false
        val updatedAt = app.updatedAt ?: return false
        return installedLastUpdateTime + PUBLISH_MARGIN_MS < updatedAt
    }
}
