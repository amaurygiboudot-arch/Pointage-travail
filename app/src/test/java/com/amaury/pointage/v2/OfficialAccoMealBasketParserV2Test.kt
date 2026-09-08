package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialAccoMealBasketParserV2Test {
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

    private fun agreement(body: String): String =
        "Le présent accord prend effet le 1 septembre 2026 et est conclu pour une durée indéterminée. $body"

    @Test
    fun `montant euro et journée travaillée sont structurés`() {
        val result = OfficialAccoMealBasketParserV2.parse(
            profile(),
            "ACCOTEXT000000009901",
            agreement("Coefficient 700 non-cadres. Panier repas de 6,25 € par journée travaillée.")
        )

        assertTrue(result.fullyStructured)
        val amount = result.rules.single().amountFormula as ConventionMealBasketV2.AmountFormula.FixedEuro
        assertEquals(6.25, amount.amount, 0.001)
    }

    @Test
    fun `coefficient voisin ne contamine pas le profil courant`() {
        val result = OfficialAccoMealBasketParserV2.parse(
            profile(),
            "ACCOTEXT000000009901",
            agreement(
                "Coefficient 700 non-cadres. Panier repas de 6,25 € par journée travaillée. " +
                    "Coefficient 800 non-cadres. Panier repas de 8,50 € par journée travaillée."
            )
        )

        assertEquals(1, result.observedOccurrences)
        assertEquals(1, result.structuredOccurrences)
        assertTrue(result.fullyStructured)
    }

    @Test
    fun `occurrence incomplète du même profil bloque le paquet entier`() {
        val result = OfficialAccoMealBasketParserV2.parse(
            profile(),
            "ACCOTEXT000000009901",
            agreement(
                "Coefficient 700 non-cadres. Panier repas de 6,25 € par journée travaillée. " +
                    "Panier de nuit pour travail posté."
            )
        )

        assertEquals(2, result.observedOccurrences)
        assertEquals(1, result.structuredOccurrences)
        assertEquals(1, result.unresolvedOccurrences)
        assertFalse(result.fullyStructured)
    }

    @Test
    fun `deux montants incompatibles dans la même clause restent ambigus`() {
        val result = OfficialAccoMealBasketParserV2.parse(
            profile(),
            "ACCOTEXT000000009901",
            agreement("Coefficient 700 non-cadres. Panier repas de 6,25 € ou 8,50 € par journée travaillée.")
        )

        assertFalse(result.fullyStructured)
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `conditions temporelles cumulatives restent dans le même groupe`() {
        val result = OfficialAccoMealBasketParserV2.parse(
            profile(),
            "ACCOTEXT000000009901",
            agreement(
                "Coefficient 700 non-cadres. Panier de nuit de 8,50 € si l'horaire comprend minuit et commence à minuit."
            )
        )

        assertTrue(result.fullyStructured)
        val groups = result.rules.single().eligibilityAnyOf
        assertEquals(1, groups.size)
        assertTrue(groups.single().allOf.contains(ConventionMealBasketV2.Condition.ShiftEnclosesMidnight))
        assertTrue(groups.single().allOf.contains(ConventionMealBasketV2.Condition.ShiftStartsAtMidnight))
    }

    @Test
    fun `alternative temporelle non décomposable bloque la clause`() {
        val result = OfficialAccoMealBasketParserV2.parse(
            profile(),
            "ACCOTEXT000000009901",
            agreement(
                "Coefficient 700 non-cadres. Panier de nuit de 8,50 € si l'horaire comprend minuit ou commence à minuit."
            )
        )

        assertFalse(result.fullyStructured)
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `accord à durée déterminée contradictoire avec durée indéterminée est refusé`() {
        val text =
            "Le présent accord prend effet le 1 septembre 2026, est conclu pour une durée indéterminée et prendra fin le 31 décembre 2026. " +
                "Coefficient 700 non-cadres. Panier repas de 6,25 € par journée travaillée."

        val result = OfficialAccoMealBasketParserV2.parse(profile(), "ACCOTEXT000000009901", text)

        assertFalse(result.fullyStructured)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.reasons.any { it.contains("date d'effet", ignoreCase = true) || it.contains("durée", ignoreCase = true) })
    }

    @Test
    fun `SIRET manquant bloque toute structuration`() {
        val result = OfficialAccoMealBasketParserV2.parse(
            profile().copy(siret = ""),
            "ACCOTEXT000000009901",
            agreement("Coefficient 700 non-cadres. Panier repas de 6,25 € par journée travaillée.")
        )

        assertFalse(result.fullyStructured)
        assertTrue(result.rules.isEmpty())
    }
}
