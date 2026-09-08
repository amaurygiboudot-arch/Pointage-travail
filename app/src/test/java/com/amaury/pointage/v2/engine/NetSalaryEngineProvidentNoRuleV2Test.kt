package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NetSalaryEngineProvidentNoRuleV2Test {
    private val date = LocalDate.of(2026, 1, 31)
    private val classification = ConventionClassificationV2(coefficient = 700)
    private val legacyOutsideAni = PlasturgieProtectionCategoryV2.classify("292", date, 700)
    private val verifiedCategory = ProtectionCategoryV2.Result(
        aniCategory = ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2,
        confirmed = true,
        source = "test KALI + APEC"
    )

    private fun coverage(state: ConventionMatterCoverageV2.State) = ConventionMatterCoverageV2.Snapshot(
        state = state,
        record = ConventionMatterCoverageV2.Record(
            idcc = "292",
            matter = ConventionMatterCoverageV2.Matter.PROVIDENT_CONTRIBUTION,
            effectiveFrom = LocalDate.of(2026, 1, 1),
            effectiveTo = date,
            classification = classification,
            professionalStatus = "NON_CADRE",
            state = state,
            source = "audit KALI test",
            checkedAtMs = 1L,
            authorities = setOf(ConventionMatterCoverageV2.Authority.KALI)
        ),
        reliable = state != ConventionMatterCoverageV2.State.INCOMPLETE,
        warnings = emptyList()
    )

    private fun company(
        coverage: ConventionMatterCoverageV2.Snapshot,
        rules: List<ConventionProvidentContributionV2.Rule> = emptyList()
    ) = CompanyPayrollOverridesV2.Snapshot(
        companyId = "company",
        idcc = "292",
        referenceDate = date,
        entryDate = LocalDate.of(2020, 1, 1),
        seniorityMonths = 72,
        contractType = ContractTypeV2.FULL_TIME,
        contractualWeeklyMinutes = 35 * 60,
        forfaitAnnualDays = null,
        unpaidAbsenceDays = 0,
        hasUnpaidAbsence = false,
        mealAmount = 0.0,
        mutualEmployeeAmount = 0.0,
        providentEmployeeAmount = null,
        transportEmployeeAmount = 0.0,
        employerProtectionTaxableAmount = 0.0,
        employeeProvidentNonDeductibleAmount = 0.0,
        incomeTaxRate = 0.05,
        professionalStatus = "NON_CADRE",
        protectionCategory = legacyOutsideAni,
        warnings = emptyList(),
        alsaceMoselleLocalRegime = false,
        atMpEmployerRate = 0.0,
        benefitsInKindGross = 0.0,
        employerMobilityRate = 0.0,
        verifiedProtectionCategory = verifiedCategory,
        verifiedProvidentClassification = classification,
        verifiedProvidentSeniorityMonths = 72,
        verifiedProvidentRules = rules,
        verifiedProvidentCoverage = coverage
    )

    @Test
    fun `KALI confirmed no rule means verified zero and never legacy fallback`() {
        val result = NetSalaryEngineV2.calculate(
            gross = 2500.0,
            year = 2026,
            company = company(coverage(ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE))
        )

        // Le vieux Plasturgie coefficient 700 calculerait une cotisation. La preuve KALI no-rule
        // doit donc prendre la main et produire explicitement zéro, sans aucun repli historique.
        assertTrue(legacyOutsideAni.confirmed)
        assertEquals(0.0, result.conventionProvidentEmployee, 0.001)
        assertEquals(0.0, result.conventionProvidentEmployer, 0.001)
        assertEquals(0.0, result.companyEmployeeDeductions, 0.001)
        assertTrue(result.netTaxable != null)
        assertTrue(result.warnings.any { it.contains("absence de cotisation explicitement confirmée", ignoreCase = true) })
    }

    @Test
    fun `blocked verified KALI path keeps taxable net unknown instead of using legacy`() {
        val mismatchedRule = ConventionProvidentContributionV2.Rule(
            idcc = "292",
            ruleId = "KALI-PROVIDENT-CONTRIBUTION-KALITEXT000000000001-ARTICLE_2_1",
            effectiveFrom = LocalDate.of(2025, 1, 1),
            classification = classification,
            professionalStatus = "NON_CADRE",
            aniCategories = setOf(ProtectionCategoryV2.AniCategory.ARTICLE_2_1),
            tiers = listOf(
                ConventionProvidentContributionV2.SeniorityTier(
                    minimumSeniorityMonths = 0,
                    bands = listOf(
                        ConventionProvidentContributionV2.Band(
                            label = "Salaire brut total",
                            employeeRate = 0.005,
                            employerRate = 0.007
                        )
                    )
                )
            ),
            source = "Légifrance KALI test",
            conventionScopeKey = "KALITEXT000000000001",
            extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
            extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
        )

        val result = NetSalaryEngineV2.calculate(
            gross = 2500.0,
            year = 2026,
            company = company(
                coverage = coverage(ConventionMatterCoverageV2.State.CONFIRMED_RULES),
                rules = listOf(mismatchedRule)
            )
        )

        assertEquals(0.0, result.conventionProvidentEmployee, 0.001)
        assertEquals(0.0, result.conventionProvidentEmployer, 0.001)
        assertNull(result.netTaxable)
        assertNull(result.incomeTax)
        assertNull(result.netAfterIncomeTax)
        assertTrue(result.warnings.any { it.contains("catégorie ANI", ignoreCase = true) })
        assertTrue(result.warnings.any { it.contains("assiette fiscale incomplète", ignoreCase = true) })
    }
}
