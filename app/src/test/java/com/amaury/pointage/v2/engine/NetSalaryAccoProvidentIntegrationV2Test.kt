package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.ConventionLegalProfileV2
import com.amaury.pointage.v2.OfficialAccoProvidentContributionParserV2
import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NetSalaryAccoProvidentIntegrationV2Test {
    private val date = LocalDate.of(2026, 9, 30)
    private val classification = ConventionClassificationV2(coefficient = 700)
    private val legacy = PlasturgieProtectionCategoryV2.classify("292", date, 700)

    private fun profile() = ConventionLegalProfileV2(
        companyId = "company",
        idcc = "292",
        siret = "12345678901234",
        professionalStatus = "NON_CADRE",
        classification = classification,
        contractType = "CDI",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = LocalDate.of(2020, 1, 1),
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private fun branchRule() = ConventionProvidentContributionV2.Rule(
        idcc = "292",
        ruleId = "KALI-PROVIDENT-292-700",
        effectiveFrom = LocalDate.of(2025, 3, 14),
        classification = classification,
        professionalStatus = "NON_CADRE",
        tiers = listOf(
            ConventionProvidentContributionV2.SeniorityTier(
                minimumSeniorityMonths = 0,
                bands = listOf(
                    ConventionProvidentContributionV2.Band(
                        label = "Salaire brut",
                        employeeRate = 0.004,
                        employerRate = 0.004
                    )
                )
            )
        ),
        source = "legifrance:KALIARTI000051328426",
        conventionScopeKey = "KALITEXT000030370171",
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2025, 3, 14)
    )

    private fun coverage() = ConventionMatterCoverageV2.Snapshot(
        state = ConventionMatterCoverageV2.State.CONFIRMED_RULES,
        record = ConventionMatterCoverageV2.Record(
            idcc = "292",
            matter = ConventionMatterCoverageV2.Matter.PROVIDENT_CONTRIBUTION,
            effectiveFrom = LocalDate.of(2025, 3, 14),
            classification = classification,
            professionalStatus = "NON_CADRE",
            state = ConventionMatterCoverageV2.State.CONFIRMED_RULES,
            source = "KALI audit test",
            checkedAtMs = 1L,
            authorities = setOf(ConventionMatterCoverageV2.Authority.KALI)
        ),
        reliable = true,
        warnings = emptyList()
    )

    private fun companyRule() = OfficialAccoProvidentContributionParserV2.Rule(
        agreementId = "ACCOTEXT000000000001",
        siret = "12345678901234",
        effectiveFrom = LocalDate.of(2026, 1, 1),
        effectiveTo = null,
        classification = classification,
        professionalStatus = "NON_CADRE",
        minimumSeniorityMonths = 0,
        basis = OfficialAccoProvidentContributionParserV2.Basis.GROSS_SALARY,
        employeeRate = 0.005,
        employerRate = 0.007,
        evidenceExcerpt = "Cotisation de prévoyance sur salaire brut : part salariale 0,5 % et part patronale 0,7 %."
    )

    private fun company(
        actualProvident: Double? = null,
        companyRules: List<OfficialAccoProvidentContributionParserV2.Rule> = listOf(companyRule()),
        guaranteesEquivalent: Boolean? = null,
        sourceKnowledge: Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> = emptyMap()
    ) = CompanyPayrollOverridesV2.Snapshot(
        companyId = "company",
        idcc = "292",
        referenceDate = date,
        entryDate = LocalDate.of(2020, 1, 1),
        seniorityMonths = 80,
        contractType = ContractTypeV2.FULL_TIME,
        contractualWeeklyMinutes = 35 * 60,
        forfaitAnnualDays = null,
        unpaidAbsenceDays = 0,
        hasUnpaidAbsence = false,
        mutualEmployeeAmount = 0.0,
        providentEmployeeAmount = actualProvident,
        transportEmployeeAmount = 0.0,
        employerProtectionTaxableAmount = 0.0,
        employeeProvidentNonDeductibleAmount = 0.0,
        incomeTaxRate = 0.0,
        professionalStatus = "NON_CADRE",
        protectionCategory = legacy,
        warnings = emptyList(),
        alsaceMoselleLocalRegime = false,
        employerMobilityRate = 0.0,
        verifiedProtectionCategory = ProtectionCategoryV2.Result(
            aniCategory = ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2,
            confirmed = true,
            source = "test KALI + APEC"
        ),
        verifiedProvidentClassification = classification,
        verifiedProvidentSeniorityMonths = 80,
        verifiedProvidentRules = listOf(branchRule()),
        verifiedProvidentCoverage = coverage(),
        verifiedProvidentLegalProfile = profile(),
        verifiedCompanyProvidentRules = companyRules,
        verifiedProvidentSourceKnowledge = sourceKnowledge,
        verifiedCompanyProvidentGuaranteesEquivalent = guaranteesEquivalent
    )

    @Test
    fun `equivalence prouvee branche la cotisation ACCO dans le moteur`() {
        val result = NetSalaryEngineV2.calculate(
            gross = 2500.0,
            year = 2026,
            company = company(guaranteesEquivalent = true)
        )

        assertEquals(12.5, result.conventionProvidentEmployee, 0.0001)
        assertEquals(17.5, result.conventionProvidentEmployer, 0.0001)
        assertEquals(12.5, result.companyEmployeeDeductions, 0.0001)
        assertTrue(result.warnings.any { it.contains("ACCO retenue", ignoreCase = true) })
    }

    @Test
    fun `equivalence explicitement fausse conserve la cotisation KALI`() {
        val result = NetSalaryEngineV2.calculate(
            gross = 2500.0,
            year = 2026,
            company = company(guaranteesEquivalent = false)
        )

        assertEquals(10.0, result.conventionProvidentEmployee, 0.0001)
        assertEquals(10.0, result.conventionProvidentEmployer, 0.0001)
        assertEquals(10.0, result.companyEmployeeDeductions, 0.0001)
        assertTrue(result.warnings.any { it.contains("KALI retenue", ignoreCase = true) })
    }

    @Test
    fun `equivalence inconnue bloque la cotisation collective`() {
        val result = NetSalaryEngineV2.calculate(
            gross = 2500.0,
            year = 2026,
            company = company(guaranteesEquivalent = null)
        )

        assertEquals(0.0, result.conventionProvidentEmployee, 0.0001)
        assertEquals(0.0, result.conventionProvidentEmployer, 0.0001)
        assertEquals(0.0, result.companyEmployeeDeductions, 0.0001)
        assertTrue(result.warnings.any { it.contains("L2253-1", ignoreCase = true) })
    }

    @Test
    fun `retenue reelle du bulletin reste prioritaire meme si arbitrage collectif bloque`() {
        val result = NetSalaryEngineV2.calculate(
            gross = 2500.0,
            year = 2026,
            company = company(
                actualProvident = 25.0,
                guaranteesEquivalent = null
            )
        )

        assertEquals(0.0, result.conventionProvidentEmployee, 0.0001)
        assertEquals(25.0, result.companyEmployeeDeductions, 0.0001)
        assertTrue(result.warnings.any { it.contains("L2253-1", ignoreCase = true) })
    }

    @Test
    fun `branche seule avec ACCO inconnu reste bloquee`() {
        val result = NetSalaryEngineV2.calculate(
            gross = 2500.0,
            year = 2026,
            company = company(companyRules = emptyList())
        )

        assertEquals(0.0, result.conventionProvidentEmployee, 0.0001)
        assertEquals(0.0, result.conventionProvidentEmployer, 0.0001)
        assertTrue(result.warnings.any { it.contains("L2253-1", ignoreCase = true) })
    }

    @Test
    fun `branche seule devient calculable seulement avec absence ACCO prouvee`() {
        val result = NetSalaryEngineV2.calculate(
            gross = 2500.0,
            year = 2026,
            company = company(
                companyRules = emptyList(),
                sourceKnowledge = mapOf(
                    PayrollLegalArbitratorV2.Source.ACCO to PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE
                )
            )
        )

        assertEquals(10.0, result.conventionProvidentEmployee, 0.0001)
        assertEquals(10.0, result.conventionProvidentEmployer, 0.0001)
        assertEquals(10.0, result.companyEmployeeDeductions, 0.0001)
    }
}
