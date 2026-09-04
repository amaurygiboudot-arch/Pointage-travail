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
        assertTrue(V2BackupManager.isSupportedFormatVersion(4))
        assertFalse(V2BackupManager.isSupportedFormatVersion(0))
        assertFalse(V2BackupManager.isSupportedFormatVersion(5))
    }

    @Test
    fun `les fichiers entreprises salaire v2 sont geres par le backup`() {
        assertTrue(V2BackupManager.isManagedPreferenceFileName("salary_companies_v2"))
        assertTrue(V2BackupManager.isManagedPreferenceFileName("salary_company_siret_12345678901234"))
        assertTrue(V2BackupManager.isManagedPreferenceFileName("salary_company_name_entreprise_test"))
        assertTrue(V2BackupManager.isManagedPreferenceFileName("horatrack_v2_planned_pauses"))
        assertFalse(V2BackupManager.isManagedPreferenceFileName("salary_company"))
        assertFalse(V2BackupManager.isManagedPreferenceFileName("firebase_device_registry"))
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
