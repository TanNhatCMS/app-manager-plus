package com.app.manager.data.local.preferences

import android.content.Context
import android.os.Environment
import com.app.manager.domain.model.AppConfig
import com.app.manager.domain.model.Language
import com.app.manager.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAppConfig(): AppConfig {
        val theme = sharedPreferences.getString(KEY_THEME, ThemeMode.SYSTEM.name)
            ?.let { name -> ThemeMode.entries.find { it.name == name } }
            ?: ThemeMode.SYSTEM

        val languageCode = sharedPreferences.getString(KEY_LANGUAGE, Language.VIETNAMESE.code) ?: Language.VIETNAMESE.code
        val language = Language.entries.find { it.code.equals(languageCode, ignoreCase = true) }
            ?: Language.entries.find { lang -> lang.code.substringBefore('-').equals(languageCode.substringBefore('-'), ignoreCase = true) }
            ?: Language.VIETNAMESE

        val compactMode = sharedPreferences.getBoolean(KEY_COMPACT_MODE, true)
        val debugLogging = sharedPreferences.getBoolean(KEY_DEBUG_LOGGING, true)
        val defaultDownloadPath = defaultDownloadPath()
        val downloadPath = (sharedPreferences.getString(KEY_DOWNLOAD_PATH, defaultDownloadPath)
            ?: defaultDownloadPath).ifBlank { defaultDownloadPath }

        return AppConfig(
            themeMode = theme,
            language = language,
            compactMode = compactMode,
            debugLogging = debugLogging,
            downloadPath = downloadPath
        )
    }

    fun saveAppConfig(config: AppConfig) {
        sharedPreferences.edit()
            .putString(KEY_THEME, config.themeMode.name)
            .putString(KEY_LANGUAGE, config.language.code)
            .putBoolean(KEY_COMPACT_MODE, config.compactMode)
            .putBoolean(KEY_DEBUG_LOGGING, config.debugLogging)
            .putString(KEY_DOWNLOAD_PATH, config.downloadPath.ifBlank { defaultDownloadPath() })
            .apply()
    }

    fun isAutoInstallEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_AUTO_INSTALL, true)
    }

    fun getPendingInstallPath(packageName: String): String? {
        return sharedPreferences.getString("pending_install_$packageName", null)
    }

    fun savePendingInstallPath(packageName: String, path: String) {
        sharedPreferences.edit()
            .putString("pending_install_$packageName", path)
            .apply()
    }

    fun removeKey(key: String) {
        sharedPreferences.edit().remove(key).apply()
    }

    private fun defaultDownloadPath(): String {
        val base = Environment.getExternalStorageDirectory()
        return File(base, "AppManager").absolutePath
    }

    companion object {
        private const val PREFS_NAME = "ReVancedManagerPreferences"
        private const val KEY_THEME = "theme"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_COMPACT_MODE = "compact_mode"
        private const val KEY_DEBUG_LOGGING = "debug_logging"
        private const val KEY_DOWNLOAD_PATH = "download_path"
        private const val KEY_AUTO_INSTALL = "auto_install"
    }
}

