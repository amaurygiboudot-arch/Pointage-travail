package com.amaury.pointage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSecurityPolicyTest {
    @Test
    fun `les secrets et etats propres au telephone ne sont jamais transferables`() {
        listOf(
            "v2_app_lock",
            "admin_diagnostics",
            "recovery_state",
            "update_download",
            "update_push",
            "app_check_status",
            "diamond_lab",
            "firebase_device_registry",
            "horatrack_v2_backup",
            "horatrack_v2_gps_state",
            "horatrack_v2_test_policy",
            "icon_switch_diagnostics",
            "location_onboarding",
            "drive_backup",
            "pointage",
            " V2_APP_LOCK "
        ).forEach { name ->
            assertFalse(name, BackupSecurityPolicy.canTransferPreferenceFile(name))
        }
    }

    @Test
    fun `les stockages Firebase et Google internes ne sont jamais transferables`() {
        listOf(
            "com.google.firebase.auth",
            "firebase_installation",
            "default_google_sign_in_account",
            "google_app_measurement_settings"
        ).forEach { name ->
            assertFalse(name, BackupSecurityPolicy.canTransferPreferenceFile(name))
        }
    }

    @Test
    fun `les reglages fonctionnels restent transferables`() {
        listOf(
            "horatrack_v2_test_runtime",
            "gps_settings",
            "shift_profiles",
            "appearance_settings",
            "widget_style",
            "place_names",
            "smart_setup"
        ).forEach { name ->
            assertTrue(name, BackupSecurityPolicy.canTransferPreferenceFile(name))
        }
    }
}

class BackupPreferenceKeyPolicyTest {
    @Test
    fun `smart setup learning state stays device local`() {
        listOf(
            "candidate_enter_zone-1",
            "candidate_days_zone-1",
            "pending_workplace_zone",
            "pending_workplace_address",
            "pending_workplace_company",
            "proposal_dialog_visible"
        ).forEach { key ->
            assertFalse(key, BackupPreferenceKeyPolicy.canTransfer("smart_setup", key))
        }

        listOf(
            "initialized",
            "enabled",
            "learn_workplace",
            "learn_pauses",
            "candidate_rejected_zone-1"
        ).forEach { key ->
            assertTrue(key, BackupPreferenceKeyPolicy.canTransfer("smart_setup", key))
        }
    }

    @Test
    fun `appearance navigation and backend device state do not cross devices`() {
        assertFalse(BackupPreferenceKeyPolicy.canTransfer("appearance_settings", "custom_image_bg"))
        assertFalse(BackupPreferenceKeyPolicy.canTransfer("appearance_settings", "celestial_night"))
        assertTrue(BackupPreferenceKeyPolicy.canTransfer("appearance_settings", "visual_theme"))
        assertTrue(BackupPreferenceKeyPolicy.canTransfer("appearance_settings", "app_bg"))

        assertFalse(BackupPreferenceKeyPolicy.canTransfer("navigation_state", "active_tab"))
        assertTrue(BackupPreferenceKeyPolicy.canTransfer("navigation_state", "report_month_ms"))

        listOf(
            "notification_permission_requested",
            "last_server_check",
            "known_revision",
            "popup_revision"
        ).forEach { key ->
            assertFalse(key, BackupPreferenceKeyPolicy.canTransfer("firebase_backend_updates", key))
        }
    }

    @Test
    fun `existing gps ephemeral policy remains enforced`() {
        GpsPresenceStateKeysV2.EPHEMERAL_KEYS.forEach { key ->
            assertFalse(key, BackupPreferenceKeyPolicy.canTransfer("gps_settings", key))
        }
        assertTrue(BackupPreferenceKeyPolicy.canTransfer("gps_settings", "zones"))
    }
}

class CloudSettingsBackupPolicyTest {
    @Test
    fun `Firebase settings backup only accepts explicitly reviewed settings files`() {
        listOf(
            "appearance_settings",
            "gps_settings",
            "navigation_state",
            "place_names",
            "shift_profiles",
            "smart_setup",
            "widget_style"
        ).forEach { name ->
            assertTrue(name, CloudSettingsBackupPolicy.canTransferPreferenceFile(name))
        }
    }

    @Test
    fun `employee facts salary stores and unknown files stay local by default`() {
        listOf(
            "horatrack_v2_meal_fact_journal",
            "horatrack_v2_test_runtime",
            "salary_settings",
            "salary_companies_v2",
            "salary_company_siret_12345678901234",
            "welcome_preview",
            "future_unreviewed_preferences"
        ).forEach { name ->
            assertFalse(name, CloudSettingsBackupPolicy.canTransferPreferenceFile(name))
        }
    }
}
