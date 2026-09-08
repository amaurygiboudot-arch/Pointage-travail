package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ConventionProvidentContributionAdvancedV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val category = ProtectionCategoryV2.Result(
        aniCategory = ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2,
        confirmed = true,
        source = "test KALI + APEC"
    )

    private fun rule(
        bands: List<ConventionProvidentContributionV2.Band>
    ) = ConventionProvidentContributionV2.Rule(
        idcc = "292",
        ruleId = "KALI-PROVIDENT-MULTIBAND-TEST",
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = ConventionClassificationV2(coefficient = 700),
        professionalStatus = "NON_CADRE",
        aniCategories = setOf(ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2),
        tiers = listOf(
            ConventionProvidentContributionV2.SeniorityTier(
                minimumSeniorityMonths = 0,
                bands = bands
            )
        ),
        source = "Légifrance KALI — test multi-tranches",
        conventionScopeKey = "KALITEXT000000000901",
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
    )

    private fun calculate(
        rule: ConventionProvidentContributionV2.Rule,
        gross: Double,
        ceiling: Double = 4000.0,
        companyDefaultAllocationConfirmed: Boolean = false
    ) = ConventionProvidentContributionV2.calculate(
        rules = listOf(rule),
        idcc = "292",
        referenceDate = date,
        classification = ConventionClassificationV2(coefficient = 700),
        professionalStatus = "NON_CADRE",
        protectionCategory = category,
        seniorityMonths = 72,
        gross = gross,
        applicableMonthlyCeiling = ceiling,
        companyDefaultAllocationConfirmed = companyDefaultAllocationConfirmed
    )

    @Test
    fun `salaire traversant deux tranches calcule chaque base une seule fois`() {
        val result = calculate(
            rule(
                listOf(
                    ConventionProvidentContributionV2.Band(
                        label = "0 à 1 PMSS",
                        lowerCeilingMultiple = 0.0,
                        upperCeilingMultiple = 1.0,
                        employeeRate = 0.004,
                        employerRate = 0.006
                    ),
                    ConventionProvidentContributionV2.Band(
                        label = "1 à 4 PMSS",
                        lowerCeilingMultiple = 1.0,
                        upperCeilingMultiple = 4.0,
                        employeeRate = 0.008,
                        employerRate = 0.012
                    )
                )
            ),
            gross = 5000.0
        )

        assertTrue(result.reliable)
        assertEquals(2, result.lines.size)
        assertEquals(4000.0, result.lines[0].baseAmount, 0.001)
        assertEquals(1000.0, result.lines[1].baseAmount, 0.001)
        assertEquals(24.0, result.employeeAmount!!, 0.001)
        assertEquals(36.0, result.employerAmount!!, 0.001)
    }

    @Test
    fun `salaire au dessus du plafond final ne cotise jamais au dela de quatre PMSS`() {
        val result = calculate(
            rule(
                listOf(
                    ConventionProvidentContributionV2.Band("0 à 1 PMSS", 0.0, 1.0, 0.004, 0.006),
                    ConventionProvidentContributionV2.Band("1 à 4 PMSS", 1.0, 4.0, 0.008, 0.012)
                )
            ),
            gross = 20_000.0
        )

        assertTrue(result.reliable)
        assertEquals(4000.0, result.lines[0].baseAmount, 0.001)
        assertEquals(12_000.0, result.lines[1].baseAmount, 0.001)
        assertEquals(112.0, result.employeeAmount!!, 0.001)
        assertEquals(168.0, result.employerAmount!!, 0.001)
    }

    @Test
    fun `repartition conventionnelle par defaut ne calcule rien sans confirmation entreprise`() {
        val flexible = rule(
            listOf(
                ConventionProvidentContributionV2.Band(
                    label = "brut total",
                    employeeRate = 0.004,
                    employerRate = 0.004,
                    minimumTotalRate = 0.008,
                    minimumEmployerRate = 0.004,
                    allocationRule = ConventionProvidentContributionV2.AllocationRule.DEFAULT_MODIFIABLE_BY_COMPANY_AGREEMENT
                )
            )
        )

        val result = calculate(flexible, gross = 2500.0)

        assertFalse(result.reliable)
        assertNull(result.employeeAmount)
        assertNull(result.employerAmount)
        assertTrue(result.warnings.any { it.contains("accord d'entreprise") })
    }

    @Test
    fun `repartition par defaut devient calculable seulement apres confirmation entreprise`() {
        val flexible = rule(
            listOf(
                ConventionProvidentContributionV2.Band(
                    label = "brut total",
                    employeeRate = 0.004,
                    employerRate = 0.004,
                    minimumTotalRate = 0.008,
                    minimumEmployerRate = 0.004,
                    allocationRule = ConventionProvidentContributionV2.AllocationRule.DEFAULT_MODIFIABLE_BY_COMPANY_AGREEMENT
                )
            )
        )

        val result = calculate(
            rule = flexible,
            gross = 2500.0,
            companyDefaultAllocationConfirmed = true
        )

        assertTrue(result.reliable)
        assertEquals(10.0, result.employeeAmount!!, 0.001)
        assertEquals(10.0, result.employerAmount!!, 0.001)
        assertTrue(result.warnings.any { it.contains("confirmation") })
    }

    @Test
    fun `repartition modifiable sans minima complets est structurellement refusee`() {
        val invalid = ConventionProvidentContributionV2.Band(
            label = "incomplet",
            employeeRate = 0.004,
            employerRate = 0.004,
            minimumTotalRate = 0.008,
            minimumEmployerRate = null,
            allocationRule = ConventionProvidentContributionV2.AllocationRule.DEFAULT_MODIFIABLE_BY_COMPANY_AGREEMENT
        )

        assertFalse(invalid.structurallyValid())
    }
}
