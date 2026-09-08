package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class VerifiedProvidentBenefitProviderV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val classification = ConventionClassificationV2(coefficient = 910)

    private fun profile() = ConventionLegalProfileV2(
        companyId = "c1",
        idcc = "292",
        siret = "12345678901234",
        professionalStatus = "CADRE",
        classification = classification,
        contractType = "CDI",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = LocalDate.of(2020, 1, 1),
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private fun category() = ProtectionCategoryV2.Result(
        aniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
        confirmed = true
    )

    private fun rule() = ConventionProvidentBenefitV2.Rule(
        idcc = "292",
        ruleId = "death",
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = classification,
        professionalStatus = "CADRE",
        aniCategories = setOf(ProtectionCategoryV2.AniCategory.ARTICLE_2_1),
        minimumSeniorityMonths = 0,
        guarantees = listOf(
            ConventionProvidentBenefitV2.Guarantee(
                family = ConventionProvidentBenefitV2.Family.DEATH_CAPITAL,
                label = "Capital décès 100 % salaire annuel",
                formula = ConventionProvidentBenefitV2.Formula(
                    basis = ConventionProvidentBenefitV2.Basis.ANNUAL_REFERENCE_SALARY,
                    coefficient = 1.0
                ),
                socialSecurityTreatment = ConventionProvidentBenefitV2.SocialSecurityTreatment.NOT_APPLICABLE,
                evidenceArticleIds = setOf("KALIARTI000000000001")
            )
        ),
        source = "Légifrance KALI — KALITEXT000000000001",
        conventionScopeKey = "KALITEXT000000000001",
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
    )

    private fun coverage(
        state: ConventionMatterCoverageV2.State,
        authorities: Set<ConventionMatterCoverageV2.Authority> = setOf(ConventionMatterCoverageV2.Authority.KALI),
        reliable: Boolean = state != ConventionMatterCoverageV2.State.INCOMPLETE
    ): ConventionMatterCoverageV2.Snapshot {
        val record = ConventionMatterCoverageV2.Record(
            idcc = "292",
            matter = ConventionMatterCoverageV2.Matter.PROVIDENT_BENEFITS,
            effectiveFrom = LocalDate.of(2026, 9, 1),
            effectiveTo = LocalDate.of(2026, 9, 30),
            classification = classification,
            professionalStatus = "CADRE",
            state = state,
            source = "Légifrance KALI",
            checkedAtMs = 1L,
            authorities = authorities
        )
        return ConventionMatterCoverageV2.Snapshot(state, record, reliable, emptyList())
    }

    @Test
    fun `couverture règles confirmée retourne uniquement les droits stockés vérifiés`() {
        val result = VerifiedProvidentBenefitProviderV2.resolve(
            profile = profile(),
            referenceDate = date,
            protectionCategory = category(),
            seniorityMonths = 80,
            rules = listOf(rule()),
            coverage = coverage(ConventionMatterCoverageV2.State.CONFIRMED_RULES)
        )

        assertTrue(result.reliable)
        assertEquals(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL, result.guarantees.single().family)
    }

    @Test
    fun `couverture no rule KALI explicite donne liste vide fiable`() {
        val result = VerifiedProvidentBenefitProviderV2.resolve(
            profile = profile(),
            referenceDate = date,
            protectionCategory = category(),
            seniorityMonths = 80,
            rules = emptyList(),
            coverage = coverage(ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE)
        )

        assertTrue(result.reliable)
        assertTrue(result.guarantees.isEmpty())
    }

    @Test
    fun `no rule sans autorité KALI est refusé`() {
        val result = VerifiedProvidentBenefitProviderV2.resolve(
            profile = profile(),
            referenceDate = date,
            protectionCategory = category(),
            seniorityMonths = 80,
            rules = emptyList(),
            coverage = coverage(
                ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE,
                authorities = setOf(ConventionMatterCoverageV2.Authority.APEC)
            )
        )

        assertFalse(result.reliable)
    }

    @Test
    fun `couverture incomplète bloque même si une règle valide est en cache`() {
        val result = VerifiedProvidentBenefitProviderV2.resolve(
            profile = profile(),
            referenceDate = date,
            protectionCategory = category(),
            seniorityMonths = 80,
            rules = listOf(rule()),
            coverage = coverage(ConventionMatterCoverageV2.State.INCOMPLETE, reliable = false)
        )

        assertFalse(result.reliable)
        assertTrue(result.guarantees.isEmpty())
    }
}
