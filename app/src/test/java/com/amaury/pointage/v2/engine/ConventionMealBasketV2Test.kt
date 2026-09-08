package com.amaury.pointage.v2.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ConventionMealBasketV2Test {
    private val date = LocalDate.of(2026, 9, 8)

    private fun rule(
        classification: ConventionClassificationV2 = ConventionClassificationV2(coefficient = 700),
        territoryCodes: Set<String> = emptySet(),
        excludedEmployments: Set<String> = emptySet()
    ) = ConventionMealBasketV2.Rule(
        idcc = "292",
        ruleId = "KALI-MEAL-KALITEXT000000000001-DAY",
        benefitId = "DAY_BASKET",
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = classification,
        professionalStatus = "NON_CADRE",
        territoryCodes = territoryCodes,
        excludedEmployments = excludedEmployments,
        deliveryMode = ConventionMealBasketV2.DeliveryMode.CASH_ALLOWANCE,
        amountFormula = ConventionMealBasketV2.AmountFormula.FixedEuro(6.0),
        eligibilityAnyOf = listOf(
            ConventionMealBasketV2.EligibilityGroup(
                listOf(ConventionMealBasketV2.Condition.WorkedDay)
            )
        ),
        source = "Légifrance KALI",
        conventionScopeKey = "KALITEXT000000000001",
        evidenceArticleIds = setOf("KALIARTI000000000001"),
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
    )

    @Test
    fun `règle complète est structurellement valide`() {
        assertTrue(rule().structurallyValid())
    }

    @Test
    fun `faux identifiant KALI est refusé`() {
        assertFalse(rule().copy(conventionScopeKey = "ACCORD-REPAS").structurallyValid())
        assertFalse(rule().copy(evidenceArticleIds = setOf("ARTICLE-45")).structurallyValid())
    }

    @Test
    fun `classification voisine ne traverse jamais le scope`() {
        val result = ConventionMealBasketV2.scopeRules(
            rules = listOf(rule()),
            idcc = "292",
            referenceDate = date,
            classification = ConventionClassificationV2(coefficient = 800),
            professionalStatus = "NON_CADRE",
            territoryCode = null
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `règle territoriale exige le territoire employeur`() {
        val result = ConventionMealBasketV2.scopeRules(
            rules = listOf(rule(territoryCodes = setOf("85"))),
            idcc = "292",
            referenceDate = date,
            classification = ConventionClassificationV2(coefficient = 700),
            professionalStatus = "NON_CADRE",
            territoryCode = null
        )

        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("territoire", ignoreCase = true) })
    }

    @Test
    fun `territoire exact conserve la règle locale`() {
        val result = ConventionMealBasketV2.scopeRules(
            rules = listOf(rule(territoryCodes = setOf("85"))),
            idcc = "292",
            referenceDate = date,
            classification = ConventionClassificationV2(coefficient = 700),
            professionalStatus = "NON_CADRE",
            territoryCode = "85"
        )

        assertTrue(result.reliable)
        assertTrue(result.rules.size == 1)
    }

    @Test
    fun `emploi exclu exige emploi local puis bloque uniquement cet emploi`() {
        val withoutEmployment = ConventionMealBasketV2.scopeRules(
            rules = listOf(rule(excludedEmployments = setOf("gardien"))),
            idcc = "292",
            referenceDate = date,
            classification = ConventionClassificationV2(coefficient = 700),
            professionalStatus = "NON_CADRE",
            territoryCode = null
        )
        assertFalse(withoutEmployment.reliable)

        val excluded = ConventionMealBasketV2.scopeRules(
            rules = listOf(rule(
                classification = ConventionClassificationV2(coefficient = 700, employment = "gardien"),
                excludedEmployments = setOf("gardien")
            )),
            idcc = "292",
            referenceDate = date,
            classification = ConventionClassificationV2(coefficient = 700, employment = "gardien"),
            professionalStatus = "NON_CADRE",
            territoryCode = null
        )
        assertTrue(excluded.reliable)
        assertTrue(excluded.rules.isEmpty())
    }

    @Test
    fun `extension future reste non exploitable`() {
        val future = rule().copy(extensionEffectiveFrom = LocalDate.of(2027, 1, 1))
        val result = ConventionMealBasketV2.scopeRules(
            rules = listOf(future),
            idcc = "292",
            referenceDate = date,
            classification = ConventionClassificationV2(coefficient = 700),
            professionalStatus = "NON_CADRE",
            territoryCode = null
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }
}
