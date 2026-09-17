package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveBackupOwnerV2Test {
    @Test
    fun `V2 routes every Drive synchronization to the complete snapshot owner`() {
        assertEquals(
            DriveBackupManager.SyncOwner.V2_SNAPSHOT,
            DriveBackupManager.syncOwner(v2Enabled = true)
        )
    }

    @Test
    fun `legacy reports remain isolated behind the explicit V1 route`() {
        assertEquals(
            DriveBackupManager.SyncOwner.LEGACY_REPORTS,
            DriveBackupManager.syncOwner(v2Enabled = false)
        )
    }
}
