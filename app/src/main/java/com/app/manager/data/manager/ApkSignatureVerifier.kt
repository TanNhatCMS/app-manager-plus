package com.app.manager.data.manager

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Reads the SHA-256 fingerprints of an APK's signing certificates, so a downloaded build can be
 * checked against the fingerprint a publisher announces (MorpheApp/MicroG-RE publishes one for
 * AppVerifier/Obtainium) before an install session is committed.
 */
object ApkSignatureVerifier {

    private const val TAG = "ApkSignatureVerifier"

    /**
     * Uppercase colon-separated SHA-256 fingerprints of every signing certificate in the APK at
     * [apkPath] (e.g. `0B:6C:...:E7`), or null when the file is missing or unreadable. An
     * unreadable signature is never treated as a mismatch — the real installer has the final word.
     */
    fun sha256Fingerprints(apkPath: String, packageManager: PackageManager): Set<String>? {
        if (!File(apkPath).exists()) return null
        return runCatching {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val info: PackageInfo = packageManager.getPackageArchiveInfo(apkPath, flags) ?: return null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signing = info.signingInfo ?: return null
                val certs = if (signing.hasMultipleSigners()) {
                    signing.apkContentsSigners
                } else {
                    signing.signingCertificateHistory
                }
                certs.map { fingerprint(it.toByteArray()) }.toSet()
            } else {
                @Suppress("DEPRECATION")
                val signatures = info.signatures ?: return null
                signatures.map { fingerprint(it.toByteArray()) }.toSet()
            }
        }.getOrElse { error ->
            Log.w(TAG, "Could not read signatures from $apkPath", error)
            null
        }
    }

    /** True when at least one of the APK's certificates matches [expectedSha256]. */
    fun matches(apkPath: String, expectedSha256: String, packageManager: PackageManager): Boolean {
        val actual = sha256Fingerprints(apkPath, packageManager) ?: return true
        val expected = normalize(expectedSha256)
        return actual.any { it == expected }
    }

    private fun fingerprint(certificateBytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificateBytes)
            .joinToString(":") { "%02X".format(it) }

    /** Normalize a fingerprint for comparison: uppercase, colon-separated byte pairs. */
    private fun normalize(value: String): String =
        value.replace(":", "").replace(" ", "").uppercase().chunked(2).joinToString(":")
}
