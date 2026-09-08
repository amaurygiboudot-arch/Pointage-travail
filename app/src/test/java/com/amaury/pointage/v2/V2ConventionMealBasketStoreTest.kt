package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class V2ConventionMealBasketStoreTest {
    private fun rule(
        amount: Double = 6.25,
        coefficient: Int = 700,
        articleId: String = "KALIARTI000000000001"
    ) = ConventionMealBasketV2.Rule(
        idcc = "292",
        ruleId = "KALI-MEAL-KALITEXT000000000001-$articleId-1-$amount",
        benefitId = "MEAL_DAY_1",
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = ConventionClassificationV2(coefficient = coefficient),
        professionalStatus = "NON_CADRE",
        deliveryMode = ConventionMealBasketV2.DeliveryMode.CASH_ALLOWANCE,
        amountFormula = ConventionMealBasketV2.AmountFormula.FixedEuro(amount),
        eligibilityAnyOf = listOf(
            ConventionMealBasketV2.EligibilityGroup(listOf(ConventionMealBasketV2.Condition.WorkedDay))
        ),
        source = "Légifrance KALI",
        conventionScopeKey = "KALITEXT000000000001",
        evidenceArticleIds = setOf(articleId),
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
    )

    @Test
    fun `regle KALI complete est acceptée`() {
        assertTrue(V2ConventionMealBasketStore.acceptsVerifiedRule(rule()))
    }

    @Test
    fun `nouveau montant du meme acte et profil remplace la variante supersedee`() {
        val old = rule(amount = 6.25)
        val revised = rule(amount = 6.50)

        assertTrue(V2ConventionMealBasketStore.sameLegalIdentity(old, revised))
    }

    @Test
    fun `coefficient voisin garde une identite separee`() {
        assertFalse(V2ConventionMealBasketStore.sameLegalIdentity(rule(coefficient = 700), rule(coefficient = 800)))
    }

    @Test
    fun `article KALI distinct garde une identite separee`() {
        assertFalse(
            V2ConventionMealBasketStore.sameLegalIdentity(
                rule(articleId = "KALIARTI000000000001"),
                rule(articleId = "KALIARTI000000000002")
            )
        )
    }
}
