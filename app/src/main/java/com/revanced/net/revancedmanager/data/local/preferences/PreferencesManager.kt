package com.revanced.net.revancedmanager.data.local.preferences

import android.content.Context
import com.revanced.net.revancedmanager.domain.model.AppConfig
import com.revanced.net.revancedmanager.domain.model.Language
import com.revanced.net.revancedmanager.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
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

        val languageCode = sharedPreferences.getString(KEY_LANGUAGE, Language.ENGLISH.code) ?: Language.ENGLISH.code
        val language = Language.entries.find { it.code.equals(languageCode, ignoreCase = true) }
            ?: Language.entries.find { lang -> lang.code.substringBefore('-').equals(languageCode.substringBefore('-'), ignoreCase = true) }
            ?: Language.ENGLISH

        val compactMode = sharedPreferences.getBoolean(KEY_COMPACT_MODE, true)

        return AppConfig(
            themeMode = theme,
            language = language,
            compactMode = compactMode
        )
    }

    fun saveAppConfig(config: AppConfig) {
        sharedPreferences.edit()
            .putString(KEY_THEME, config.themeMode.name)
            .putString(KEY_LANGUAGE, config.language.code)
            .putBoolean(KEY_COMPACT_MODE, config.compactMode)
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

    companion object {
        private const val PREFS_NAME = "ReVancedManagerPreferences"
        private const val KEY_THEME = "theme"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_COMPACT_MODE = "compact_mode"
        private const val KEY_AUTO_INSTALL = "auto_install"
    }
}
