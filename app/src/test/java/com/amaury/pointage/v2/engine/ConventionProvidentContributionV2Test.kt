package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ConventionProvidentContributionV2Test {
    private val date = LocalDate.of(2026, 1, 31)
    private val outsideAni = ProtectionCategoryV2.Result(
        aniCategory = ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2,
        confirmed = true,
        source = "test KALI + APEC"
    )

    private fun rule(
        ruleId: String = "KALIARTI000000000001",
        classification: ConventionClassificationV2 = ConventionClassificationV2(coefficient = 700),
        professionalStatus: String? = "NON_CADRE",
        aniCategories: Set<ProtectionCategoryV2.AniCategory> = setOf(ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2),
        effectiveFrom: LocalDate = LocalDate.of(2025, 1, 1),
        extensionStatus: ConventionMinimumSalaryV2.ExtensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom: LocalDate? = LocalDate.of(2025, 1, 1),
        tiers: List<ConventionProvidentContributionV2.SeniorityTier> = listOf(
            ConventionProvidentContributionV2.SeniorityTier(
                minimumSeniorityMonths = 3,
                bands = listOf(
                    ConventionProvidentContributionV2.Band(
                        label = "Salaire jusqu'à 4 PMSS",
                        lowerCeilingMultiple = 0.0,
                        upperCeilingMultiple = 4.0,
                        employeeRate = 0.004,
                        employerRate = 0.004
                    )
                )
            )
        )
    ) = ConventionProvidentContributionV2.Rule(
        idcc = "292",
        ruleId = ruleId,
        effectiveFrom = effectiveFrom,
        classification = classification,
        professionalStatus = professionalStatus,
        aniCategories = aniCategories,
        tiers = tiers,
        source = "Légifrance KALI — test",
        conventionScopeKey = "KALITEXT000000000001",
        extensionStatus = extensionStatus,
        extensionEffectiveFrom = extensionEffectiveFrom
    )

    private fun calculate(
        rules: List<ConventionProvidentContributionV2.Rule> = listOf(rule()),
        classification: ConventionClassificationV2 = ConventionClassificationV2(coefficient = 700),
        professionalStatus: String? = "NON_CADRE",
        protectionCategory: ProtectionCategoryV2.Result? = outsideAni,
        seniorityMonths: Int? = 72,
        gross: Double = 2500.0,
        ceiling: Double? = 4005.0,
        referenceDate: LocalDate = date,
        companyApplicabilityConfirmed: Boolean = false
    ) = ConventionProvidentContributionV2.calculate(
        rules = rules,
        idcc = "292",
        referenceDate = referenceDate,
        classification = classification,
        professionalStatus = professionalStatus,
        protectionCategory = protectionCategory,
        seniorityMonths = seniorityMonths,
        gross = gross,
        applicableMonthlyCeiling = ceiling,
        companyApplicabilityConfirmed = companyApplicabilityConfirmed
    )

    @Test
    fun `barème exact calcule les parts salarié et employeur`() {
        val result = calculate()

        assertTrue(result.reliable)
        assertEquals(10.0, result.employeeAmount!!, 0.001)
        assertEquals(10.0, result.employerAmount!!, 0.001)
        assertEquals(2500.0, result.lines.single().baseAmount, 0.001)
    }

    @Test
    fun `catégorie ANI non confirmée bloque même si l'ancien profil semblerait correspondre`() {
        val result = calculate(
            protectionCategory = ProtectionCategoryV2.Result(
                aniCategory = ProtectionCategoryV2.AniCategory.TO_CONFIRM,
                confirmed = false
            )
        )

        assertFalse(result.reliable)
        assertNull(result.employeeAmount)
        assertNull(result.employerAmount)
        assertTrue(result.warnings.any { it.contains("catégorie ANI") })
    }

    @Test
    fun `règle générale ne sert pas de repli si une règle ANI potentielle ne peut pas être départagée`() {
        val general = rule(
            ruleId = "KALIARTI000000000010",
            aniCategories = emptySet(),
            tiers = listOf(
                ConventionProvidentContributionV2.SeniorityTier(
                    0,
                    listOf(ConventionProvidentContributionV2.Band("général", employeeRate = 0.002, employerRate = 0.002))
                )
            )
        )
        val categorySpecific = rule(ruleId = "KALIARTI000000000011")

        val result = calculate(
            rules = listOf(general, categorySpecific),
            protectionCategory = ProtectionCategoryV2.Result(ProtectionCategoryV2.AniCategory.TO_CONFIRM, false)
        )

        assertFalse(result.reliable)
        assertNull(result.employeeAmount)
    }

    @Test
    fun `coefficient voisin ne reçoit jamais la règle`() {
        val result = calculate(classification = ConventionClassificationV2(coefficient = 710))

        assertFalse(result.reliable)
        assertNull(result.employeeAmount)
    }

    @Test
    fun `statut professionnel voisin ne reçoit jamais la règle`() {
        val result = calculate(professionalStatus = "CADRE")

        assertFalse(result.reliable)
        assertNull(result.employeeAmount)
    }

    @Test
    fun `deux règles de même précision bloquent le calcul`() {
        val other = rule(
            ruleId = "KALIARTI000000000002",
            tiers = listOf(
                ConventionProvidentContributionV2.SeniorityTier(
                    3,
                    listOf(
                        ConventionProvidentContributionV2.Band(
                            "autre taux",
                            upperCeilingMultiple = 4.0,
                            employeeRate = 0.005,
                            employerRate = 0.005
                        )
                    )
                )
            )
        )

        val result = calculate(rules = listOf(rule(), other))

        assertFalse(result.reliable)
        assertNull(result.employeeAmount)
        assertTrue(result.warnings.any { it.contains("se contredisent") })
    }

    @Test
    fun `règle future n'est jamais anticipée`() {
        val result = calculate(
            rules = listOf(rule(effectiveFrom = LocalDate.of(2027, 1, 1)))
        )

        assertFalse(result.reliable)
        assertNull(result.employeeAmount)
    }

    @Test
    fun `extension future bloque hors entreprise signataire`() {
        val futureExtension = rule(extensionEffectiveFrom = LocalDate.of(2027, 1, 1))

        val blocked = calculate(rules = listOf(futureExtension))
        val companyConfirmed = calculate(rules = listOf(futureExtension), companyApplicabilityConfirmed = true)

        assertFalse(blocked.reliable)
        assertTrue(companyConfirmed.reliable)
        assertEquals(10.0, companyConfirmed.employeeAmount!!, 0.001)
    }

    @Test
    fun `taux incohérent et tranches chevauchantes sont refusés structurellement`() {
        val invalidRate = rule(
            tiers = listOf(
                ConventionProvidentContributionV2.SeniorityTier(
                    0,
                    listOf(ConventionProvidentContributionV2.Band("invalide", employeeRate = 1.2, employerRate = 0.0))
                )
            )
        )
        val overlap = rule(
            tiers = listOf(
                ConventionProvidentContributionV2.SeniorityTier(
                    0,
                    listOf(
                        ConventionProvidentContributionV2.Band("A", 0.0, 2.0, 0.004, 0.004),
                        ConventionProvidentContributionV2.Band("B", 1.0, 4.0, 0.004, 0.004)
                    )
                )
            )
        )

        assertFalse(invalidRate.structurallyValid())
        assertFalse(overlap.structurallyValid())
    }

    @Test
    fun `palier d'ancienneté le plus précis est sélectionné`() {
        val tiered = rule(
            tiers = listOf(
                ConventionProvidentContributionV2.SeniorityTier(
                    0,
                    listOf(ConventionProvidentContributionV2.Band("0 mois", employeeRate = 0.004, employerRate = 0.004))
                ),
                ConventionProvidentContributionV2.SeniorityTier(
                    36,
                    listOf(ConventionProvidentContributionV2.Band("36 mois", employeeRate = 0.005, employerRate = 0.005))
                )
            )
        )

        val result = calculate(rules = listOf(tiered), seniorityMonths = 48, ceiling = null)

        assertTrue(result.reliable)
        assertEquals(12.50, result.employeeAmount!!, 0.001)
        assertEquals(36, result.selectedTier!!.minimumSeniorityMonths)
    }

    @Test
    fun `ancienneté manquante bloque un barème à paliers`() {
        val tiered = rule(
            tiers = listOf(
                ConventionProvidentContributionV2.SeniorityTier(
                    0,
                    listOf(ConventionProvidentContributionV2.Band("0 mois", employeeRate = 0.004, employerRate = 0.004))
                ),
                ConventionProvidentContributionV2.SeniorityTier(
                    36,
                    listOf(ConventionProvidentContributionV2.Band("36 mois", employeeRate = 0.005, employerRate = 0.005))
                )
            )
        )

        val result = calculate(rules = listOf(tiered), seniorityMonths = null, ceiling = null)

        assertFalse(result.reliable)
        assertNull(result.employeeAmount)
    }

    @Test
    fun `ancienneté explicitement insuffisante produit zéro au lieu d'inventer une retenue`() {
        val result = calculate(seniorityMonths = 2)

        assertTrue(result.reliable)
        assertTrue(result.eligibilityConfirmed)
        assertEquals(0.0, result.employeeAmount!!, 0.001)
        assertEquals(0.0, result.employerAmount!!, 0.001)
    }

    @Test
    fun `plafond manquant bloque une tranche plafonnée`() {
        val result = calculate(ceiling = null)

        assertFalse(result.reliable)
        assertNull(result.employeeAmount)
    }

    @Test
    fun `assiette brute non plafonnée ne dépend pas du PMSS`() {
        val fullGrossRule = rule(
            tiers = listOf(
                ConventionProvidentContributionV2.SeniorityTier(
                    0,
                    listOf(ConventionProvidentContributionV2.Band("brut total", employeeRate = 0.003, employerRate = 0.006))
                )
            )
        )

        val result = calculate(rules = listOf(fullGrossRule), ceiling = null)

        assertTrue(result.reliable)
        assertEquals(7.50, result.employeeAmount!!, 0.001)
        assertEquals(15.0, result.employerAmount!!, 0.001)
    }

    @Test
    fun `catégorie ANI plus spécifique prime sur règle générale`() {
        val general = rule(
            ruleId = "KALIARTI000000000020",
            aniCategories = emptySet(),
            tiers = listOf(
                ConventionProvidentContributionV2.SeniorityTier(
                    0,
                    listOf(ConventionProvidentContributionV2.Band("général", employeeRate = 0.002, employerRate = 0.002))
                )
            )
        )
        val specific = rule(
            ruleId = "KALIARTI000000000021",
            tiers = listOf(
                ConventionProvidentContributionV2.SeniorityTier(
                    0,
                    listOf(ConventionProvidentContributionV2.Band("ANI", employeeRate = 0.004, employerRate = 0.004))
                )
            )
        )

        val result = calculate(rules = listOf(general, specific), ceiling = null)

        assertTrue(result.reliable)
        assertEquals("KALIARTI000000000021", result.selectedRule!!.ruleId)
        assertEquals(10.0, result.employeeAmount!!, 0.001)
    }
}
