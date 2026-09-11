package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SalaryCompanySiretUniquenessV2Test {
    @Test
    fun `existing company may keep its own siret`() {
        val existing = SalaryCompanyStore.Company("company_a", "A", "12345678901234")
        val stored = SalaryCompanyStore.ReadResult(listOf(existing), reliable = true)
        val updated = existing.copy(name = "A mise à jour")

        assertEquals(
            listOf(updated),
            SalaryCompanyStore.companiesAfterMutation(stored, updated, allowInsert = false)
        )
    }

    @Test
    fun `existing company cannot take another company siret`() {
        val first = SalaryCompanyStore.Company("company_a", "A", "11111111111111")
        val second = SalaryCompanyStore.Company("company_b", "B", "12345678901234")
        val stored = SalaryCompanyStore.ReadResult(listOf(first, second), reliable = true)
        val conflicting = first.copy(siret = "123 456 789 012 34")

        assertNull(
            SalaryCompanyStore.companiesAfterMutation(stored, conflicting, allowInsert = false)
        )
    }

    @Test
    fun `explicit insert cannot create another id for an existing siret`() {
        val existing = SalaryCompanyStore.Company("legacy_1", "A", "12345678901234")
        val stored = SalaryCompanyStore.ReadResult(listOf(existing), reliable = true)
        val duplicate = SalaryCompanyStore.Company("siret_12345678901234", "A bis", "12345678901234")

        assertNull(
            SalaryCompanyStore.companiesAfterMutation(stored, duplicate, allowInsert = true)
        )
    }

    @Test
    fun `blank sirets do not create an identity conflict`() {
        val first = SalaryCompanyStore.Company("company_a", "A", "")
        val second = SalaryCompanyStore.Company("company_b", "B", "")
        val stored = SalaryCompanyStore.ReadResult(listOf(first), reliable = true)

        assertEquals(
            listOf(first, second),
            SalaryCompanyStore.companiesAfterMutation(stored, second, allowInsert = true)
        )
    }

    @Test
    fun `invalid non empty siret is rejected by the central mutation guard`() {
        val existing = SalaryCompanyStore.Company("company_a", "A", "12345678901234")
        val stored = SalaryCompanyStore.ReadResult(listOf(existing), reliable = true)
        val invalid = existing.copy(siret = "123")

        assertNull(
            SalaryCompanyStore.companiesAfterMutation(stored, invalid, allowInsert = false)
        )
    }
}
