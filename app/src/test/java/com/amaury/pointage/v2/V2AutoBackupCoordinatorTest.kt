package com.amaury.pointage.v2

import com.amaury.pointage.SalaryCompanyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V2AutoBackupCoordinatorTest {
    @Test
    fun `store fiable ajoute les fichiers attendus et conserve ceux deja presents`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(
                SalaryCompanyStore.Company(
                    id = "company-a",
                    name = "Entreprise A",
                    siret = "12345678901234"
                )
            ),
            reliable = true
        )

        val files = V2AutoBackupCoordinator.dynamicSalaryFileNames(
            stored,
            listOf("salary_company_legacy", "other_preferences")
        )

        assertEquals(listOf("salary_company_company-a", "salary_company_legacy"), files)
    }

    @Test
    fun `store non fiable ne deduit aucune entreprise mais conserve tous les fichiers salariaux du disque`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(
                SalaryCompanyStore.Company(
                    id = "partial-company",
                    name = "Partielle",
                    siret = ""
                )
            ),
            reliable = false,
            warnings = listOf("store entreprises corrompu")
        )

        val files = V2AutoBackupCoordinator.dynamicSalaryFileNames(
            stored,
            listOf("salary_company_a", "salary_company_b", "unrelated")
        )

        assertEquals(listOf("salary_company_a", "salary_company_b"), files)
        assertTrue(files.none { it.contains("partial-company") })
    }

    @Test
    fun `les doublons de fichiers dynamiques sont elimines`() {
        val company = SalaryCompanyStore.Company(
            id = "same",
            name = "Entreprise",
            siret = "12345678901234"
        )
        val stored = SalaryCompanyStore.ReadResult(listOf(company), reliable = true)

        val files = V2AutoBackupCoordinator.dynamicSalaryFileNames(
            stored,
            listOf("salary_company_same", "salary_company_same")
        )

        assertEquals(listOf("salary_company_same"), files)
    }
}
