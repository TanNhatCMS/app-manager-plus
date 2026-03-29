package com.app.manager.util

import android.util.Log
import com.app.manager.config.Config

/**
 * Utility object for conditional logging throughout the application.
 * All log methods check Config.ENABLE_LOG before writing to logcat.
 */
object LogUtil {
    
    /**
     * Log a debug message with optional tag prefix
     * @param tag The log tag (usually class name)
     * @param message The message to log
     */
    fun d(tag: String, message: String) {
        if (Config.ENABLE_LOG) {
            Log.d(tag, "📝 $message")
        }
    }
    
    /**
     * Log an info message
     * @param tag The log tag (usually class name)
     * @param message The message to log
     */
    fun i(tag: String, message: String) {
        if (Config.ENABLE_LOG) {
            Log.i(tag, "ℹ️ $message")
        }
    }
    
    /**
     * Log a warning message
     * @param tag The log tag (usually class name)
     * @param message The message to log
     * @param throwable Optional throwable to include
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        // Warnings are always logged regardless of ENABLE_LOG
        if (throwable != null) {
            Log.w(tag, "⚠️ $message", throwable)
        } else {
            Log.w(tag, "⚠️ $message")
        }
    }
    
    /**
     * Log an error message
     * @param tag The log tag (usually class name)
     * @param message The message to log
     * @param throwable Optional throwable to include
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        // Errors are always logged regardless of ENABLE_LOG
        if (throwable != null) {
            Log.e(tag, "❌ $message", throwable)
        } else {
            Log.e(tag, "❌ $message")
        }
    }
    
    /**
     * Log a verbose message (only when logging is enabled)
     * @param tag The log tag (usually class name)
     * @param message The message to log
     */
    fun v(tag: String, message: String) {
        if (Config.ENABLE_LOG) {
            Log.v(tag, "🔍 $message")
        }
    }
}

