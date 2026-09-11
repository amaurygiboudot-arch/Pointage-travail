package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SalaryCompanyLegacyMigrationV2Test {
    private fun entry(
        id: String,
        siret: String,
        sourceSiret: String = siret
    ) = SalaryCompanyStore.LegacyMigrationEntry(
        company = SalaryCompanyStore.Company(
            id = id,
            name = "Entreprise",
            siret = siret
        ),
        sourceSiret = sourceSiret,
        contractType = "CDI",
        hourlyRate = "13.70",
        weeklyHours = "35",
        mealAmount = "5.38",
        coefficient = "700",
        entryDate = "01/01/2024"
    )

    @Test
    fun `empty legacy source is a valid no data preflight`() {
        assertTrue(SalaryCompanyStore.validateLegacyMigrationEntries(emptyList()))
    }

    @Test
    fun `two unique valid legacy companies pass preflight`() {
        val entries = listOf(
            entry("siret_12345678901234", "12345678901234"),
            entry("siret_98765432109876", "98765432109876")
        )

        assertTrue(SalaryCompanyStore.validateLegacyMigrationEntries(entries))
    }

    @Test
    fun `formatted legacy siret with spaces remains supported`() {
        val value = entry(
            id = "siret_12345678901234",
            siret = "12345678901234",
            sourceSiret = "123 456 789 01234"
        )

        assertTrue(SalaryCompanyStore.validateLegacyMigrationEntries(listOf(value)))
    }

    @Test
    fun `duplicate legacy siret is rejected before persistence`() {
        val entries = listOf(
            entry("siret_12345678901234", "12345678901234"),
            entry("siret_12345678901234", "12345678901234")
        )

        assertFalse(SalaryCompanyStore.validateLegacyMigrationEntries(entries))
    }

    @Test
    fun `malformed non blank legacy siret is rejected`() {
        val value = entry(
            id = "siret_12345678901234",
            siret = "12345678901234",
            sourceSiret = "SIRET 12345678901234"
        )

        assertFalse(SalaryCompanyStore.validateLegacyMigrationEntries(listOf(value)))
    }

    @Test
    fun `wrong length legacy siret is rejected`() {
        val value = entry(
            id = "siret_1234567890123",
            siret = "1234567890123"
        )

        assertFalse(SalaryCompanyStore.validateLegacyMigrationEntries(listOf(value)))
    }

    @Test
    fun `failed migration becomes unreliable read instead of reliable absence`() {
        val company = SalaryCompanyStore.Company(
            id = "legacy_1",
            name = "Ancienne entreprise",
            siret = ""
        )
        val failure = SalaryCompanyStore.legacyMigrationReadFailure(
            SalaryCompanyStore.LegacyMigrationResult(
                SalaryCompanyStore.LegacyMigrationStatus.FAILED,
                listOf(company)
            )
        )

        requireNotNull(failure)
        assertFalse(failure.reliable)
        assertEquals(listOf(company), failure.companies)
        assertTrue(failure.warnings.any { it.contains("migration") })
        assertTrue(failure.warnings.any { it.contains("aucune absence") })
    }

    @Test
    fun `no data migration does not manufacture a storage failure`() {
        val failure = SalaryCompanyStore.legacyMigrationReadFailure(
            SalaryCompanyStore.LegacyMigrationResult(SalaryCompanyStore.LegacyMigrationStatus.NO_DATA)
        )

        assertNull(failure)
    }

    @Test
    fun `saved migration does not manufacture a storage failure`() {
        val failure = SalaryCompanyStore.legacyMigrationReadFailure(
            SalaryCompanyStore.LegacyMigrationResult(SalaryCompanyStore.LegacyMigrationStatus.SAVED)
        )

        assertNull(failure)
    }
}
