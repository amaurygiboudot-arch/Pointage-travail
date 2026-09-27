package com.amaury.pointage

import java.util.Locale

/** Source unique des fichiers de préférences qui ne doivent jamais quitter le téléphone. */
object BackupSecurityPolicy {
    private val deviceLocalPreferenceFiles = setOf(
        "admin_diagnostics",
        "app_check_status",
        "drive_backup",
        "firebase_device_registry",
        "horatrack_v2_gps_state",
        "location_onboarding",
        "pointage",
        "recovery_state",
        "update_download",
        "update_push",
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


/** Politique unique de transfert au niveau des clés de préférences. */
object BackupPreferenceKeyPolicy {
    private val appearanceLocalKeys = setOf(
        "custom_image_bg",
        "celestial_night"
    )

    private val backendUpdateLocalKeys = setOf(
        "notification_permission_requested",
        "last_server_check"
    )

    private val smartSetupEphemeralExact = setOf(
        "pending_workplace_zone",
        "pending_workplace_address",
        "pending_workplace_company",
        "proposal_dialog_visible"
    )

    fun canTransfer(preferenceFileName: String, key: String): Boolean {
        if (!BackupSecurityPolicy.canTransferPreferenceFile(preferenceFileName)) return false
        if (!GpsPresenceStateKeysV2.isTransferablePreferenceKey(preferenceFileName, key)) return false

        if (preferenceFileName == "appearance_settings" && key in appearanceLocalKeys) return false
        if (preferenceFileName == "navigation_state" && key == "active_tab") return false
        if (preferenceFileName == "firebase_backend_updates" && key in backendUpdateLocalKeys) return false

        if (preferenceFileName == "smart_setup") {
            if (key in smartSetupEphemeralExact) return false
            if (key.startsWith("candidate_enter_")) return false
            if (key.startsWith("candidate_days_")) return false
        }
        return true
    }
}
