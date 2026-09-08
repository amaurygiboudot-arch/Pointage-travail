package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CompanyAgreementProvidentContributionIngestionV2Test {
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

    private fun verified(text: String, siret: String = "12345678901234") =
        OfficialAgreementContentParserV2.VerifiedContent(siret = siret, text = text)

    private fun agreement(body: String) = """
        Le présent accord entre en vigueur le 1er janvier 2026 et est conclu pour une durée indéterminée.
        $body
    """.trimIndent()

    @Test
    fun `accord sans cotisation prevoyance nest pas structure`() {
        val result = CompanyAgreementProvidentContributionIngestionV2.structure(
            profile,
            "ACCOTEXT000000000001",
            verified("Prime annuelle de performance versée en décembre selon les objectifs atteints.")
        )

        assertFalse(result.detected)
        assertNull(result.rule)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `consultation verifiee et clause complete produit une regle`() {
        val result = CompanyAgreementProvidentContributionIngestionV2.structure(
            profile,
            "ACCOTEXT000000000002",
            verified(
                agreement(
                    "Tous les salariés sont couverts sans condition d'ancienneté. " +
                        "La cotisation de prévoyance est assise sur le salaire brut. " +
                        "Part salariale : 0,40 %. Part patronale : 0,60 %."
                )
            )
        )

        assertTrue(result.detected)
        val rule = result.rule
        requireNotNull(rule)
        assertEquals("12345678901234", rule.siret)
        assertEquals("ACCOTEXT000000000002", rule.agreementId)
        assertEquals(0.004, rule.employeeRate, 0.0000001)
        assertEquals(0.006, rule.employerRate, 0.0000001)
    }

    @Test
    fun `siret verifie different du profil rejette la regle`() {
        val result = CompanyAgreementProvidentContributionIngestionV2.structure(
            profile,
            "ACCOTEXT000000000003",
            verified(
                agreement(
                    "Tous les salariés sont couverts sans condition d'ancienneté. " +
                        "La cotisation de prévoyance est assise sur le salaire brut. " +
                        "Part salariale : 0,40 %. Part patronale : 0,60 %."
                ),
                siret = "99999999999999"
            )
        )

        assertTrue(result.detected)
        assertNull(result.rule)
        assertTrue(result.warnings.any { it.contains("SIRET", ignoreCase = true) })
    }

    @Test
    fun `clause pmss reste detectee mais non structuree`() {
        val result = CompanyAgreementProvidentContributionIngestionV2.structure(
            profile,
            "ACCOTEXT000000000004",
            verified(
                agreement(
                    "Tous les salariés sont couverts sans condition d'ancienneté. " +
                        "La cotisation de prévoyance est calculée sur le salaire brut en tranche 1 jusqu'au PMSS. " +
                        "Part salariale : 0,40 %. Part patronale : 0,60 %."
                )
            )
        )

        assertTrue(result.detected)
        assertNull(result.rule)
        assertTrue(result.warnings.any { it.contains("PMSS", ignoreCase = true) || it.contains("tranche", ignoreCase = true) })
    }

    @Test
    fun `identifiant non accotext ne produit aucune regle`() {
        val result = CompanyAgreementProvidentContributionIngestionV2.structure(
            profile,
            "KALITEXT000000000001",
            verified(
                agreement(
                    "Tous les salariés sont couverts sans condition d'ancienneté. " +
                        "La cotisation de prévoyance est assise sur le salaire brut. " +
                        "Part salariale : 0,40 %. Part patronale : 0,60 %."
                )
            )
        )

        assertTrue(result.detected)
        assertNull(result.rule)
    }
}
