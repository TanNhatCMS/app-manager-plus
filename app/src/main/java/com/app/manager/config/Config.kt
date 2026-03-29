package com.app.manager.config

/**
 * Application configuration for debug and runtime settings.
 * This object provides centralized control over various app behaviors.
 */
object Config {
    
    /**
     * Enable verbose debug logging throughout the application.
     * When true, detailed logs will be printed for:
     * - SharedPreferences operations (save/load)
     * - App configuration changes
     * - Installation/download events
     * - UI state changes
     * 
     * Set to false for production builds to reduce logcat noise.
     */
    const val ENABLE_LOG = false
    
    /**
     * Log tag prefix for all debug logs
     */
    const val LOG_TAG = "RvMng"
}

