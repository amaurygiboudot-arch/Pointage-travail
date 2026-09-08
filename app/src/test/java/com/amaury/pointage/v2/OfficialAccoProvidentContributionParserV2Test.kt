package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialAccoProvidentContributionParserV2Test {
    private val profile = ConventionLegalProfileV2(
        companyId = "company",
        idcc = "292",
        siret = "12345678901234",
        professionalStatus = "NON_CADRE",
        classification = ConventionClassificationV2(coefficient = 700),
        contractType = "CDI",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = LocalDate.of(2020, 1, 1),
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private fun agreement(body: String) = """
        Le présent accord entre en vigueur le 1er janvier 2026 et est conclu pour une durée indéterminée.
        $body
    """.trimIndent()

    @Test
    fun `taux explicites sur brut et sans anciennete produisent une regle`() {
        val result = OfficialAccoProvidentContributionParserV2.parse(
            profile = profile,
            agreementId = "ACCOTEXT000000000001",
            officialText = agreement(
                "Tous les salariés bénéficient du régime sans condition d'ancienneté. " +
                    "La cotisation de prévoyance est assise sur le salaire brut. " +
                    "Part salariale : 0,40 %. Part patronale : 0,60 %."
            )
        )

        val rule = result.rule
        requireNotNull(rule)
        assertEquals("12345678901234", rule.siret)
        assertEquals(LocalDate.of(2026, 1, 1), rule.effectiveFrom)
        assertNull(rule.effectiveTo)
        assertEquals(0, rule.minimumSeniorityMonths)
        assertEquals(0.004, rule.employeeRate, 0.0000001)
        assertEquals(0.006, rule.employerRate, 0.0000001)
        assertEquals(ConventionClassificationV2(coefficient = 700), rule.classification)
        assertEquals("NON_CADRE", rule.professionalStatus)
    }

    @Test
    fun `repartition taux avant libelle est acceptee`() {
        val result = OfficialAccoProvidentContributionParserV2.parse(
            profile,
            "ACCOTEXT000000000002",
            agreement(
                "Le régime est ouvert à tous les salariés sans condition d'ancienneté. " +
                    "La cotisation de prévoyance porte sur la rémunération brute : " +
                    "0,35 % à la charge du salarié et 0,65 % à la charge de l'employeur."
            )
        )

        val rule = result.rule
        requireNotNull(rule)
        assertEquals(0.0035, rule.employeeRate, 0.0000001)
        assertEquals(0.0065, rule.employerRate, 0.0000001)
    }

    @Test
    fun `condition anciennete explicite est conservee`() {
        val result = OfficialAccoProvidentContributionParserV2.parse(
            profile,
            "ACCOTEXT000000000003",
            agreement(
                "Les salariés bénéficient du régime après 3 mois d'ancienneté. " +
                    "La cotisation de prévoyance est calculée sur le salaire brut. " +
                    "Part salariale 0,40 %. Part patronale 0,60 %."
            )
        )

        assertEquals(3, result.rule?.minimumSeniorityMonths)
    }

    @Test
    fun `tranche pmss reste bloquee`() {
        val result = OfficialAccoProvidentContributionParserV2.parse(
            profile,
            "ACCOTEXT000000000004",
            agreement(
                "Tous les salariés sont couverts sans condition d'ancienneté. " +
                    "La cotisation de prévoyance est calculée sur le salaire brut dans la tranche 1 jusqu'au PMSS. " +
                    "Part salariale 0,40 %. Part patronale 0,60 %."
            )
        )

        assertNull(result.rule)
        assertTrue(result.reasons.any { it.contains("PMSS", ignoreCase = true) || it.contains("tranche", ignoreCase = true) })
    }

    @Test
    fun `coefficient voisin ne contamine pas le profil`() {
        val result = OfficialAccoProvidentContributionParserV2.parse(
            profile,
            "ACCOTEXT000000000005",
            agreement(
                "Non-cadres coefficient 800. Sans condition d'ancienneté. " +
                    "La cotisation de prévoyance est assise sur le salaire brut. " +
                    "Part salariale 0,40 %. Part patronale 0,60 %."
            )
        )

        assertNull(result.rule)
    }

    @Test
    fun `date effet manquante bloque`() {
        val result = OfficialAccoProvidentContributionParserV2.parse(
            profile,
            "ACCOTEXT000000000006",
            "Accord conclu pour une durée indéterminée. " +
                "Tous les salariés sont couverts sans condition d'ancienneté. " +
                "La cotisation de prévoyance est assise sur le salaire brut. " +
                "Part salariale 0,40 %. Part patronale 0,60 %."
        )

        assertNull(result.rule)
        assertTrue(result.reasons.any { it.contains("date", ignoreCase = true) })
    }

    @Test
    fun `duree non demontree bloque`() {
        val result = OfficialAccoProvidentContributionParserV2.parse(
            profile,
            "ACCOTEXT000000000007",
            "Le présent accord entre en vigueur le 1er janvier 2026. " +
                "Tous les salariés sont couverts sans condition d'ancienneté. " +
                "La cotisation de prévoyance est assise sur le salaire brut. " +
                "Part salariale 0,40 %. Part patronale 0,60 %."
        )

        assertNull(result.rule)
        assertTrue(result.reasons.any { it.contains("durée", ignoreCase = true) })
    }

    @Test
    fun `taux contradictoires bloquent`() {
        val result = OfficialAccoProvidentContributionParserV2.parse(
            profile,
            "ACCOTEXT000000000008",
            agreement(
                "Tous les salariés sont couverts sans condition d'ancienneté. " +
                    "La cotisation de prévoyance est assise sur le salaire brut. " +
                    "Part salariale 0,40 %. Part patronale 0,60 %. " +
                    "Une autre part salariale de 0,50 % est également mentionnée."
            )
        )

        assertNull(result.rule)
        assertTrue(result.reasons.any { it.contains("contradictoire", ignoreCase = true) })
    }

    @Test
    fun `identifiant autre que accotext est refuse`() {
        val result = OfficialAccoProvidentContributionParserV2.parse(
            profile,
            "KALITEXT000000000001",
            agreement(
                "Tous les salariés sont couverts sans condition d'ancienneté. " +
                    "La cotisation de prévoyance est assise sur le salaire brut. " +
                    "Part salariale 0,40 %. Part patronale 0,60 %."
            )
        )

        assertNull(result.rule)
    }

    @Test
    fun `SIRET manquant bloque la regle`() {
        val result = OfficialAccoProvidentContributionParserV2.parse(
            profile.copy(siret = ""),
            "ACCOTEXT000000000009",
            agreement(
                "Tous les salariés sont couverts sans condition d'ancienneté. " +
                    "La cotisation de prévoyance est assise sur le salaire brut. " +
                    "Part salariale 0,40 %. Part patronale 0,60 %."
            )
        )

        assertNull(result.rule)
        assertTrue(result.reasons.any { it.contains("SIRET", ignoreCase = true) })
    }

    @Test
    fun `SIRET invalide bloque la regle`() {
        val result = OfficialAccoProvidentContributionParserV2.parse(
            profile.copy(siret = "123456789"),
            "ACCOTEXT000000000010",
            agreement(
                "Tous les salariés sont couverts sans condition d'ancienneté. " +
                    "La cotisation de prévoyance est assise sur le salaire brut. " +
                    "Part salariale 0,40 %. Part patronale 0,60 %."
            )
        )

        assertNull(result.rule)
        assertTrue(result.reasons.any { it.contains("SIRET", ignoreCase = true) })
    }
}
