package com.amaury.pointage.v2

import com.amaury.pointage.BackupSecurityPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2SettingsBackupCoverageTest {
    @Test
    fun `celestial settings are managed and watched for V2 backup`() {
        assertTrue(V2BackupManager.isManagedPreferenceFileName("celestial_settings"))
        assertTrue(V2AutoBackupCoordinator.watchedPreferenceFilesForTest().contains("celestial_settings"))
    }

    @Test
    fun `device local stores never trigger V2 auto backup`() {
        val watched = V2AutoBackupCoordinator.watchedPreferenceFilesForTest()
        assertFalse(watched.contains("v2_app_lock"))
        assertFalse(watched.contains("horatrack_v2_gps_state"))
        watched.forEach { name ->
            assertTrue(name, BackupSecurityPolicy.canTransferPreferenceFile(name))
        }
    }
}
