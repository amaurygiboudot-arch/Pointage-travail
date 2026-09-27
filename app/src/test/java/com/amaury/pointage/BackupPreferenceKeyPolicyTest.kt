package com.amaury.pointage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPreferenceKeyPolicyTest {
    @Test
    fun `device local preference files never leave the phone`() {
        listOf("telemetry_settings", "location_onboarding", "v2_app_lock", "drive_backup", "horatrack_v2_backup", "horatrack_v2_gps_state")
            .forEach { assertFalse(it, BackupSecurityPolicy.canTransferPreferenceFile(it)) }
    }

    @Test
    fun `transient GPS and smart setup state stays device local`() {
        listOf("pending_point_address", "pending_point_company_bindings").forEach { key ->
            assertFalse(key, BackupPreferenceKeyPolicy.canTransfer("gps_settings", key))
        }
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
        assertTrue(BackupPreferenceKeyPolicy.canTransfer("gps_settings", "zones"))
        assertTrue(BackupPreferenceKeyPolicy.canTransfer("smart_setup", "enabled"))
    }

    @Test
    fun `device presentation and polling state stays local`() {
        assertFalse(BackupPreferenceKeyPolicy.canTransfer("appearance_settings", "custom_image_bg"))
        assertFalse(BackupPreferenceKeyPolicy.canTransfer("appearance_settings", "celestial_night"))
        assertFalse(BackupPreferenceKeyPolicy.canTransfer("navigation_state", "active_tab"))
        assertFalse(BackupPreferenceKeyPolicy.canTransfer("firebase_backend_updates", "notification_permission_requested"))
        assertFalse(BackupPreferenceKeyPolicy.canTransfer("firebase_backend_updates", "last_server_check"))

        assertTrue(BackupPreferenceKeyPolicy.canTransfer("appearance_settings", "visual_theme"))
        assertTrue(BackupPreferenceKeyPolicy.canTransfer("navigation_state", "report_month_ms"))
        assertTrue(BackupPreferenceKeyPolicy.canTransfer("firebase_backend_updates", "known_revision"))
        assertTrue(BackupPreferenceKeyPolicy.canTransfer("celestial_settings", "globe_mode"))
    }
}
