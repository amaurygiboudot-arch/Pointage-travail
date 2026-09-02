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
