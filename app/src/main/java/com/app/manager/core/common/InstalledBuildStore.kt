package com.app.manager.core.common

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What this manager installed for a package: written when its own install succeeds, so the patch
 * version of the build on the device is known exactly rather than guessed (see [PatchUpdate]).
 *
 * Only trusted while [versionName] and [lastUpdateTime] still match the installed package — any
 * other install (a browser download, another store) changes lastUpdateTime and retires it.
 */
@Serializable
data class InstalledBuildRecord(
    val versionName: String,
    val patchVersion: String,
    val lastUpdateTime: Long
)

/**
 * One JSON record per package, kept in SharedPreferences and cached in memory. It is written only
 * from this manager's own PackageInstaller success result.
 */
@Singleton
class InstalledBuildStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = HashMap<String, InstalledBuildRecord?>()

    fun get(packageName: String): InstalledBuildRecord? {
        if (cache.containsKey(packageName)) return cache[packageName]
        val record = prefs.getString(packageName, null)?.let { raw ->
            runCatching { json.decodeFromString<InstalledBuildRecord>(raw) }.getOrNull()
        }
        cache[packageName] = record
        return record
    }

    fun put(packageName: String, record: InstalledBuildRecord) {
        cache[packageName] = record
        prefs.edit().putString(packageName, json.encodeToString(InstalledBuildRecord.serializer(), record)).apply()
    }

    fun remove(packageName: String) {
        cache[packageName] = null
        prefs.edit().remove(packageName).apply()
    }

    private companion object {
        const val PREFS_NAME = "installed_builds"
    }
}
