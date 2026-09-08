package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class KaliMealBasketAuditV2Test {
    private val date = LocalDate.of(2026, 9, 8)

    private fun rule(
        extensionStatus: ConventionMinimumSalaryV2.ExtensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionDate: LocalDate? = LocalDate.of(2025, 1, 1)
    ) = ConventionMealBasketV2.Rule(
        idcc = "292",
        ruleId = "KALI-MEAL-KALITEXT000000000001-KALIARTI000000000001-1",
        benefitId = "MEAL_DAY_1",
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = ConventionClassificationV2(coefficient = 700),
        professionalStatus = "NON_CADRE",
        deliveryMode = ConventionMealBasketV2.DeliveryMode.CASH_ALLOWANCE,
        amountFormula = ConventionMealBasketV2.AmountFormula.FixedEuro(6.25),
        eligibilityAnyOf = listOf(
            ConventionMealBasketV2.EligibilityGroup(listOf(ConventionMealBasketV2.Condition.WorkedDay))
        ),
        source = "Légifrance KALI",
        conventionScopeKey = "KALITEXT000000000001",
        evidenceArticleIds = setOf("KALIARTI000000000001"),
        extensionStatus = extensionStatus,
        extensionEffectiveFrom = extensionDate
    )

    private fun diagnostic(
        rules: List<ConventionMealBasketV2.Rule>,
        observed: Int,
        structured: Int,
        unresolved: Int
    ) = OfficialKaliMealBasketParserV2.Diagnostic(
        rules = rules,
        observedOccurrences = observed,
        structuredOccurrences = structured,
        unresolvedOccurrences = unresolved,
        reasons = emptyList()
    )

    @Test
    fun `recherche vide ne produit jamais confirmed no rule`() {
        val completion = KaliMealBasketAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            diagnostic = diagnostic(emptyList(), observed = 0, structured = 0, unresolved = 0),
            savedRules = 0,
            referenceDate = date
        )

        assertFalse(completion.completed)
        assertEquals(ConventionMatterCoverageV2.State.INCOMPLETE, completion.state)
    }

    @Test
    fun `une occurrence incomplete bloque toute la couverture`() {
        val completion = KaliMealBasketAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            diagnostic = diagnostic(listOf(rule()), observed = 2, structured = 1, unresolved = 1),
            savedRules = 1,
            referenceDate = date
        )

        assertFalse(completion.completed)
        assertEquals(ConventionMatterCoverageV2.State.INCOMPLETE, completion.state)
    }

    @Test
    fun `regle non etendue ne devient jamais applicable`() {
        val nonExtended = rule(
            extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED,
            extensionDate = null
        )
        val completion = KaliMealBasketAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            diagnostic = diagnostic(listOf(nonExtended), observed = 1, structured = 1, unresolved = 0),
            savedRules = 1,
            referenceDate = date
        )

        assertFalse(completion.completed)
        assertEquals(ConventionMatterCoverageV2.State.INCOMPLETE, completion.state)
    }

    @Test
    fun `extension future ne deverrouille pas la date de paie`() {
        val future = rule(extensionDate = LocalDate.of(2027, 1, 1))
        val completion = KaliMealBasketAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            diagnostic = diagnostic(listOf(future), observed = 1, structured = 1, unresolved = 0),
            savedRules = 1,
            referenceDate = date
        )

        assertFalse(completion.completed)
    }

    @Test
    fun `couverture complete exige chaque occurrence structuree stockee et etendue`() {
        val completion = KaliMealBasketAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            diagnostic = diagnostic(listOf(rule()), observed = 1, structured = 1, unresolved = 0),
            savedRules = 1,
            referenceDate = date
        )

        assertTrue(completion.completed)
        assertEquals(ConventionMatterCoverageV2.State.CONFIRMED_RULES, completion.state)
    }

    @Test
    fun `echec de stockage bloque la couverture meme avec texte complet`() {
        val completion = KaliMealBasketAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            diagnostic = diagnostic(listOf(rule()), observed = 1, structured = 1, unresolved = 0),
            savedRules = 0,
            referenceDate = date
        )

        assertFalse(completion.completed)
    }
}
