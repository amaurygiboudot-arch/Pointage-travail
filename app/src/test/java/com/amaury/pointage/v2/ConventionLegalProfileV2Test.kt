package com.amaury.pointage.v2

import com.amaury.pointage.SalaryCompanyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConventionLegalProfileV2Test {
    private val company = SalaryCompanyStore.Company(
        id = "company-safe",
        name = "Entreprise sûre",
        siret = "12345678901234",
        idcc = "0086"
    )

    @Test
    fun `un store entreprises non fiable ne fournit jamais de profil meme avec une entree lisible`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(company),
            reliable = false,
            warnings = listOf("stockage incohérent")
        )

        assertNull(ConventionLegalProfileV2.confirmedCompany(stored, company.id))
    }

    @Test
    fun `une entreprise issue d une lecture fiable est selectionnee`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(company),
            reliable = true
        )

        assertEquals(company, ConventionLegalProfileV2.confirmedCompany(stored, company.id))
    }

    @Test
    fun `une lecture restauree depuis la copie saine reste exploitable`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(company),
            reliable = true,
            repairedFromBackup = true,
            warnings = listOf("restauré depuis la copie saine")
        )

        assertEquals(company, ConventionLegalProfileV2.confirmedCompany(stored, company.id))
    }

    @Test
    fun `un identifiant vide ou absent ne selectionne aucune entreprise`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(company),
            reliable = true
        )

        assertNull(ConventionLegalProfileV2.confirmedCompany(stored, " "))
        assertNull(ConventionLegalProfileV2.confirmedCompany(stored, "company-other"))
    }
}
