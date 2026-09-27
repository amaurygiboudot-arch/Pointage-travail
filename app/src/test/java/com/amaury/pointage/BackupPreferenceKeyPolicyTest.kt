package com.amaury.pointage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPreferenceKeyPolicyTest {
    @Test
    fun `device local preference files never leave the phone`() {
        listOf(
            "telemetry_settings",
            "location_onboarding",
            "v2_app_lock",
            "drive_backup",
            "horatrack_v2_gps_state"
        ).forEach { name ->
            assertFalse(name, BackupSecurityPolicy.canTransferPreferenceFile(name))
        }
    }

    @Test
    fun `smart setup transient learning state is never transferable`() {
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
    fun `device presentation state is not restored on another phone`() {
        assertFalse(BackupPreferenceKeyPolicy.canTransfer("appearance_settings", "custom_image_bg"))
        assertFalse(BackupPreferenceKeyPolicy.canTransfer("appearance_settings", "celestial_night"))
        assertTrue(BackupPreferenceKeyPolicy.canTransfer("appearance_settings", "visual_theme"))
        assertTrue(BackupPreferenceKeyPolicy.canTransfer("appearance_settings", "app_bg"))

        assertFalse(BackupPreferenceKeyPolicy.canTransfer("navigation_state", "active_tab"))
        assertTrue(BackupPreferenceKeyPolicy.canTransfer("navigation_state", "report_month_ms"))
    }

    @Test
    fun `local backend notification state stays local`() {
        assertFalse(
            BackupPreferenceKeyPolicy.canTransfer(
                "firebase_backend_updates",
                "notification_permission_requested"
            )
        )
        assertFalse(
            BackupPreferenceKeyPolicy.canTransfer("firebase_backend_updates", "last_server_check")
        )
        assertTrue(
            BackupPreferenceKeyPolicy.canTransfer("firebase_backend_updates", "known_revision")
        )
        assertTrue(
            BackupPreferenceKeyPolicy.canTransfer("firebase_backend_updates", "popup_revision")
        )
    }

    @Test
    fun `existing gps ephemeral policy remains enforced`() {
        GpsPresenceStateKeysV2.EPHEMERAL_KEYS.forEach { key ->
            assertFalse(key, BackupPreferenceKeyPolicy.canTransfer("gps_settings", key))
        }
        assertTrue(BackupPreferenceKeyPolicy.canTransfer("gps_settings", "zones"))
    }
}
