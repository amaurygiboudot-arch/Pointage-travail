package com.amaury.pointage.v2

import org.junit.Assert.assertFalse
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
