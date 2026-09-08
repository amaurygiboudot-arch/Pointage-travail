package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialApecProtectionCategoryParserV2Test {
    private fun profile(
        idcc: String = "292",
        status: String = "CADRE",
        classification: ConventionClassificationV2 = ConventionClassificationV2(coefficient = 910)
    ) = ConventionLegalProfileV2(
        companyId = "c1",
        idcc = idcc,
        siret = "12345678901234",
        professionalStatus = status,
        classification = classification,
        contractType = "CDI",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = LocalDate.of(2020, 1, 1),
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private fun document(content: String, source: String = "https://commission-paritaire.apec.fr/assets/files/agrement-test.pdf") =
        OfficialApecProtectionCategoryParserV2.Document(
            documentId = "APEC-PDF-test",
            sourceUrl = source,
            content = content
        )

    @Test
    fun `Plasturgie coefficient 910 est prouve en ANI 2 1 sans inventer date effet`() {
        val text = """
            AGREMENT DU 09.10.2024
            CCN de la plasturgie (IDCC 292)
            Accord du 27 juin 2024 relatif aux catégories de bénéficiaire du régime de protection sociale complémentaire.
            La Commission paritaire rattachée à l'Apec valide l'affiliation des cadres (coefficients 900 à 940) à l'article 2.1 de l'ANI du 17 novembre 2017.
        """.trimIndent()

        val result = OfficialApecProtectionCategoryParserV2.parse(document(text), profile())

        assertNotNull(result.evidence)
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_1, result.evidence!!.aniCategory)
        assertEquals(LocalDate.of(2024, 10, 9), result.evidence!!.decisionDate)
        assertNull(result.evidence!!.applicableFrom)
        assertTrue(result.reasons.any { it.contains("DATE D'EFFET APEC exacte non prouvée") })
        assertTrue(result.evidence!!.agreementReferences.any { it.contains("accord du 27 juin 2024") })
    }

    @Test
    fun `Plasturgie coefficient 830 est prouve en ANI 2 2`() {
        val text = """
            AGREMENT DU 09.10.2024
            CCN de la plasturgie (IDCC 292)
            Accord du 27 juin 2024 relatif aux catégories de bénéficiaire du régime de protection sociale complémentaire.
            La Commission paritaire rattachée à l'Apec valide l'affiliation des assimilés cadres (coefficient 830) à l'article 2.2 de l'ANI du 17 novembre 2017.
        """.trimIndent()

        val result = OfficialApecProtectionCategoryParserV2.parse(
            document(text),
            profile(status = "NON_CADRE", classification = ConventionClassificationV2(coefficient = 830))
        )

        assertNotNull(result.evidence)
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_2, result.evidence!!.aniCategory)
    }

    @Test
    fun `IDCC 493 niveau VI echelon B est prouve sans règle codée par convention`() {
        val text = """
            AGREMENT DU 19.11.2024
            Vins, cidres, jus de fruits, sirops, spiritueux et liqueurs (IDCC 493)
            Accord du 28 juin 2024 relatif aux catégories objectives.
            La Commission paritaire rattachée à l'Apec valide l'affiliation des agents de maîtrise et agents techniques du niveau VI - échelons A et B à l'article 2.2 de l'ANI du 17 novembre 2017.
        """.trimIndent()

        val result = OfficialApecProtectionCategoryParserV2.parse(
            document(text),
            profile(
                idcc = "493",
                status = "NON_CADRE",
                classification = ConventionClassificationV2(level = "VI", echelon = "B")
            )
        )

        assertNotNull(result.evidence)
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_2, result.evidence!!.aniCategory)
    }

    @Test
    fun `niveau voisin non cité est refusé`() {
        val text = """
            AGREMENT DU 19.11.2024
            Branche test (IDCC 493)
            La Commission paritaire rattachée à l'Apec valide l'affiliation des agents de maîtrise du niveau VI - échelons A et B à l'article 2.2 de l'ANI du 17 novembre 2017.
        """.trimIndent()

        val result = OfficialApecProtectionCategoryParserV2.parse(
            document(text),
            profile(idcc = "493", status = "NON_CADRE", classification = ConventionClassificationV2(level = "VI", echelon = "C"))
        )

        assertNull(result.evidence)
    }

    @Test
    fun `DATE D EFFET explicite est distincte de la délibération`() {
        val text = """
            AGREMENT DU 21.12.2023
            Branche test (IDCC 292)
            Accord du 1 juillet 2023 relatif aux classifications.
            La Commission paritaire valide l'affiliation des cadres coefficient 910 à l'article 2.1 de l'ANI du 17 novembre 2017.
            DATE D'EFFET 01.07.2024
        """.trimIndent()

        val result = OfficialApecProtectionCategoryParserV2.parse(document(text), profile())

        assertNotNull(result.evidence)
        assertEquals(LocalDate.of(2023, 12, 21), result.evidence!!.decisionDate)
        assertEquals(LocalDate.of(2024, 7, 1), result.evidence!!.applicableFrom)
    }

    @Test
    fun `DATE D EFFET SOUHAITEE ne devient jamais date applicable`() {
        val text = """
            AGREMENT DU 08.11.2023
            Branche test (IDCC 292)
            Accord du 1 juillet 2023 relatif aux classifications.
            La Commission paritaire valide l'affiliation des cadres coefficient 910 à l'article 2.1 de l'ANI du 17 novembre 2017.
            DATE D'EFFET SOUHAITEE 01.01.2024
        """.trimIndent()

        val result = OfficialApecProtectionCategoryParserV2.parse(document(text), profile())

        assertNotNull(result.evidence)
        assertNull(result.evidence!!.applicableFrom)
        assertEquals(LocalDate.of(2024, 1, 1), result.evidence!!.requestedEffectiveFrom)
        assertTrue(result.reasons.any { it.contains("SOUHAITEE") })
    }

    @Test
    fun `mauvais domaine ne peut jamais fabriquer une preuve APEC`() {
        val text = "AGREMENT DU 09.10.2024 CCN test (IDCC 292). La Commission valide les cadres coefficient 910 à l'article 2.1."
        val result = OfficialApecProtectionCategoryParserV2.parse(
            document(text, source = "https://example.com/faux-agrement.pdf"),
            profile()
        )

        assertNull(result.evidence)
        assertTrue(result.reasons.any { it.contains("source APEC officielle") })
    }

    @Test
    fun `IDCC différent est refusé même si la classification correspond`() {
        val text = """
            AGREMENT DU 09.10.2024
            Branche test (IDCC 493)
            La Commission paritaire valide l'affiliation des cadres coefficient 910 à l'article 2.1 de l'ANI du 17 novembre 2017.
        """.trimIndent()
        val result = OfficialApecProtectionCategoryParserV2.parse(document(text), profile(idcc = "292"))

        assertNull(result.evidence)
        assertTrue(result.reasons.any { it.contains("IDCC") })
    }

    @Test
    fun `catégories contradictoires sur la même classification bloquent`() {
        val text = """
            AGREMENT DU 09.10.2024
            Branche test (IDCC 292)
            La Commission paritaire valide l'affiliation des cadres coefficient 910 à l'article 2.1 de l'ANI du 17 novembre 2017.
            La Commission paritaire valide l'affiliation des assimilés cadres coefficient 910 à l'article 2.2 de l'ANI du 17 novembre 2017.
        """.trimIndent()
        val result = OfficialApecProtectionCategoryParserV2.parse(document(text), profile())

        assertNull(result.evidence)
        assertTrue(result.reasons.any { it.contains("contradictoires") })
    }
}
