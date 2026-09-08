package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.ConventionLegalProfileV2
import com.amaury.pointage.v2.OfficialAccoProvidentContributionParserV2
import com.amaury.pointage.v2.V2ConventionProvidentContributionBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate

class ProvidentContributionLegalArbitrationSameValueV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val classification = ConventionClassificationV2(coefficient = 700)

    @Test
    fun `deux accords entreprise de meme valeur ne creent pas un faux conflit`() {
        val result = ProvidentContributionLegalArbitrationBridgeV2.resolve(
            profile = profile(),
            referenceDate = date,
            branch = branchSnapshot(),
            companyRules = listOf(
                companyRule("ACCOTEXT000000000101"),
                companyRule("ACCOTEXT000000000102")
            ),
            companyGuaranteesEquivalent = true
        )

        assertEquals(PayrollLegalArbitratorV2.State.RESOLVED, result.resolution.state)
        assertEquals(PayrollLegalArbitratorV2.Source.ACCO, result.resolution.selected?.source)
        assertNotNull(result.selectedCompanyRule)
    }

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

    private fun companyRule(agreementId: String) = OfficialAccoProvidentContributionParserV2.Rule(
        agreementId = agreementId,
        siret = "12345678901234",
        effectiveFrom = LocalDate.of(2026, 1, 1),
        effectiveTo = null,
        classification = classification,
        professionalStatus = "NON_CADRE",
        minimumSeniorityMonths = 0,
        basis = OfficialAccoProvidentContributionParserV2.Basis.GROSS_SALARY,
        employeeRate = 0.005,
        employerRate = 0.007,
        evidenceExcerpt = "Cotisation de prévoyance sur salaire brut : part salariale 0,50 %, part patronale 0,70 %."
    )

    private fun branchSnapshot(): V2ConventionProvidentContributionBridge.Snapshot {
        val rule = ConventionProvidentContributionV2.Rule(
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
        return V2ConventionProvidentContributionBridge.Snapshot(
            result = ConventionProvidentContributionV2.Result(
                applicable = true,
                eligibilityConfirmed = true,
                reliable = true,
                selectedRule = rule,
                selectedTier = rule.tiers.single(),
                lines = emptyList(),
                employeeAmount = 10.0,
                employerAmount = 10.0,
                warnings = emptyList()
            ),
            coverage = ConventionMatterCoverageV2.Snapshot(
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
        )
    }
}
