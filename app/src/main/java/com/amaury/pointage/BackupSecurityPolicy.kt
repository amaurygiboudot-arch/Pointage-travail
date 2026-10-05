package com.amaury.pointage

import java.util.Locale

/** Source unique des fichiers de préférences qui ne doivent jamais quitter le téléphone. */
object BackupSecurityPolicy {
    private val deviceLocalPreferenceFiles = setOf(
        "admin_diagnostics",
        "app_check_status",
        "diamond_lab",
        "drive_backup",
        "firebase_device_registry",
        "horatrack_v2_backup",
        "horatrack_v2_gps_state",
        "horatrack_v2_test_policy",
        "icon_switch_diagnostics",
        "location_onboarding",
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

/**
 * Périmètre fermé de la sauvegarde Firebase des réglages.
 *
 * Cette sauvegarde n'est pas une sauvegarde générale des SharedPreferences : tout nouveau fichier
 * reste local tant qu'il n'a pas été explicitement classé comme réglage transférable.
 */
object CloudSettingsBackupPolicy {
    private val transferablePreferenceFiles = setOf(
        "appearance_settings",
        "gps_settings",
        "navigation_state",
        "place_names",
        "shift_profiles",
        "smart_setup",
        "widget_style"
    )

    fun canTransferPreferenceFile(name: String): Boolean {
        val normalized = name.trim().lowercase(Locale.ROOT)
        return normalized in transferablePreferenceFiles &&
            BackupSecurityPolicy.canTransferPreferenceFile(normalized)
    }
}

/** Source unique des clés de préférences qui peuvent suivre l'utilisateur entre appareils. */
object BackupPreferenceKeyPolicy {
    private val appearanceDeviceLocalKeys = setOf(
        "custom_image_bg",
        "celestial_night"
    )

    private val smartSetupEphemeralKeys = setOf(
        "pending_workplace_zone",
        "pending_workplace_address",
        "pending_workplace_company",
        "proposal_dialog_visible"
    )

    fun canTransfer(preferenceFileName: String, key: String): Boolean {
        val fileName = preferenceFileName.trim().lowercase(Locale.ROOT)
        val normalizedKey = key.trim()
        if (normalizedKey.isBlank()) return false
        if (!BackupSecurityPolicy.canTransferPreferenceFile(fileName)) return false
        if (!GpsPresenceStateKeysV2.isTransferablePreferenceKey(fileName, normalizedKey)) return false

        if (fileName == "appearance_settings" && normalizedKey in appearanceDeviceLocalKeys) return false
        if (fileName == "navigation_state" && normalizedKey == "active_tab") return false
        if (fileName == "smart_setup") {
            if (normalizedKey in smartSetupEphemeralKeys) return false
            if (normalizedKey.startsWith("candidate_enter_")) return false
            if (normalizedKey.startsWith("candidate_days_")) return false
        }
        return true
    }
}
