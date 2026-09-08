package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.ConventionLegalProfileV2
import com.amaury.pointage.v2.OfficialAccoMealBasketParserV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MealBasketLegalArbitrationBridgeV2Test {
    private val date = LocalDate.of(2026, 9, 8)

    private fun profile() = ConventionLegalProfileV2(
        companyId = "company", idcc = "292", siret = "12345678901234",
        professionalStatus = "NON_CADRE", classification = ConventionClassificationV2(coefficient = 700),
        contractType = "FULL_TIME", entryDate = LocalDate.of(2020, 1, 1), conventionSeniorityDate = null,
        weeklyHours = 35.0, forfaitAnnualHours = null, forfaitAnnualDays = null
    )

    private fun branchRule(benefitId: String, amount: Double, coefficient: Int = 700) = ConventionMealBasketV2.Rule(
        idcc = "292",
        ruleId = "KALI-MEAL-V2C-KALITEXT000000000001-$benefitId-$coefficient",
        benefitId = benefitId,
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = ConventionClassificationV2(coefficient = coefficient),
        professionalStatus = "NON_CADRE",
        deliveryMode = ConventionMealBasketV2.DeliveryMode.CASH_ALLOWANCE,
        amountFormula = ConventionMealBasketV2.AmountFormula.FixedEuro(amount),
        eligibilityAnyOf = listOf(ConventionMealBasketV2.EligibilityGroup(listOf(ConventionMealBasketV2.Condition.WorkedDay))),
        source = "Légifrance KALI", conventionScopeKey = "KALITEXT000000000001",
        evidenceArticleIds = setOf("KALIARTI000000000001"),
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
    )

    private fun companyRule(benefitId: String, amount: Double, agreementId: String = "ACCOTEXT000000000001") =
        OfficialAccoMealBasketParserV2.Rule(
            agreementId = agreementId, siret = "12345678901234", effectiveFrom = LocalDate.of(2026, 1, 1),
            effectiveTo = null, classification = ConventionClassificationV2(coefficient = 700),
            professionalStatus = "NON_CADRE", benefitId = benefitId,
            deliveryMode = ConventionMealBasketV2.DeliveryMode.CASH_ALLOWANCE,
            amountFormula = ConventionMealBasketV2.AmountFormula.FixedEuro(amount),
            eligibilityAnyOf = listOf(ConventionMealBasketV2.EligibilityGroup(listOf(ConventionMealBasketV2.Condition.WorkedDay))),
            blockers = emptySet(), countingUnit = ConventionMealBasketV2.CountingUnit.WORKED_DAY,
            evidenceExcerpt = "Panier repas entreprise"
        )

    @Test
    fun `accord entreprise prévaut sur branche pour le même objet`() {
        val result = MealBasketLegalArbitrationBridgeV2.resolve(
            profile(), date, listOf(branchRule("MEAL_DAY_1", 6.25)), listOf(companyRule("MEAL_DAY_1", 8.0))
        )
        assertTrue(result.reliable)
        assertEquals(PayrollLegalArbitratorV2.Source.ACCO, result.selected.single().source)
    }

    @Test
    fun `KALI d un coefficient voisin ne bloque pas un ACCO exact`() {
        val result = MealBasketLegalArbitrationBridgeV2.resolve(
            profile = profile(), referenceDate = date,
            branchRules = listOf(branchRule("MEAL_DAY_1", 6.25, coefficient = 800)),
            companyRules = listOf(companyRule("MEAL_DAY_1", 8.0))
        )
        assertTrue(result.reliable)
        assertEquals(1, result.selected.size)
        assertEquals(PayrollLegalArbitratorV2.Source.ACCO, result.selected.single().source)
    }

    @Test
    fun `deux ACCOTEXT au meme panier exact ne créent pas de faux conflit`() {
        val result = MealBasketLegalArbitrationBridgeV2.resolve(
            profile(), date, listOf(branchRule("MEAL_DAY_1", 6.25)),
            listOf(companyRule("MEAL_DAY_1", 8.0, "ACCOTEXT000000000001"), companyRule("MEAL_DAY_1", 8.0, "ACCOTEXT000000000002"))
        )
        assertTrue(result.reliable)
        assertEquals(PayrollLegalArbitratorV2.Source.ACCO, result.selected.single().source)
    }

    @Test
    fun `deux ACCOTEXT avec montants incompatibles restent en conflit`() {
        val result = MealBasketLegalArbitrationBridgeV2.resolve(
            profile(), date, listOf(branchRule("MEAL_DAY_1", 6.25)),
            listOf(companyRule("MEAL_DAY_1", 8.0, "ACCOTEXT000000000001"), companyRule("MEAL_DAY_1", 8.5, "ACCOTEXT000000000002"))
        )
        assertFalse(result.reliable)
        assertTrue(result.selected.isEmpty())
    }

    @Test
    fun `branche seule reste bloquée sans preuve absence ACCO pour ce sujet`() {
        val result = MealBasketLegalArbitrationBridgeV2.resolve(
            profile(), date, listOf(branchRule("MEAL_NIGHT_1", 10.0)), emptyList()
        )
        assertFalse(result.reliable)
        assertTrue(result.selected.isEmpty())
    }

    @Test
    fun `branche seule est retenue avec absence ACCO confirmée pour ce sujet`() {
        val result = MealBasketLegalArbitrationBridgeV2.resolve(
            profile = profile(), referenceDate = date, branchRules = listOf(branchRule("MEAL_NIGHT_1", 10.0)),
            companyRules = emptyList(), sourceKnowledgeBySubject = mapOf(
                "MEAL_NIGHT" to mapOf(PayrollLegalArbitratorV2.Source.ACCO to PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE)
            )
        )
        assertTrue(result.reliable)
        assertEquals(PayrollLegalArbitratorV2.Source.KALI, result.selected.single().source)
    }

    @Test
    fun `preuve absence panier jour ne déverrouille jamais panier nuit`() {
        val result = MealBasketLegalArbitrationBridgeV2.resolve(
            profile = profile(), referenceDate = date, branchRules = listOf(branchRule("MEAL_NIGHT_1", 10.0)),
            companyRules = emptyList(), sourceKnowledgeBySubject = mapOf(
                "MEAL_DAY" to mapOf(PayrollLegalArbitratorV2.Source.ACCO to PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE)
            )
        )
        assertFalse(result.reliable)
    }

    @Test
    fun `panier jour ACCO ne masque pas panier nuit KALI si absence nuit ACCO est prouvée`() {
        val result = MealBasketLegalArbitrationBridgeV2.resolve(
            profile = profile(), referenceDate = date,
            branchRules = listOf(branchRule("MEAL_DAY_1", 6.25), branchRule("MEAL_NIGHT_1", 10.0)),
            companyRules = listOf(companyRule("MEAL_DAY_1", 8.0)),
            sourceKnowledgeBySubject = mapOf(
                "MEAL_NIGHT" to mapOf(PayrollLegalArbitratorV2.Source.ACCO to PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE)
            )
        )
        assertTrue(result.reliable)
        assertEquals(2, result.selected.size)
        assertEquals(PayrollLegalArbitratorV2.Source.ACCO, result.selected.single { it.subject == "MEAL_DAY" }.source)
        assertEquals(PayrollLegalArbitratorV2.Source.KALI, result.selected.single { it.subject == "MEAL_NIGHT" }.source)
    }
}