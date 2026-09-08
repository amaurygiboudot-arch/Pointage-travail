package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CompanyAgreementMealBasketIngestionV2Test {
    private val profile = ConventionLegalProfileV2(
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

    private fun verified(text: String, siret: String = "12345678901234") =
        OfficialAgreementContentParserV2.VerifiedContent(siret = siret, text = text)

    private fun agreement(body: String) =
        "Le présent accord prend effet le 1 septembre 2026 et est conclu pour une durée indéterminée. $body"

    @Test
    fun `accord sans repas reste hors chaine dediee`() {
        val result = CompanyAgreementMealBasketIngestionV2.structure(
            profile,
            "ACCOTEXT000000000001",
            verified(agreement("Prime annuelle de performance versée en décembre."))
        )

        assertFalse(result.detected)
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `simple pause repas ne devient jamais une indemnité`() {
        val result = CompanyAgreementMealBasketIngestionV2.structure(
            profile,
            "ACCOTEXT000000000001",
            verified(agreement("La pause repas est fixée de 12 h à 13 h et n'est pas assimilée à du temps de travail effectif."))
        )

        assertFalse(result.detected)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `contenu verifie et paquet complet produit sujet jour`() {
        val result = CompanyAgreementMealBasketIngestionV2.structure(
            profile,
            "ACCOTEXT000000000002",
            verified(agreement("Coefficient 700 non-cadres. Panier repas de 6,25 € par journée travaillée."))
        )

        assertTrue(result.detected)
        assertTrue(result.packageComplete)
        assertEquals(1, result.rules.size)
        assertEquals(setOf("MEAL_DAY"), result.subjects)
    }

    @Test
    fun `siret officiel different bloque le paquet`() {
        val result = CompanyAgreementMealBasketIngestionV2.structure(
            profile,
            "ACCOTEXT000000000003",
            verified(
                agreement("Coefficient 700 non-cadres. Panier repas de 6,25 € par journée travaillée."),
                siret = "99999999999999"
            )
        )

        assertTrue(result.detected)
        assertFalse(result.packageComplete)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.any { it.contains("SIRET", ignoreCase = true) })
    }

    @Test
    fun `paquet partiel reste visible mais interdit au calcul`() {
        val result = CompanyAgreementMealBasketIngestionV2.structure(
            profile,
            "ACCOTEXT000000000004",
            verified(
                agreement(
                    "Coefficient 700 non-cadres. Panier repas de 6,25 € par journée travaillée. " +
                        "Panier de nuit pour travail posté."
                )
            )
        )

        assertTrue(result.detected)
        assertFalse(result.packageComplete)
        assertEquals(1, result.rules.size)
        assertTrue(result.warnings.any { it.contains("aucune règle partielle", ignoreCase = true) })
    }
}
