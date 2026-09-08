package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class V2CompanyMealBasketStoreTest {
    private fun rule(
        agreementId: String = "ACCOTEXT000000000001",
        siret: String = "12345678901234",
        coefficient: Int = 700,
        benefitId: String = "MEAL_DAY_1",
        amount: Double = 6.25
    ) = OfficialAccoMealBasketParserV2.Rule(
        agreementId = agreementId,
        siret = siret,
        effectiveFrom = LocalDate.of(2026, 1, 1),
        effectiveTo = null,
        classification = ConventionClassificationV2(coefficient = coefficient),
        professionalStatus = "NON_CADRE",
        benefitId = benefitId,
        deliveryMode = ConventionMealBasketV2.DeliveryMode.CASH_ALLOWANCE,
        amountFormula = ConventionMealBasketV2.AmountFormula.FixedEuro(amount),
        eligibilityAnyOf = listOf(
            ConventionMealBasketV2.EligibilityGroup(listOf(ConventionMealBasketV2.Condition.WorkedDay))
        ),
        blockers = emptySet(),
        countingUnit = ConventionMealBasketV2.CountingUnit.WORKED_DAY,
        evidenceExcerpt = "Panier repas entreprise"
    )

    @Test
    fun `nouveau montant du meme accord et profil remplace la variante precedente`() {
        assertTrue(V2CompanyMealBasketStore.sameLegalIdentity(rule(amount = 6.25), rule(amount = 6.50)))
    }

    @Test
    fun `autre SIRET reste une identite distincte`() {
        assertFalse(
            V2CompanyMealBasketStore.sameLegalIdentity(
                rule(siret = "12345678901234"),
                rule(siret = "99999999999999")
            )
        )
    }

    @Test
    fun `coefficient voisin reste une identite distincte`() {
        assertFalse(V2CompanyMealBasketStore.sameLegalIdentity(rule(coefficient = 700), rule(coefficient = 800)))
    }

    @Test
    fun `autre ACCOTEXT reste une identite distincte`() {
        assertFalse(
            V2CompanyMealBasketStore.sameLegalIdentity(
                rule(agreementId = "ACCOTEXT000000000001"),
                rule(agreementId = "ACCOTEXT000000000002")
            )
        )
    }

    @Test
    fun `panier nuit et panier jour du meme accord restent distincts`() {
        assertFalse(
            V2CompanyMealBasketStore.sameLegalIdentity(
                rule(benefitId = "MEAL_DAY_1"),
                rule(benefitId = "MEAL_NIGHT_1")
            )
        )
    }
}
