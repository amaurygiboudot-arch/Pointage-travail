package com.amaury.pointage

import java.util.Locale

/** Source unique des fichiers de préférences qui ne doivent jamais quitter le téléphone. */
object BackupSecurityPolicy {
    private val deviceLocalPreferenceFiles = setOf(
        "admin_diagnostics",
        "app_check_status",
        "drive_backup",
        "firebase_device_registry",
        "horatrack_v2_backup",
        "horatrack_v2_gps_state",
        "pointage",
        "telemetry_settings",
        "pause_schedule_confirmation",
        "navigation_state",
        "icon_switch_diagnostics",
        "user_feedback",
        "snake_profile",
        "payroll_rates_cache",
        "location_onboarding",
        "horatrack_v2_test_policy",
        "horatrack_local_provident_documents",
        "firebase_backend_updates",
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
