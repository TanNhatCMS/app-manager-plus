package com.app.manager.domain.model

/**
 * Configuration model for app settings
 */
data class AppConfig(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: Language = Language.VIETNAMESE,
    val compactMode: Boolean = true, // Default to compact mode enabled
    val debugLogging: Boolean = true,
    val downloadPath: String = ""
)

/**
 * Theme mode options
 */
enum class ThemeMode {
    LIGHT, DARK, SYSTEM
}

/**
 * Supported languages with ISO codes and emoji flags
 */
enum class Language(
    val code: String, 
    val displayName: String, 
    val flagEmoji: String
) {
    ENGLISH("en", "English", "🇬🇧"),
    VIETNAMESE("vi", "Tiếng Việt", "🇻🇳"),
}
