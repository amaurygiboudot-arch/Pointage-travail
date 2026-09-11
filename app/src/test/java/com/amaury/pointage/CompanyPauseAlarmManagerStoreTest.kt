package com.amaury.pointage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyPauseAlarmManagerStoreTest {
    private val company = SalaryCompanyStore.Company(
        id = "company-a",
        name = "Entreprise A",
        siret = "12345678901234"
    )

    @Test
    fun `un store entreprises non fiable ne programme aucune alarme V2`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(company),
            reliable = false,
            warnings = listOf("store entreprises corrompu")
        )

        assertTrue(CompanyPauseAlarmManager.confirmedCompanies(stored).isEmpty())
        assertFalse(CompanyPauseAlarmManager.isConfirmedCompany(stored, company.id))
    }

    @Test
    fun `une entreprise absente ne peut pas entretenir une alarme orpheline`() {
        val stored = SalaryCompanyStore.ReadResult(emptyList(), reliable = true)

        assertFalse(CompanyPauseAlarmManager.isConfirmedCompany(stored, company.id))
    }

    @Test
    fun `une entreprise confirmee reste disponible pour ses alarmes`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(company),
            reliable = true,
            repairedFromBackup = true,
            warnings = listOf("restauré depuis la copie saine")
        )

        assertTrue(CompanyPauseAlarmManager.confirmedCompanies(stored).single() == company)
        assertTrue(CompanyPauseAlarmManager.isConfirmedCompany(stored, " company-a "))
    }
}
