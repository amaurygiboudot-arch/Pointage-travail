package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialContributionCsgCrdsEmployerProtectionV2Test {
    @Test
    fun `employer protection is added after salary abatement`() {
        val result = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross = 3000.0,
            year = 2026,
            employerProtectionCsgCrdsBaseAmount = 50.0
        )

        val expectedBase = 3000.0 * 0.9825 + 50.0
        listOf("csg_deductible", "csg_taxable", "crds").forEach { id ->
            val line = result.lines.first { it.id == id }
            assertEquals(expectedBase, line.baseAmount, 0.001)
        }
    }

    @Test
    fun `employer protection changes only CSG CRDS employee deductions`() {
        val without = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross = 3000.0,
            year = 2026,
            employerProtectionCsgCrdsBaseAmount = 0.0
        )
        val with = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross = 3000.0,
            year = 2026,
            employerProtectionCsgCrdsBaseAmount = 50.0
        )

        assertEquals(50.0 * (0.068 + 0.024 + 0.005), with.employeeDeductions - without.employeeDeductions, 0.001)
        assertEquals(
            without.lines.first { it.id == "old_age_uncapped" }.baseAmount,
            with.lines.first { it.id == "old_age_uncapped" }.baseAmount,
            0.001
        )
        assertEquals(
            without.lines.first { it.id == "old_age_capped" }.baseAmount,
            with.lines.first { it.id == "old_age_capped" }.baseAmount,
            0.001
        )
    }

    @Test
    fun `missing employer protection remains explicit unknown`() {
        val result = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross = 3000.0,
            year = 2026,
            employerProtectionCsgCrdsBaseAmount = null
        )

        assertEquals(3000.0 * 0.9825, result.lines.first { it.id == "csg_deductible" }.baseAmount, 0.001)
        assertTrue(result.warnings.any { it.contains("part employeur") && it.contains("à confirmer") })
    }
}
