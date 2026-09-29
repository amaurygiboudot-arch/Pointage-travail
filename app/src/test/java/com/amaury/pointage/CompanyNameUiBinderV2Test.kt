package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyNameUiBinderV2Test {
    @Test
    fun `v2 ecoute uniquement le store entreprises canonique`() {
        assertEquals("salary_companies_v2", CompanyNameUiBinder.preferenceFileName(true))
        assertEquals("salary_settings", CompanyNameUiBinder.preferenceFileName(false))
        assertTrue(CompanyNameUiBinder.shouldRefreshForPreference(true, "companies"))
        assertFalse(CompanyNameUiBinder.shouldRefreshForPreference(true, "company_name"))
        assertTrue(CompanyNameUiBinder.shouldRefreshForPreference(false, "company_name"))
    }

    @Test
    fun `les noms visibles viennent du store v2 fiable`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(
                SalaryCompanyStore.Company("company-a", "Alpha", ""),
                SalaryCompanyStore.Company("company-b", "Beta", "")
            ),
            reliable = true
        )

        assertEquals("Alpha", CompanyNameUiBinder.companyNameFromV2(stored, 1))
        assertEquals("Beta", CompanyNameUiBinder.companyNameFromV2(stored, 2))
    }

    @Test
    fun `un store v2 non fiable ne reactive jamais un nom legacy`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(SalaryCompanyStore.Company("company-a", "Nom partiel", "")),
            reliable = false
        )

        assertEquals("", CompanyNameUiBinder.companyNameFromV2(stored, 1))
    }
}
