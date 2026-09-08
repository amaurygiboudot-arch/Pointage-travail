package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ConventionProvidentBenefitV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val classification = ConventionClassificationV2(coefficient = 910)

    private fun category(
        value: ProtectionCategoryV2.AniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
        confirmed: Boolean = true
    ) = ProtectionCategoryV2.Result(aniCategory = value, confirmed = confirmed)

    private fun deathGuarantee() = ConventionProvidentBenefitV2.Guarantee(
        family = ConventionProvidentBenefitV2.Family.DEATH_CAPITAL,
        label = "Capital décès 100 % du salaire annuel de référence",
        formula = ConventionProvidentBenefitV2.Formula(
            basis = ConventionProvidentBenefitV2.Basis.ANNUAL_REFERENCE_SALARY,
            coefficient = 1.0
        ),
        evidenceArticleIds = setOf("KALIARTI000000000001")
    )

    private fun rule(
        id: String = "r1",
        idcc: String = "292",
        coefficient: Int = 910,
        status: String = "CADRE",
        ani: Set<ProtectionCategoryV2.AniCategory> = setOf(ProtectionCategoryV2.AniCategory.ARTICLE_2_1),
        seniority: Int = 0,
        extensionFrom: LocalDate = LocalDate.of(2025, 1, 1),
        effectiveFrom: LocalDate = LocalDate.of(2025, 1, 1)
    ) = ConventionProvidentBenefitV2.Rule(
        idcc = idcc,
        ruleId = id,
        effectiveFrom = effectiveFrom,
        classification = ConventionClassificationV2(coefficient = coefficient),
        professionalStatus = status,
        aniCategories = ani,
        minimumSeniorityMonths = seniority,
        guarantees = listOf(deathGuarantee()),
        source = "Légifrance KALI — KALITEXT000000000001",
        conventionScopeKey = "KALITEXT000000000001",
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = extensionFrom
    )

    @Test
    fun `règle exacte retourne uniquement les garanties vérifiées`() {
        val result = ConventionProvidentBenefitV2.resolve(
            rules = listOf(rule()),
            idcc = "0292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "CADRE",
            protectionCategory = category(),
            seniorityMonths = 80
        )

        assertTrue(result.reliable)
        assertEquals(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL, result.guarantees.single().family)
        assertEquals("r1", result.selectedRule?.ruleId)
    }

    @Test
    fun `coefficient voisin ne déborde jamais`() {
        val result = ConventionProvidentBenefitV2.resolve(
            rules = listOf(rule(coefficient = 920)),
            idcc = "292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "CADRE",
            protectionCategory = category(),
            seniorityMonths = 80
        )

        assertFalse(result.reliable)
        assertTrue(result.guarantees.isEmpty())
    }

    @Test
    fun `catégorie ANI non confirmée bloque une règle dépendante`() {
        val result = ConventionProvidentBenefitV2.resolve(
            rules = listOf(rule()),
            idcc = "292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "CADRE",
            protectionCategory = category(confirmed = false),
            seniorityMonths = 80
        )

        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("ANI") })
    }

    @Test
    fun `extension future bloque les garanties actuelles`() {
        val result = ConventionProvidentBenefitV2.resolve(
            rules = listOf(rule(extensionFrom = LocalDate.of(2027, 1, 1))),
            idcc = "292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "CADRE",
            protectionCategory = category(),
            seniorityMonths = 80
        )

        assertFalse(result.reliable)
        assertTrue(result.guarantees.isEmpty())
    }

    @Test
    fun `ancienneté requise mais inconnue bloque`() {
        val result = ConventionProvidentBenefitV2.resolve(
            rules = listOf(rule(seniority = 3)),
            idcc = "292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "CADRE",
            protectionCategory = category(),
            seniorityMonths = null
        )

        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("ancienneté") })
    }

    @Test
    fun `ancienneté insuffisante donne un droit vide mais fiable`() {
        val result = ConventionProvidentBenefitV2.resolve(
            rules = listOf(rule(seniority = 12)),
            idcc = "292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "CADRE",
            protectionCategory = category(),
            seniorityMonths = 6
        )

        assertTrue(result.reliable)
        assertTrue(result.guarantees.isEmpty())
        assertNull(result.selectedRule)
    }

    @Test
    fun `deux règles de même précision bloquent même si elles ont la même famille`() {
        val result = ConventionProvidentBenefitV2.resolve(
            rules = listOf(rule(id = "r1"), rule(id = "r2")),
            idcc = "292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "CADRE",
            protectionCategory = category(),
            seniorityMonths = 80
        )

        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("plusieurs") })
    }

    @Test
    fun `formule incohérente est structurellement refusée`() {
        val invalid = deathGuarantee().copy(
            formula = ConventionProvidentBenefitV2.Formula(
                basis = ConventionProvidentBenefitV2.Basis.FIXED_EURO,
                coefficient = 1.0,
                fixedAmount = 1000.0
            )
        )
        val invalidRule = rule().copy(guarantees = listOf(invalid))

        assertFalse(invalidRule.structurallyValid())
    }
}
