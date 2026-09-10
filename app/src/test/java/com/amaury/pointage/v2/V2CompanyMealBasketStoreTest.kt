package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import org.junit.Assert.assertEquals
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

    @Test
    fun `paquet ACCO complet accepte plusieurs objets distincts`() {
        assertTrue(
            V2CompanyMealBasketStore.acceptsVerifiedPackage(
                listOf(rule(benefitId = "MEAL_DAY_1"), rule(benefitId = "MEAL_NIGHT_1"))
            )
        )
    }

    @Test
    fun `paquet ACCO refuse deux variantes de la meme identite juridique`() {
        assertFalse(
            V2CompanyMealBasketStore.acceptsVerifiedPackage(
                listOf(rule(amount = 6.25), rule(amount = 6.50))
            )
        )
    }

    @Test
    fun `stockage vide explicite est fiable`() {
        val result = V2CompanyMealBasketStore.decodeStored("[]")

        assertTrue(result.reliable)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `json illisible rend le stockage ACCO non fiable`() {
        val result = V2CompanyMealBasketStore.decodeStored("not-json")

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `entree invalide rend tout le stockage non fiable sans perdre la preuve valide`() {
        val result = V2CompanyMealBasketStore.decodeStored(
            """[
                ${ruleJson()},
                {"agreementId":"ACCOTEXT000000000002","siret":"12345678901234"}
            ]""".trimIndent()
        )

        assertFalse(result.reliable)
        assertEquals(1, result.rules.size)
    }

    @Test
    fun `deux variantes de la meme identite juridique rendent le stockage non fiable`() {
        val result = V2CompanyMealBasketStore.decodeStored(
            """[
                ${ruleJson(amount = 6.25)},
                ${ruleJson(amount = 6.50)}
            ]""".trimIndent()
        )

        assertFalse(result.reliable)
        assertEquals(2, result.rules.size)
    }

    @Test
    fun `deux objets repas distincts restent fiables`() {
        val result = V2CompanyMealBasketStore.decodeStored(
            """[
                ${ruleJson(benefitId = "MEAL_DAY_1")},
                ${ruleJson(benefitId = "MEAL_NIGHT_1")}
            ]""".trimIndent()
        )

        assertTrue(result.reliable)
        assertEquals(2, result.rules.size)
    }

    @Test
    fun `condition ACCO inconnue est rejetee`() {
        val result = V2CompanyMealBasketStore.decodeStored(
            "[${ruleJson(conditionType = "UNKNOWN")}]"
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `preuve ACCO vide est rejetee`() {
        val result = V2CompanyMealBasketStore.decodeStored(
            "[${ruleJson(evidenceExcerpt = "")}]"
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }

    private fun ruleJson(
        agreementId: String = "ACCOTEXT000000000001",
        siret: String = "12345678901234",
        coefficient: Int = 700,
        benefitId: String = "MEAL_DAY_1",
        amount: Double = 6.25,
        conditionType: String = "WORKED_DAY",
        evidenceExcerpt: String = "Panier repas entreprise"
    ): String = """{
        "agreementId":"$agreementId",
        "siret":"$siret",
        "effectiveFrom":"2026-01-01",
        "effectiveTo":null,
        "classification":{"coefficient":$coefficient,"level":null,"echelon":null,"position":null,"group":null,"category":null,"employment":null},
        "professionalStatus":"NON_CADRE",
        "benefitId":"$benefitId",
        "deliveryMode":"CASH_ALLOWANCE",
        "amount":{"type":"FIXED","value":$amount},
        "eligibility":[[{"type":"$conditionType"}]],
        "blockers":[],
        "countingUnit":"WORKED_DAY",
        "maxAwardsPerCalendarDay":1,
        "evidenceExcerpt":"$evidenceExcerpt"
    }""".trimIndent()
}
