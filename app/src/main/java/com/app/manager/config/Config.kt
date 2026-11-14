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

    /**
     * The Morphe MicroG build is NOT served from apps-v2.json: the manager tracks the
     * MorpheApp/MicroG-RE release stream directly and injects the two catalog entries below at
     * load time. `stable` follows the newest non-prerelease ("Latest") release, `beta` follows
     * the newest pre-release — the APK asset matched to this device's ABI (arm64-v8a /
     * armeabi-v7a / universal fallback).
     */
    const val MORPHE_MICROG_REPO = "MorpheApp/MicroG-RE"

    /**
     * Package the MicroG RE builds install as, and the SHA-256 fingerprint of their signing
     * certificate (as published for AppVerifier/Obtainium). A downloaded MicroG APK is verified
     * against this fingerprint before an install session is committed.
     */
    const val MORPHE_MICROG_PACKAGE = "app.revanced.android.gms"
    const val MORPHE_MICROG_FINGERPRINT =
        "0B:6C:95:15:AF:B1:95:FA:C5:96:01:69:6B:A0:A7:90:7A:0B:21:7C:CF:72:0B:43:14:84:27:CC:F6:43:43:E7"

    /** Last known releases — the offline fallback when the GitHub API cannot be reached. */
    const val MORPHE_MICROG_FALLBACK_STABLE_VERSION = "7.3.1"
    const val MORPHE_MICROG_FALLBACK_STABLE_URL =
        "https://github.com/MorpheApp/MicroG-RE/releases/download/7.3.1/microg-7.3.1.apk"
    const val MORPHE_MICROG_FALLBACK_BETA_VERSION = "7.3.1-dev.2"
    const val MORPHE_MICROG_FALLBACK_BETA_URL =
        "https://github.com/MorpheApp/MicroG-RE/releases/download/7.3.1-dev.2/microg-7.3.1-dev.2.apk"

    /** Morphe publisher avatar, also used by the catalog for Morphe entries. */
    const val MORPHE_ICON_URL = "https://avatars.githubusercontent.com/u/246280111?s=48&v=4"
}
