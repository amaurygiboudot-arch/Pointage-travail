package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProvidentContributionV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class KaliProvidentContributionAuditV2Test {
    private val referenceDate = LocalDate.of(2026, 1, 31)

    private fun rule(
        extensionStatus: ConventionMinimumSalaryV2.ExtensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom: LocalDate? = LocalDate.of(2025, 1, 1)
    ) = ConventionProvidentContributionV2.Rule(
        idcc = "292",
        ruleId = "KALI-PROVIDENT-CONTRIBUTION-KALITEXT000000000001-OUTSIDE_2_1_2_2",
        effectiveFrom = LocalDate.of(2025, 1, 1),
        professionalStatus = "NON_CADRE",
        aniCategories = setOf(ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2),
        tiers = listOf(
            ConventionProvidentContributionV2.SeniorityTier(
                minimumSeniorityMonths = 3,
                bands = listOf(
                    ConventionProvidentContributionV2.Band(
                        label = "Salaire de référence jusqu'à 4 PMSS",
                        upperCeilingMultiple = 4.0,
                        employeeRate = 0.004,
                        employerRate = 0.004
                    )
                )
            )
        ),
        source = "Légifrance KALI — test",
        conventionScopeKey = "KALITEXT000000000001",
        extensionStatus = extensionStatus,
        extensionEffectiveFrom = extensionEffectiveFrom
    )

    @Test
    fun `couverture complete plus regle sauvegardee et extension active confirme les regles`() {
        val result = KaliProvidentContributionAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            rule = rule(),
            saved = true,
            referenceDate = referenceDate
        )

        assertTrue(result.completed)
        assertEquals(ConventionMatterCoverageV2.State.CONFIRMED_RULES, result.state)
    }

    @Test
    fun `couverture technique incomplete reste incomplete meme avec regle exacte`() {
        val result = KaliProvidentContributionAuditV2.evaluateCompletion(
            technicalCoverageComplete = false,
            rule = rule(),
            saved = true,
            referenceDate = referenceDate
        )

        assertFalse(result.completed)
        assertEquals(ConventionMatterCoverageV2.State.INCOMPLETE, result.state)
    }

    @Test
    fun `regle non sauvegardee reste incomplete`() {
        val result = KaliProvidentContributionAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            rule = rule(),
            saved = false,
            referenceDate = referenceDate
        )

        assertFalse(result.completed)
        assertEquals(ConventionMatterCoverageV2.State.INCOMPLETE, result.state)
    }

    @Test
    fun `absence de regle ne devient jamais absence de cotisation`() {
        val result = KaliProvidentContributionAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            rule = null,
            saved = false,
            referenceDate = referenceDate
        )

        assertFalse(result.completed)
        assertEquals(ConventionMatterCoverageV2.State.INCOMPLETE, result.state)
    }

    @Test
    fun `extension future reste incomplete`() {
        val result = KaliProvidentContributionAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            rule = rule(extensionEffectiveFrom = LocalDate.of(2027, 1, 1)),
            saved = true,
            referenceDate = referenceDate
        )

        assertFalse(result.completed)
        assertEquals(ConventionMatterCoverageV2.State.INCOMPLETE, result.state)
    }

    @Test
    fun `extension inconnue reste incomplete`() {
        val result = KaliProvidentContributionAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            rule = rule(
                extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN,
                extensionEffectiveFrom = null
            ),
            saved = true,
            referenceDate = referenceDate
        )

        assertFalse(result.completed)
        assertEquals(ConventionMatterCoverageV2.State.INCOMPLETE, result.state)
    }
}
