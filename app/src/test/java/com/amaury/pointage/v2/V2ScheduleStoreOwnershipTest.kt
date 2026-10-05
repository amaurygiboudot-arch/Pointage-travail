package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ScheduleStoreOwnershipTest {
    @Test
    fun `deux entreprises utilisent des cles horaires distinctes`() {
        val a = V2ScheduleStore.companyPreferenceKey("company-a", "expected_end_day")
        val b = V2ScheduleStore.companyPreferenceKey("company-b", "expected_end_day")

        assertNotEquals(a, b)
    }

    @Test
    fun `la distance horaire automatique traverse correctement minuit`() {
        assertEquals(10, V2ScheduleStore.circularMinuteDistance(5, 1435))
        assertEquals(15, V2ScheduleStore.circularMinuteDistance(300, 315))
    }

    @Test
    fun `le poste automatique vient uniquement du debut configure le plus proche`() {
        val selected = V2ScheduleStore.nearestConfiguredShiftId(
            entryMinute = 5 * 60 + 3,
            starts = linkedMapOf(
                "morning" to 5 * 60,
                "day" to 8 * 60,
                "afternoon" to 13 * 60,
                "night" to 21 * 60
            )
        )

        assertEquals("morning", selected)
    }

    @Test
    fun `une egalite entre deux horaires reste inconnue au lieu de choisir arbitrairement`() {
        val selected = V2ScheduleStore.nearestConfiguredShiftId(
            entryMinute = 6 * 60,
            starts = linkedMapOf(
                "morning" to 5 * 60,
                "day" to 7 * 60
            )
        )

        assertNull(selected)
    }

    @Test
    fun `la migration legacy est autorisee pour une seule entreprise fiable`() {
        assertTrue(
            V2ScheduleStore.canMigrateLegacySchedule(
                companiesReliable = true,
                confirmedCompanyIds = listOf("company-a"),
                companyId = "company-a"
            )
        )
    }

    @Test
    fun `la migration legacy est refusee avec plusieurs entreprises`() {
        assertFalse(
            V2ScheduleStore.canMigrateLegacySchedule(
                companiesReliable = true,
                confirmedCompanyIds = listOf("company-a", "company-b"),
                companyId = "company-a"
            )
        )
    }

    @Test
    fun `la migration legacy est refusee si le store entreprises est non fiable`() {
        assertFalse(
            V2ScheduleStore.canMigrateLegacySchedule(
                companiesReliable = false,
                confirmedCompanyIds = listOf("company-a"),
                companyId = "company-a"
            )
        )
    }
}
