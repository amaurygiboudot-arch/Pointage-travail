package com.amaury.pointage

import org.junit.Assert.*
import org.junit.Test

class CompanyNameUiBinderV2Test {
    @Test fun `renames and deletion render the original template`() {
        val binding = CompanyNameUiBinder.LabelBinding()
        val first = binding.render("Paie Entreprise 1", "Alpha", "")
        assertEquals("Paie Alpha", first)
        val second = binding.render(first, "Beta", "")
        assertEquals("Paie Beta", second)
        assertEquals("Paie Entreprise 1", binding.render(second, "", ""))
    }

    @Test fun `external text replacement is preserved instead of restoring old template`() {
        val binding = CompanyNameUiBinder.LabelBinding()
        binding.render("Entreprise 1", "Alpha", "")
        assertEquals("Autre contenu", binding.render("Autre contenu", "Beta", ""))
    }

    @Test fun `literal names are not regex replacements or new placeholders`() {
        assertEquals("Entreprise 2 $5 / Beta / Entreprise 12",
            CompanyNameUiBinder.replaceCompanyLabels("Entreprise 1 / Entreprise 2 / Entreprise 12", "Entreprise 2 $5", "Beta"))
    }

    @Test fun `only slot two migrated never becomes slot one`() {
        val b = SalaryCompanyStore.Company("legacy_2", "Beta", "")
        val stored = SalaryCompanyStore.ReadResult(listOf(b), true)
        val aliases: (String) -> String? = { if (it == "company_2") b.id else null }
        assertEquals("", CompanyNameUiBinder.companyNameFromV2(stored, 1, aliases))
        assertEquals("Beta", CompanyNameUiBinder.companyNameFromV2(stored, 2, aliases))
    }

    @Test fun `deletion of first company cannot shift second into its slot`() {
        val a = SalaryCompanyStore.Company("a", "Alpha", "")
        val b = SalaryCompanyStore.Company("b", "Beta", "")
        val aliases: (String) -> String? = { when (it) { "company_1" -> "a"; "company_2" -> "b"; else -> null } }
        assertEquals("Alpha", CompanyNameUiBinder.companyNameFromV2(SalaryCompanyStore.ReadResult(listOf(a,b), true), 1, aliases))
        val afterDeletion = SalaryCompanyStore.ReadResult(listOf(b), true)
        assertEquals("", CompanyNameUiBinder.companyNameFromV2(afterDeletion, 1, aliases))
        assertEquals("Beta", CompanyNameUiBinder.companyNameFromV2(afterDeletion, 2, aliases))
    }

    @Test fun `unreliable or ambiguous identity cannot display a company name`() {
        val a = SalaryCompanyStore.Company("a", "Alpha", "")
        assertEquals("", CompanyNameUiBinder.companyNameFromV2(SalaryCompanyStore.ReadResult(listOf(a), false), 1) { "a" })
        assertEquals("", CompanyNameUiBinder.companyNameFromV2(SalaryCompanyStore.ReadResult(listOf(a), true), 1) { null })
    }

    @Test fun `canonical changes and alias identity changes refresh labels`() {
        assertEquals("salary_companies_v2", CompanyNameUiBinder.preferenceFileName(true))
        assertEquals("salary_settings", CompanyNameUiBinder.preferenceFileName(false))
        for (key in listOf("companies", "companies_last_known_good", "company_name", "company2_name", "company_siret", "company2_siret", null)) {
            assertTrue(CompanyNameUiBinder.shouldRefreshForPreference(true, key))
        }
        assertFalse(CompanyNameUiBinder.shouldRefreshForPreference(true, "hourly_rate"))
    }
}
