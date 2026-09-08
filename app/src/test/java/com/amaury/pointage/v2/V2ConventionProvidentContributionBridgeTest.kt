package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProvidentContributionV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class V2ConventionProvidentContributionBridgeTest {
    private val date = LocalDate.of(2026, 1, 31)
    private val classification = ConventionClassificationV2(coefficient = 700)
    private val profile = ConventionLegalProfileV2(
        companyId = "company",
        idcc = "292",
        siret = "",
        professionalStatus = "NON_CADRE",
        classification = classification,
        contractType = null,
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = null,
        weeklyHours = null,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )
    private val category = ProtectionCategoryV2.Result(
        aniCategory = ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2,
        confirmed = true,
        source = "test KALI + APEC"
    )

    private fun rule() = ConventionProvidentContributionV2.Rule(
        idcc = "292",
        ruleId = "KALI-PROVIDENT-CONTRIBUTION-KALITEXT000000000001-OUTSIDE_2_1_2_2",
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = classification,
        professionalStatus = "NON_CADRE",
        aniCategories = setOf(ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2),
        tiers = listOf(
            ConventionProvidentContributionV2.SeniorityTier(
                minimumSeniorityMonths = 0,
                bands = listOf(
                    ConventionProvidentContributionV2.Band(
                        label = "Salaire brut total",
                        employeeRate = 0.004,
                        employerRate = 0.006
                    )
                )
            )
        ),
        source = "Légifrance KALI",
        conventionScopeKey = "KALITEXT000000000001",
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
    )

    private fun coverage(
        state: ConventionMatterCoverageV2.State = ConventionMatterCoverageV2.State.CONFIRMED_RULES,
        authorities: Set<ConventionMatterCoverageV2.Authority> = setOf(ConventionMatterCoverageV2.Authority.KALI)
    ): ConventionMatterCoverageV2.Snapshot {
        val record = ConventionMatterCoverageV2.Record(
            idcc = "292",
            matter = ConventionMatterCoverageV2.Matter.PROVIDENT_CONTRIBUTION,
            effectiveFrom = LocalDate.of(2026, 1, 1),
            effectiveTo = LocalDate.of(2026, 1, 31),
            classification = classification,
            professionalStatus = "NON_CADRE",
            state = state,
            source = "audit test",
            checkedAtMs = 1L,
            authorities = authorities
        )
        return ConventionMatterCoverageV2.Snapshot(
            state = state,
            record = record,
            reliable = state != ConventionMatterCoverageV2.State.INCOMPLETE,
            warnings = if (state == ConventionMatterCoverageV2.State.INCOMPLETE) listOf("audit incomplet") else emptyList()
        )
    }

    @Test
    fun `barème KALI confirmé est calculé par le bridge`() {
        val result = V2ConventionProvidentContributionBridge.resolve(
            profile = profile,
            referenceDate = date,
            protectionCategory = category,
            rules = listOf(rule()),
            coverage = coverage(),
            gross = 2500.0,
            applicableMonthlyCeiling = null,
            seniorityMonths = 72
        ).result

        assertTrue(result.reliable)
        assertTrue(result.applicable)
        assertEquals(10.0, result.employeeAmount!!, 0.0001)
        assertEquals(15.0, result.employerAmount!!, 0.0001)
    }

    @Test
    fun `règle stockée ne contourne jamais une couverture incomplète`() {
        val result = V2ConventionProvidentContributionBridge.resolve(
            profile = profile,
            referenceDate = date,
            protectionCategory = category,
            rules = listOf(rule()),
            coverage = coverage(ConventionMatterCoverageV2.State.INCOMPLETE),
            gross = 2500.0,
            applicableMonthlyCeiling = null,
            seniorityMonths = 72
        ).result

        assertFalse(result.reliable)
        assertFalse(result.applicable)
        assertNull(result.employeeAmount)
        assertTrue(result.warnings.any { it.contains("audit officiel", ignoreCase = true) })
    }

    @Test
    fun `CONFIRMED_RULES sans autorité KALI reste bloqué`() {
        val result = V2ConventionProvidentContributionBridge.resolve(
            profile = profile,
            referenceDate = date,
            protectionCategory = category,
            rules = listOf(rule()),
            coverage = coverage(authorities = emptySet()),
            gross = 2500.0,
            applicableMonthlyCeiling = null,
            seniorityMonths = 72
        ).result

        assertFalse(result.reliable)
        assertNull(result.employeeAmount)
        assertTrue(result.warnings.any { it.contains("KALI") })
    }

    @Test
    fun `catégorie ANI non confirmée bloque le calcul`() {
        val result = V2ConventionProvidentContributionBridge.resolve(
            profile = profile,
            referenceDate = date,
            protectionCategory = category.copy(confirmed = false),
            rules = listOf(rule()),
            coverage = coverage(),
            gross = 2500.0,
            applicableMonthlyCeiling = null,
            seniorityMonths = 72
        ).result

        assertFalse(result.reliable)
        assertNull(result.employeeAmount)
        assertTrue(result.warnings.any { it.contains("catégorie ANI", ignoreCase = true) })
    }

    @Test
    fun `ancienneté conventionnelle prime sur la date entrée`() {
        val withConventionSeniority = profile.copy(
            entryDate = LocalDate.of(2020, 1, 1),
            conventionSeniorityDate = LocalDate.of(2024, 1, 31)
        )

        assertEquals(24, V2ConventionProvidentContributionBridge.seniorityMonths(withConventionSeniority, date))
    }
}
