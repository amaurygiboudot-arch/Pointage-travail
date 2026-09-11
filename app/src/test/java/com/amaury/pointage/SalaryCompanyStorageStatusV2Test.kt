package com.amaury.pointage

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SalaryCompanyStorageStatusV2Test {
    @Test
    fun `reliable store without repair has no status message`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = emptyList(),
            reliable = true
        )

        assertNull(salaryCompanyStorageStatusText(stored))
    }

    @Test
    fun `unreliable store is displayed as corruption and never as empty companies`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = emptyList(),
            reliable = false,
            warnings = listOf("aucune entreprise ni absence ne peut être déduite")
        )

        val text = salaryCompanyStorageStatusText(stored).orEmpty()

        assertTrue(text.contains("Stockage des entreprises incohérent"))
        assertTrue(text.contains("n'utilise aucune entreprise ni ancien profil de secours"))
        assertTrue(text.contains("aucune entreprise ni absence"))
    }

    @Test
    fun `automatic backup repair is explicitly visible`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(SalaryCompanyStore.Company("company_a", "A", "")),
            reliable = true,
            repairedFromBackup = true,
            warnings = listOf("stockage principal restauré")
        )

        val text = salaryCompanyStorageStatusText(stored).orEmpty()

        assertTrue(text.contains("restaurées automatiquement"))
        assertTrue(text.contains("stockage principal restauré"))
    }
}
