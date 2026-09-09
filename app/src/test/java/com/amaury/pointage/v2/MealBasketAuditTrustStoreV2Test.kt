package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate

class MealBasketAuditTrustStoreV2Test {
    private val date = LocalDate.of(2026, 9, 8)

    private fun profile() = ConventionLegalProfileV2(
        companyId = "company",
        idcc = "292",
        siret = "12345678901234",
        professionalStatus = "NON_CADRE",
        classification = ConventionClassificationV2(coefficient = 700),
        contractType = "FULL_TIME",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = null,
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private fun kaliRule(): ConventionMealBasketV2.Rule {
        val articleId = "KALIARTI000000009901"
        return OfficialKaliMealBasketParserV2.parse(
            profile = profile(),
            verifiedIdcc = "292",
            auditDate = date,
            articles = listOf(
                OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
                    articleId = articleId,
                    status = "VIGUEUR_ETEN",
                    content = "Coefficient 700 non-cadres. Panier repas de 6,25 € par journée travaillée.",
                    effectiveFrom = LocalDate.of(2025, 1, 1),
                    effectiveTo = null,
                    title = "Paniers repas",
                    extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
                )
            ),
            articleTextIds = mapOf(articleId to "KALITEXT000000009901")
        ).rules.single()
    }

    @Test
    fun `empreinte KALI change si une valeur de paie change`() {
        val original = kaliRule()
        val modified = original.copy(
            amountFormula = ConventionMealBasketV2.AmountFormula.FixedEuro(8.50)
        )

        assertNotEquals(
            MealBasketAuditTrustStoreV2.kaliFingerprint(original),
            MealBasketAuditTrustStoreV2.kaliFingerprint(modified)
        )
    }

    @Test
    fun `empreinte KALI change si le plafond journalier change`() {
        val original = kaliRule()
        val modified = original.copy(maxAwardsPerCalendarDay = 2)

        assertNotEquals(
            MealBasketAuditTrustStoreV2.kaliFingerprint(original),
            MealBasketAuditTrustStoreV2.kaliFingerprint(modified)
        )
    }

    @Test
    fun `empreinte ACCO est exactement celle de la regle durcie`() {
        val parsed = OfficialAccoMealBasketParserV2.parse(
            profile = profile(),
            agreementId = "ACCOTEXT000000009901",
            officialText = "Le présent accord prend effet le 1 septembre 2026 et est conclu pour une durée indéterminée. " +
                "Coefficient 700 non-cadres. Panier repas de 6,25 € par journée travaillée."
        ).rules.single()
        val hardened = AccoMealBasketRuleHardeningV2.harden(parsed).rule!!

        assertEquals(
            hardened.fingerprint,
            MealBasketAuditTrustStoreV2.accoFingerprint(hardened)
        )
    }
}
