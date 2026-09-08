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

class ProvidentContributionLegalArbitrationBridgeV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val classification = ConventionClassificationV2(coefficient = 700)

    private fun profile(
        siret: String = "12345678901234",
        classification: ConventionClassificationV2 = this.classification,
        entryDate: LocalDate? = LocalDate.of(2020, 1, 1)
    ) = ConventionLegalProfileV2(
        companyId = "company",
        idcc = "292",
        siret = siret,
        professionalStatus = "NON_CADRE",
        classification = classification,
        contractType = "CDI",
        entryDate = entryDate,
        conventionSeniorityDate = entryDate,
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private fun branchRule(
        employeeRate: Double = 0.004,
        employerRate: Double = 0.004
    ): ConventionProvidentContributionV2.Rule {
        val tier = ConventionProvidentContributionV2.SeniorityTier(
            minimumSeniorityMonths = 3,
            bands = listOf(
                ConventionProvidentContributionV2.Band(
                    label = "Salaire brut",
                    employeeRate = employeeRate,
                    employerRate = employerRate
                )
            )
        )
        return ConventionProvidentContributionV2.Rule(
            idcc = "292",
            ruleId = "KALI-PROVIDENT-292-700",
            effectiveFrom = LocalDate.of(2025, 3, 14),
            classification = classification,
            professionalStatus = "NON_CADRE",
            tiers = listOf(tier),
            source = "legifrance:KALIARTI000051328426",
            conventionScopeKey = "KALITEXT000030370171",
            extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
            extensionEffectiveFrom = LocalDate.of(2025, 3, 14)
        )
    }

    private fun coverage(
        state: ConventionMatterCoverageV2.State = ConventionMatterCoverageV2.State.CONFIRMED_RULES,
        reliable: Boolean = true,
        authorities: Set<ConventionMatterCoverageV2.Authority> = setOf(ConventionMatterCoverageV2.Authority.KALI)
    ) = ConventionMatterCoverageV2.Snapshot(
        state = state,
        record = ConventionMatterCoverageV2.Record(
            idcc = "292",
            matter = ConventionMatterCoverageV2.Matter.PROVIDENT_CONTRIBUTION,
            effectiveFrom = LocalDate.of(2025, 3, 14),
            classification = classification,
            professionalStatus = "NON_CADRE",
            state = state,
            source = "KALI audit",
            checkedAtMs = 1L,
            authorities = authorities
        ),
        reliable = reliable,
        warnings = emptyList()
    )

    private fun branchSnapshot(
        rule: ConventionProvidentContributionV2.Rule? = branchRule(),
        coverage: ConventionMatterCoverageV2.Snapshot = coverage()
    ): V2ConventionProvidentContributionBridge.Snapshot {
        val tier = rule?.tiers?.firstOrNull()
        return V2ConventionProvidentContributionBridge.Snapshot(
            result = ConventionProvidentContributionV2.Result(
                applicable = rule != null,
                eligibilityConfirmed = rule != null,
                reliable = rule != null && coverage.reliable,
                selectedRule = rule,
                selectedTier = tier,
                lines = emptyList(),
                employeeAmount = if (rule == null) 0.0 else 10.0,
                employerAmount = if (rule == null) 0.0 else 10.0,
                warnings = emptyList()
            ),
            coverage = coverage
        )
    }

    private fun companyRule(
        agreementId: String = "ACCOTEXT000000000001",
        siret: String = "12345678901234",
        classification: ConventionClassificationV2 = this.classification,
        minimumSeniorityMonths: Int = 0,
        employeeRate: Double = 0.005,
        employerRate: Double = 0.007
    ) = OfficialAccoProvidentContributionParserV2.Rule(
        agreementId = agreementId,
        siret = siret,
        effectiveFrom = LocalDate.of(2026, 1, 1),
        effectiveTo = null,
        classification = classification,
        professionalStatus = "NON_CADRE",
        minimumSeniorityMonths = minimumSeniorityMonths,
        basis = OfficialAccoProvidentContributionParserV2.Basis.GROSS_SALARY,
        employeeRate = employeeRate,
        employerRate = employerRate,
        evidenceExcerpt = "Cotisation de prévoyance sur salaire brut : part salariale et patronale explicites."
    )

    @Test
    fun `branche et entreprise sans equivalence exigent une revue`() {
        val result = ProvidentContributionLegalArbitrationBridgeV2.resolve(
            profile = profile(),
            referenceDate = date,
            branch = branchSnapshot(),
            companyRules = listOf(companyRule()),
            companyGuaranteesEquivalent = null
        )

        assertEquals(PayrollLegalArbitratorV2.State.REVIEW_REQUIRED, result.resolution.state)
        assertNull(result.selectedBranchRule)
        assertNull(result.selectedCompanyRule)
    }

    @Test
    fun `equivalence explicitement fausse conserve la branche`() {
        val result = ProvidentContributionLegalArbitrationBridgeV2.resolve(
            profile(),
            date,
            branchSnapshot(),
            listOf(companyRule()),
            companyGuaranteesEquivalent = false
        )

        assertEquals(PayrollLegalArbitratorV2.State.RESOLVED, result.resolution.state)
        assertTrue(result.selectedBranchRule != null)
        assertNull(result.selectedCompanyRule)
    }

    @Test
    fun `equivalence explicitement vraie permet l accord entreprise`() {
        val result = ProvidentContributionLegalArbitrationBridgeV2.resolve(
            profile(),
            date,
            branchSnapshot(),
            listOf(companyRule()),
            companyGuaranteesEquivalent = true
        )

        assertEquals(PayrollLegalArbitratorV2.State.RESOLVED, result.resolution.state)
        assertNull(result.selectedBranchRule)
        assertEquals("ACCOTEXT000000000001", result.selectedCompanyRule?.agreementId)
    }

    @Test
    fun `branche seule avec acco inconnu reste en revue`() {
        val result = ProvidentContributionLegalArbitrationBridgeV2.resolve(
            profile(),
            date,
            branchSnapshot(),
            emptyList()
        )

        assertEquals(PayrollLegalArbitratorV2.State.REVIEW_REQUIRED, result.resolution.state)
    }

    @Test
    fun `branche seule devient resolue seulement si absence acco confirmee`() {
        val result = ProvidentContributionLegalArbitrationBridgeV2.resolve(
            profile(),
            date,
            branchSnapshot(),
            emptyList(),
            sourceKnowledge = mapOf(
                PayrollLegalArbitratorV2.Source.ACCO to PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE
            )
        )

        assertEquals(PayrollLegalArbitratorV2.State.RESOLVED, result.resolution.state)
        assertTrue(result.selectedBranchRule != null)
    }

    @Test
    fun `entreprise seule avec branche inconnue reste en revue`() {
        val incompleteBranch = branchSnapshot(
            rule = null,
            coverage = coverage(
                state = ConventionMatterCoverageV2.State.INCOMPLETE,
                reliable = false,
                authorities = emptySet()
            )
        )
        val result = ProvidentContributionLegalArbitrationBridgeV2.resolve(
            profile(),
            date,
            incompleteBranch,
            listOf(companyRule()),
            companyGuaranteesEquivalent = true
        )

        assertEquals(PayrollLegalArbitratorV2.State.REVIEW_REQUIRED, result.resolution.state)
        assertNull(result.selectedCompanyRule)
    }

    @Test
    fun `deux accords entreprise incompatibles provoquent un conflit`() {
        val result = ProvidentContributionLegalArbitrationBridgeV2.resolve(
            profile(),
            date,
            branchSnapshot(),
            listOf(
                companyRule(agreementId = "ACCOTEXT000000000001", employeeRate = 0.005),
                companyRule(agreementId = "ACCOTEXT000000000002", employeeRate = 0.006)
            ),
            companyGuaranteesEquivalent = true
        )

        assertEquals(PayrollLegalArbitratorV2.State.CONFLICT, result.resolution.state)
        assertFalse(result.resolved)
    }

    @Test
    fun `mauvais siret et coefficient voisin ne sont jamais consideres`() {
        val result = ProvidentContributionLegalArbitrationBridgeV2.resolve(
            profile(),
            date,
            branchSnapshot(),
            listOf(
                companyRule(siret = "99999999999999"),
                companyRule(
                    agreementId = "ACCOTEXT000000000003",
                    classification = ConventionClassificationV2(coefficient = 800)
                )
            ),
            sourceKnowledge = mapOf(
                PayrollLegalArbitratorV2.Source.ACCO to PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE
            )
        )

        assertEquals(PayrollLegalArbitratorV2.State.RESOLVED, result.resolution.state)
        assertTrue(result.resolution.considered.none { it.source == PayrollLegalArbitratorV2.Source.ACCO })
        assertTrue(result.selectedBranchRule != null)
    }

    @Test
    fun `anciennete entreprise insuffisante ne confirme pas son champ`() {
        val recentProfile = profile(entryDate = LocalDate.of(2026, 8, 1))
        val result = ProvidentContributionLegalArbitrationBridgeV2.resolve(
            recentProfile,
            date,
            branchSnapshot(),
            listOf(companyRule(minimumSeniorityMonths = 3)),
            companyGuaranteesEquivalent = true
        )

        assertTrue(result.resolution.considered.none { it.source == PayrollLegalArbitratorV2.Source.ACCO })
        assertEquals(PayrollLegalArbitratorV2.State.REVIEW_REQUIRED, result.resolution.state)
    }

    @Test
    fun `regle kali brute sans couverture fiable ne devient jamais candidate`() {
        val untrusted = branchSnapshot(
            rule = branchRule(),
            coverage = coverage(
                state = ConventionMatterCoverageV2.State.INCOMPLETE,
                reliable = false,
                authorities = emptySet()
            )
        )
        val result = ProvidentContributionLegalArbitrationBridgeV2.resolve(
            profile(),
            date,
            untrusted,
            emptyList(),
            sourceKnowledge = mapOf(
                PayrollLegalArbitratorV2.Source.ACCO to PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE,
                PayrollLegalArbitratorV2.Source.KALI to PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE
            )
        )

        assertTrue(result.resolution.considered.none { it.source == PayrollLegalArbitratorV2.Source.KALI })
        assertEquals(PayrollLegalArbitratorV2.State.REVIEW_REQUIRED, result.resolution.state)
    }
}
