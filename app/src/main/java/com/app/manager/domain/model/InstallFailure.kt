package com.app.manager.domain.model

import android.content.pm.PackageInstaller

/**
 * Why an install failed, classified well enough to pick the right recovery.
 *
 * The distinction that matters is [uninstallCanHelp]: offering "uninstall the old version and
 * retry" is only ever correct when the old version is what blocks the install. For everything
 * else (no space, wrong ABI, damaged APK, Play Protect) uninstalling loses the user's app and
 * data and the retry still fails for the same reason.
 */
enum class InstallFailureReason(val uninstallCanHelp: Boolean) {
    /** The installed build is signed with a different certificate. */
    SIGNATURE_CONFLICT(true),

    /** The APK's versionCode is lower than the installed one. */
    VERSION_DOWNGRADE(true),

    /** Wrong ABI or Android version — this device cannot run the build at all. */
    INCOMPATIBLE(false),

    /** Not enough free storage. */
    STORAGE_FULL(false),

    /** The APK is damaged or truncated. */
    INVALID_APK(false),

    /** Blocked by the system (Play Protect, device policy, unknown-sources setting). */
    BLOCKED(false),

    /** The user declined the system dialog. */
    ABORTED(false),

    /** Anything we cannot classify — never offer uninstall for these. */
    UNKNOWN(false);

    companion object {
        /**
         * Pure classification from a PackageInstaller status code plus its message. The message
         * matters because the framework folds several distinct causes into one public status
         * (e.g. INSTALL_FAILED_VERSION_DOWNGRADE arrives as STATUS_FAILURE_INVALID), so it is
         * checked before the coarse code.
         */
        fun from(statusCode: Int, message: String?): InstallFailureReason {
            val msg = message.orEmpty()
            fun has(vararg needles: String) = needles.any { msg.contains(it, ignoreCase = true) }
            return when {
                // HyperOS reports its own refusal as ABORTED (issue #63) — the user never saw a
                // dialog, so calling it a cancel would leave them nothing to act on.
                has("Permission denied") -> BLOCKED

                statusCode == PackageInstaller.STATUS_FAILURE_ABORTED ||
                    has("aborted", "cancelled", "user denied") -> ABORTED

                statusCode == PackageInstaller.STATUS_FAILURE_STORAGE ||
                    has("INSTALL_FAILED_INSUFFICIENT_STORAGE") -> STORAGE_FULL

                statusCode == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ||
                    has("NO_MATCHING_ABIS", "OLDER_SDK", "NEWER_SDK") -> INCOMPATIBLE

                has("INSTALL_FAILED_VERSION_DOWNGRADE") -> VERSION_DOWNGRADE

                statusCode == PackageInstaller.STATUS_FAILURE_CONFLICT ||
                    has(
                        "signatures do not match",
                        "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
                        "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE"
                    ) -> SIGNATURE_CONFLICT

                statusCode == PackageInstaller.STATUS_FAILURE_INVALID ||
                    has("INSTALL_PARSE_FAILED", "INSTALL_FAILED_INVALID_APK") -> INVALID_APK

                statusCode == PackageInstaller.STATUS_FAILURE_BLOCKED ||
                    has("INSTALL_FAILED_VERIFICATION") -> BLOCKED

                else -> UNKNOWN
            }
        }
    }
}
