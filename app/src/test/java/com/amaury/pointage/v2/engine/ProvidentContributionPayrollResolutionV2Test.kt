package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.ConventionLegalProfileV2
import com.amaury.pointage.v2.OfficialAccoProvidentContributionParserV2
import com.amaury.pointage.v2.V2ConventionProvidentContributionBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProvidentContributionPayrollResolutionV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val classification = ConventionClassificationV2(coefficient = 700)

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
            source = "KALI audit",
            checkedAtMs = 1L,
            authorities = setOf(ConventionMatterCoverageV2.Authority.KALI)
        ),
        reliable = true,
        warnings = emptyList()
    )

    private fun branchSnapshot(
        reliable: Boolean = true,
        selectedRule: ConventionProvidentContributionV2.Rule? = branchRule()
    ) = V2ConventionProvidentContributionBridge.Snapshot(
        result = ConventionProvidentContributionV2.Result(
            applicable = selectedRule != null,
            eligibilityConfirmed = selectedRule != null,
            reliable = reliable,
            selectedRule = selectedRule,
            selectedTier = selectedRule?.tiers?.firstOrNull(),
            lines = emptyList(),
            employeeAmount = if (selectedRule == null) null else 10.0,
            employerAmount = if (selectedRule == null) null else 10.0,
            warnings = emptyList()
        ),
        coverage = coverage()
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

    @Test
    fun `equivalence inconnue bloque les montants au lieu de choisir`() {
        val result = ProvidentContributionPayrollResolutionV2.resolve(
            profile = profile(),
            referenceDate = date,
            branch = branchSnapshot(),
            companyRules = listOf(companyRule()),
            gross = 2500.0,
            companyGuaranteesEquivalent = null
        )

        assertFalse(result.reliable)
        assertNull(result.employeeAmount)
        assertNull(result.employerAmount)
        assertNull(result.selectedSource)
    }

    @Test
    fun `equivalence prouvee permet de calculer le taux ACCO sur le brut`() {
        val result = ProvidentContributionPayrollResolutionV2.resolve(
            profile = profile(),
            referenceDate = date,
            branch = branchSnapshot(),
            companyRules = listOf(companyRule()),
            gross = 2500.0,
            companyGuaranteesEquivalent = true
        )

        assertTrue(result.reliable)
        assertEquals(PayrollLegalArbitratorV2.Source.ACCO, result.selectedSource)
        assertEquals(12.5, result.employeeAmount!!, 0.0001)
        assertEquals(17.5, result.employerAmount!!, 0.0001)
    }

    @Test
    fun `equivalence fausse conserve les montants KALI deja calcules`() {
        val result = ProvidentContributionPayrollResolutionV2.resolve(
            profile = profile(),
            referenceDate = date,
            branch = branchSnapshot(),
            companyRules = listOf(companyRule()),
            gross = 2500.0,
            companyGuaranteesEquivalent = false
        )

        assertTrue(result.reliable)
        assertEquals(PayrollLegalArbitratorV2.Source.KALI, result.selectedSource)
        assertEquals(10.0, result.employeeAmount!!, 0.0001)
        assertEquals(10.0, result.employerAmount!!, 0.0001)
    }

    @Test
    fun `branche seule avec ACCO inconnu reste bloquee`() {
        val result = ProvidentContributionPayrollResolutionV2.resolve(
            profile = profile(),
            referenceDate = date,
            branch = branchSnapshot(),
            companyRules = emptyList(),
            gross = 2500.0
        )

        assertFalse(result.reliable)
        assertNull(result.employeeAmount)
        assertNull(result.employerAmount)
    }

    @Test
    fun `branche seule est utilisable si absence ACCO explicitement confirmee`() {
        val result = ProvidentContributionPayrollResolutionV2.resolve(
            profile = profile(),
            referenceDate = date,
            branch = branchSnapshot(),
            companyRules = emptyList(),
            gross = 2500.0,
            sourceKnowledge = mapOf(
                PayrollLegalArbitratorV2.Source.ACCO to PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE
            )
        )

        assertTrue(result.reliable)
        assertEquals(PayrollLegalArbitratorV2.Source.KALI, result.selectedSource)
        assertEquals(10.0, result.employeeAmount!!, 0.0001)
        assertEquals(10.0, result.employerAmount!!, 0.0001)
    }

    @Test
    fun `brut invalide est refuse avant tout calcul`() {
        val result = ProvidentContributionPayrollResolutionV2.resolve(
            profile = profile(),
            referenceDate = date,
            branch = branchSnapshot(),
            companyRules = listOf(companyRule()),
            gross = Double.NaN,
            companyGuaranteesEquivalent = true
        )

        assertFalse(result.reliable)
        assertNull(result.employeeAmount)
        assertNull(result.employerAmount)
        assertTrue(result.warnings.any { it.contains("salaire brut invalide") })
    }
}
