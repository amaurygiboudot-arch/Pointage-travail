package com.amaury.pointage

import java.util.Locale

/** Source unique des fichiers de préférences qui ne doivent jamais quitter le téléphone. */
object BackupSecurityPolicy {
    private val deviceLocalPreferenceFiles = setOf(
        "admin_diagnostics",
        "app_check_status",
        "drive_backup",
        "firebase_device_registry",
        "pointage",
        "recovery_state",
        "update_download",
        "update_push",
        "v2_app_lock"
    )

    fun canTransferPreferenceFile(name: String): Boolean {
        val normalized = name.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank() || normalized in deviceLocalPreferenceFiles) return false
        return !normalized.startsWith("com.google.firebase") &&
            !normalized.startsWith("firebase") &&
            !normalized.contains("google_sign_in") &&
            !normalized.contains("google_app_measurement")
    }
}
