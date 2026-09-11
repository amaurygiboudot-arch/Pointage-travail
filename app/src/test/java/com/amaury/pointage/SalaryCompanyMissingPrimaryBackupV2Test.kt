package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SalaryCompanyMissingPrimaryBackupV2Test {
    @Test
    fun `missing backup evidence allows the normal legacy or empty path`() {
        assertNull(
            SalaryCompanyStore.missingPrimaryBackupFailure(
                backupPresent = false,
                backup = null
            )
        )
    }

    @Test
    fun `reliable backup is handled by the normal restoration path`() {
        val reliable = SalaryCompanyStore.ReadResult(
            companies = listOf(SalaryCompanyStore.Company("company_a", "A", "12345678901234")),
            reliable = true
        )

        assertNull(
            SalaryCompanyStore.missingPrimaryBackupFailure(
                backupPresent = true,
                backup = reliable
            )
        )
    }

    @Test
    fun `present but unreadable backup cannot become reliable empty state`() {
        val failure = requireNotNull(
            SalaryCompanyStore.missingPrimaryBackupFailure(
                backupPresent = true,
                backup = null
            )
        )

        assertFalse(failure.reliable)
        assertTrue(failure.companies.isEmpty())
        assertTrue(failure.warnings.any { it.contains("copie locale de secours") })
    }

    @Test
    fun `partially readable corrupt backup keeps recoverable companies only as diagnostics`() {
        val backup = SalaryCompanyStore.decodeCompanies(
            """[
                {"id":"company_a","name":"A","siret":"12345678901234","address":"","conventionName":"","idcc":""},
                "broken-entry"
            ]""".trimIndent()
        )
        val failure = requireNotNull(
            SalaryCompanyStore.missingPrimaryBackupFailure(
                backupPresent = true,
                backup = backup
            )
        )

        assertFalse(backup.reliable)
        assertFalse(failure.reliable)
        assertEquals(listOf("company_a"), failure.companies.map { it.id })
        assertTrue(failure.warnings.any { it.contains("aucune absence") })
    }
}
