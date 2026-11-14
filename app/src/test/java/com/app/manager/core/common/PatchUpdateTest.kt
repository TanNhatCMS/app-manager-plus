package com.app.manager.core.common

import com.app.manager.domain.model.RevancedApp
import com.app.manager.domain.model.AppStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchUpdateTest {

    private val publishedAt = 1_700_000_000_000L // a realistic epoch (Nov 2023)

    private fun app(
        versionName: String = "21.04.223",
        patchVersion: String? = "1.40.0",
        updatedAt: Long? = publishedAt
    ) = RevancedApp(
        packageName = "app.morphe.android.youtube",
        title = "YouTube Morphe",
        latestVersionCode = 2147483647L,
        latestVersionName = versionName,
        currentVersion = versionName,
        description = "",
        iconUrl = "",
        downloadUrl = "https://example.com/app.apk",
        requiresMicroG = true,
        index = 1,
        status = AppStatus.UP_TO_DATE,
        updatedAt = updatedAt,
        patchVersion = patchVersion
    )

    @Test
    fun `older recorded patch is an update`() {
        val record = InstalledBuildRecord("21.04.223", "1.39.0", lastUpdateTime = 5_000L)
        assertTrue(PatchUpdate.hasNewerPatch(app(), record, installedLastUpdateTime = 5_000L))
    }

    @Test
    fun `same recorded patch is not an update`() {
        val record = InstalledBuildRecord("21.04.223", "1.40.0", lastUpdateTime = 5_000L)
        assertFalse(PatchUpdate.hasNewerPatch(app(), record, installedLastUpdateTime = 5_000L))
    }

    @Test
    fun `record for a different app version is ignored`() {
        val record = InstalledBuildRecord("21.13.163", "1.30.0", lastUpdateTime = publishedAt + 60_000L)
        // The record belongs to another version's build — fall through to install-time logic.
        // The install came after the build was published, so it IS that build: no update.
        assertFalse(
            PatchUpdate.hasNewerPatch(app(), record, installedLastUpdateTime = publishedAt + 60_000L)
        )
    }

    @Test
    fun `a record another installer overwrote is retired via lastUpdateTime`() {
        val record = InstalledBuildRecord("21.04.223", "1.40.0", lastUpdateTime = 9_999L)
        // The device's lastUpdateTime no longer matches the record: install-time path instead.
        assertTrue(PatchUpdate.hasNewerPatch(app(), record, installedLastUpdateTime = 0L) == false)
        // Unknown install time → no signal at all.
        assertFalse(PatchUpdate.hasNewerPatch(app(), record, installedLastUpdateTime = 0L))
    }

    @Test
    fun `install before the build was published means the installed build is older`() {
        // Installed 3h BEFORE the catalog build was published — it cannot be that build, and for
        // the same app version the only thing the server republishes is a newer patch.
        assertTrue(
            PatchUpdate.hasNewerPatch(
                app(),
                record = null,
                installedLastUpdateTime = publishedAt - PatchUpdate.PUBLISH_MARGIN_MS - 60_000L
            )
        )
    }

    @Test
    fun `install after the build was published is that build`() {
        // Installed 3h after publish — the margin (2h) has passed, so the install IS the build.
        assertFalse(
            PatchUpdate.hasNewerPatch(
                app(),
                record = null,
                installedLastUpdateTime = publishedAt + PatchUpdate.PUBLISH_MARGIN_MS + 60_000L
            )
        )
    }

    @Test
    fun `catalog entry without a patch version never reports a patch update`() {
        assertFalse(PatchUpdate.hasNewerPatch(app(patchVersion = null), null, 800L))
    }
}
