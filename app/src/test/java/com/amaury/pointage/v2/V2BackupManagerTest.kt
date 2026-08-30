package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2BackupManagerTest {
    @Test
    fun `versions de sauvegarde historiques et courante sont acceptees`() {
        assertTrue(V2BackupManager.isSupportedFormatVersion(1))
        assertTrue(V2BackupManager.isSupportedFormatVersion(2))
        assertTrue(V2BackupManager.isSupportedFormatVersion(3))
        assertFalse(V2BackupManager.isSupportedFormatVersion(0))
        assertFalse(V2BackupManager.isSupportedFormatVersion(4))
    }

    @Test
    fun `signature historique identique detecte un doublon exact`() {
        val first = V2BackupManager.historySignature(
            id = "session-1",
            realEntry = 1_000L,
            realExit = 2_000L,
            countedEntry = 1_100L,
            countedExit = 1_900L
        )
        val duplicate = V2BackupManager.historySignature(
            id = "session-1",
            realEntry = 1_000L,
            realExit = 2_000L,
            countedEntry = 1_100L,
            countedExit = 1_900L
        )

        assertEquals(first, duplicate)
    }

    @Test
    fun `signature historique ne confond pas deux sessions aux horaires differents`() {
        val first = V2BackupManager.historySignature(
            id = "session-1",
            realEntry = 1_000L,
            realExit = 2_000L,
            countedEntry = 1_100L,
            countedExit = 1_900L
        )
        val changedExit = V2BackupManager.historySignature(
            id = "session-1",
            realEntry = 1_000L,
            realExit = 2_100L,
            countedEntry = 1_100L,
            countedExit = 2_000L
        )

        assertFalse(first == changedExit)
    }
}
