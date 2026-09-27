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
            "firebase_device_registry",
            "horatrack_v2_gps_state",
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
    fun `smart setup local learning state is never transferable`() {
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
    }

    @Test
    fun `smart setup durable choices remain transferable`() {
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
    fun `existing gps ephemeral policy is still enforced`() {
        GpsPresenceStateKeysV2.EPHEMERAL_KEYS.forEach { key ->
            assertFalse(key, BackupPreferenceKeyPolicy.canTransfer("gps_settings", key))
        }
        assertTrue(BackupPreferenceKeyPolicy.canTransfer("gps_settings", "zones"))
    }
}
