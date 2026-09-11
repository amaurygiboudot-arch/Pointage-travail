package com.amaury.pointage.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2CompanyProvidentContributionCompanyLifetimeTest {
    @Test
    fun `entreprise indisponible rend les cotisations ACCO non fiables`() {
        val result = V2CompanyProvidentContributionStore.companyUnavailableResult("company-a")

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.any {
            it.contains("absente", ignoreCase = true) && it.contains("orpheline", ignoreCase = true)
        })
    }
}
