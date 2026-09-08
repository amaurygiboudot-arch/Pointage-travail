package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialKaliMealBasketParserV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val articleId = "KALIARTI000000009901"
    private val textId = "KALITEXT000000009901"

    private fun profile(coefficient: Int = 700) = ConventionLegalProfileV2(
        companyId = "company",
        idcc = "292",
        siret = "12345678901234",
        professionalStatus = "NON_CADRE",
        classification = ConventionClassificationV2(coefficient = coefficient),
        contractType = "FULL_TIME",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = null,
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private fun article(content: String, status: String = "VIGUEUR_ETEN") =
        OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
            articleId = articleId,
            status = status,
            content = content,
            effectiveFrom = LocalDate.of(2025, 1, 1),
            effectiveTo = null,
            title = "Paniers repas",
            extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
        )

    private fun parse(content: String, coefficient: Int = 700, status: String = "VIGUEUR_ETEN") =
        OfficialKaliMealBasketParserV2.parse(
            profile = profile(coefficient),
            verifiedIdcc = "292",
            auditDate = date,
            articles = listOf(article(content, status)),
            articleTextIds = mapOf(articleId to textId)
        )

    @Test
    fun `montant fixe et jour travaille sont structures pour le coefficient exact`() {
        val result = parse("Coefficient 700 non-cadres. Panier repas de 6,25 € par journée travaillée.")

        assertEquals(1, result.observedOccurrences)
        assertEquals(1, result.structuredOccurrences)
        assertEquals(0, result.unresolvedOccurrences)
        val amount = result.rules.single().amountFormula as ConventionMealBasketV2.AmountFormula.FixedEuro
        assertEquals(6.25, amount.amount, 0.001)
    }

    @Test
    fun `clause du coefficient voisin est ignoree et ne contamine pas le profil`() {
        val result = parse(
            "Coefficient 700 non-cadres. Panier repas de 6,25 € par journée travaillée. " +
                "Coefficient 800 non-cadres. Panier repas de 8,50 € par journée travaillée."
        )

        assertEquals(1, result.observedOccurrences)
        assertEquals(1, result.structuredOccurrences)
        assertEquals(0, result.unresolvedOccurrences)
        val amount = result.rules.single().amountFormula as ConventionMealBasketV2.AmountFormula.FixedEuro
        assertEquals(6.25, amount.amount, 0.001)
    }

    @Test
    fun `deux occurrences du meme profil restent independantes si la seconde est incomplete`() {
        val result = parse(
            "Coefficient 700 non-cadres. Panier repas de 6,25 € par journée travaillée. " +
                "Panier de nuit pour travail posté."
        )

        assertEquals(2, result.observedOccurrences)
        assertEquals(1, result.structuredOccurrences)
        assertEquals(1, result.unresolvedOccurrences)
        assertEquals(1, result.rules.size)
    }

    @Test
    fun `deux formules dans la meme occurrence restent ambiguës`() {
        val result = parse(
            "Coefficient 700 non-cadres. Panier repas de 6,25 € correspondant à 1,5 fois le minimum garanti par journée travaillée."
        )

        assertEquals(1, result.observedOccurrences)
        assertEquals(0, result.structuredOccurrences)
        assertEquals(1, result.unresolvedOccurrences)
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `multiple du minimum garanti est conserve comme formule et non converti arbitrairement`() {
        val result = parse(
            "Coefficient 700 non-cadres. Panier de nuit de 2,65 fois le minimum garanti par journée travaillée."
        )

        val amount = result.rules.single().amountFormula as ConventionMealBasketV2.AmountFormula.MinimumGuaranteedMultiple
        assertEquals(2.65, amount.multiplier, 0.001)
    }

    @Test
    fun `portee territoriale non structuree bloque la clause`() {
        val result = parse(
            "Coefficient 700 non-cadres. Dans le département 85, panier repas de 6,25 € par journée travaillée."
        )

        assertEquals(1, result.observedOccurrences)
        assertEquals(0, result.structuredOccurrences)
        assertEquals(1, result.unresolvedOccurrences)
    }

    @Test
    fun `article non etendu peut etre structure mais ne pretend pas etre applicable`() {
        val result = parse(
            "Coefficient 700 non-cadres. Panier repas de 6,25 € par journée travaillée.",
            status = "VIGUEUR_NON_ETEN"
        )

        assertEquals(1, result.rules.size)
        assertEquals(com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED, result.rules.single().extensionStatus)
    }

    @Test
    fun `absence de panier trouve ne devient jamais une preuve d absence`() {
        val result = parse("Coefficient 700 non-cadres. Prime d'ancienneté selon le barème applicable.")

        assertEquals(0, result.observedOccurrences)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.reasons.any { it.contains("ne prouve jamais", ignoreCase = true) })
    }
}
