package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import org.junit.Assert.assertEquals
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
    fun `paquet certifie accepte des identites juridiques distinctes`() {
        assertTrue(
            V2ConventionMealBasketStore.acceptsVerifiedPackage(
                listOf(
                    rule(articleId = "KALIARTI000000000001"),
                    rule(articleId = "KALIARTI000000000002")
                )
            )
        )
    }

    @Test
    fun `paquet certifie refuse deux variantes de la meme identite juridique`() {
        assertFalse(
            V2ConventionMealBasketStore.acceptsVerifiedPackage(
                listOf(rule(amount = 6.25), rule(amount = 6.50))
            )
        )
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

    @Test
    fun `historique vide explicite est fiable`() {
        val result = V2ConventionMealBasketStore.decodeVerified("[]")

        assertTrue(result.reliable)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `json illisible rend tout le stockage non fiable`() {
        val result = V2ConventionMealBasketStore.decodeVerified("not-json")

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `entree invalide rend tout le stockage non fiable sans perdre la preuve valide`() {
        val result = V2ConventionMealBasketStore.decodeVerified(
            """[
                ${ruleJson(ruleId = "R1")},
                {"idcc":"292","ruleId":"BROKEN"}
            ]""".trimIndent()
        )

        assertFalse(result.reliable)
        assertEquals(1, result.rules.size)
    }

    @Test
    fun `deux variantes de la meme identite juridique rendent le stockage non fiable`() {
        val result = V2ConventionMealBasketStore.decodeVerified(
            """[
                ${ruleJson(ruleId = "R1", amount = 6.25)},
                ${ruleJson(ruleId = "R2", amount = 6.50)}
            ]""".trimIndent()
        )

        assertFalse(result.reliable)
        assertEquals(2, result.rules.size)
    }

    @Test
    fun `deux articles KALI distincts restent fiables`() {
        val result = V2ConventionMealBasketStore.decodeVerified(
            """[
                ${ruleJson(ruleId = "R1", articleId = "KALIARTI000000000001")},
                ${ruleJson(ruleId = "R2", articleId = "KALIARTI000000000002")}
            ]""".trimIndent()
        )

        assertTrue(result.reliable)
        assertEquals(2, result.rules.size)
    }

    @Test
    fun `source officielle vide est rejetee`() {
        val result = V2ConventionMealBasketStore.decodeVerified(
            "[${ruleJson(ruleId = "R1", source = "")}]"
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `identifiant de texte KALI invalide est rejete`() {
        val result = V2ConventionMealBasketStore.decodeVerified(
            "[${ruleJson(ruleId = "R1", scopeId = "NOT-KALI")}]"
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `extension incoherente est rejetee`() {
        val result = V2ConventionMealBasketStore.decodeVerified(
            "[${ruleJson(ruleId = "R1", extensionStatus = "UNKNOWN")}]"
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `condition repas inconnue est rejetee`() {
        val result = V2ConventionMealBasketStore.decodeVerified(
            "[${ruleJson(ruleId = "R1", conditionType = "UNKNOWN_CONDITION")}]"
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }

    private fun ruleJson(
        ruleId: String,
        amount: Double = 6.25,
        coefficient: Int = 700,
        articleId: String = "KALIARTI000000000001",
        scopeId: String = "KALITEXT000000000001",
        source: String = "Legifrance KALI",
        extensionStatus: String = "EXTENDED",
        conditionType: String = "WORKED_DAY"
    ): String = """{
        "idcc":"292",
        "ruleId":"$ruleId",
        "benefitId":"MEAL_DAY_1",
        "effectiveFrom":"2025-01-01",
        "effectiveTo":null,
        "classification":{"coefficient":$coefficient,"level":null,"echelon":null,"position":null,"group":null,"category":null,"employment":null},
        "professionalStatus":"NON_CADRE",
        "territoryCodes":[],
        "excludedEmployments":[],
        "deliveryMode":"CASH_ALLOWANCE",
        "amount":{"type":"FIXED","value":$amount},
        "eligibility":[[{"type":"$conditionType"}]],
        "blockers":[],
        "countingUnit":"SHIFT",
        "maxAwardsPerCalendarDay":1,
        "source":"$source",
        "conventionScopeKey":"$scopeId",
        "evidenceArticleIds":["$articleId"],
        "extensionStatus":"$extensionStatus",
        "extensionEffectiveFrom":"2025-01-01"
    }""".trimIndent()
}
