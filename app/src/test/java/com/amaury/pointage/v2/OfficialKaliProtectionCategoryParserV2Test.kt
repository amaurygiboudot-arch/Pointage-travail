package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialKaliProtectionCategoryParserV2Test {
    private val auditDate = LocalDate.of(2026, 9, 8)

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

    private fun article(
        content: String,
        status: String = "VIGUEUR_ETEN",
        extension: LocalDate? = LocalDate.of(2024, 12, 26),
        id: String = "KALIARTI000050828557",
        effectiveFrom: LocalDate = LocalDate.of(2025, 1, 1),
        effectiveTo: LocalDate? = null
    ) = OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
        articleId = id,
        status = status,
        content = content,
        effectiveFrom = effectiveFrom,
        effectiveTo = effectiveTo,
        title = "Catégories objectives de protection sociale complémentaire",
        extensionEffectiveFrom = extension
    )

    private fun parse(
        article: OfficialKaliOvertimeRuleParserV2.VerifiedArticle,
        profile: ConventionLegalProfileV2 = profile(),
        verifiedIdcc: String = profile.idcc
    ) = OfficialKaliProtectionCategoryParserV2.parse(article, profile, auditDate, verifiedIdcc)

    @Test
    fun `Plasturgie plage 900 a 940 classe 910 en ANI 2 1`() {
        val diagnostic = parse(
            article(
                "Pour l'application des stipulations de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les ingénieurs et cadres relevant des coefficients 900 à 940 de la classification."
            )
        )

        assertNotNull(diagnostic.rule)
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_1, diagnostic.rule!!.aniCategory)
        assertEquals(910, diagnostic.rule!!.classification.coefficient)
        assertEquals("0292", diagnostic.rule!!.idcc)
        assertEquals(ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED, diagnostic.rule!!.extensionStatus)
    }

    @Test
    fun `liste explicite de coefficients accepte seulement une valeur citee`() {
        val text = "Pour l'application de l'article 2.2 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les agents de maîtrise relevant des coefficients 375, 390 ou 405."
        val accepted = parse(
            article(text),
            profile(status = "NON_CADRE", classification = ConventionClassificationV2(coefficient = 390))
        )
        val rejected = parse(
            article(text),
            profile(status = "NON_CADRE", classification = ConventionClassificationV2(coefficient = 400))
        )

        assertNotNull(accepted.rule)
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_2, accepted.rule!!.aniCategory)
        assertNull(rejected.rule)
    }

    @Test
    fun `Plasturgie coefficient 830 classe uniquement en ANI 2 2`() {
        val diagnostic = parse(
            article(
                "Pour l'application des stipulations de l'article 2.2 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les techniciens et agents de maîtrise relevant du coefficient 830 de la classification."
            ),
            profile(status = "NON_CADRE", classification = ConventionClassificationV2(coefficient = 830))
        )

        assertNotNull(diagnostic.rule)
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_2, diagnostic.rule!!.aniCategory)
    }

    @Test
    fun `Plasturgie 800 a 820 reste seulement extension eligible`() {
        val diagnostic = parse(
            article(
                "Pour l'application des dispositions de l'article R. 242-1-1, alinéa 2 du code de la sécurité sociale, qui définissent les salariés non-cadres et non-assimilés aux cadres susceptibles de bénéficier d'une extension de régime, sont visés les techniciens et agents de maîtrise relevant du coefficient 800 à 820 de la classification."
            ),
            profile(status = "NON_CADRE", classification = ConventionClassificationV2(coefficient = 810))
        )

        assertNotNull(diagnostic.rule)
        assertEquals(ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE, diagnostic.rule!!.aniCategory)
        assertTrue(diagnostic.reasons.any { it.contains("aucune affiliation ANI 2.1/2.2") })
    }

    @Test
    fun `coefficient voisin hors plage ne recoit aucune categorie`() {
        val diagnostic = parse(
            article(
                "Pour l'application des stipulations de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les cadres relevant des coefficients 900 à 940."
            ),
            profile(classification = ConventionClassificationV2(coefficient = 830))
        )

        assertNull(diagnostic.rule)
    }

    @Test
    fun `statut non cadre contradictoire bloque ANI 2 1`() {
        val diagnostic = parse(
            article(
                "Pour l'application des stipulations de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les cadres relevant des coefficients 900 à 940."
            ),
            profile(status = "NON_CADRE")
        )

        assertNull(diagnostic.rule)
        assertTrue(diagnostic.reasons.any { it.contains("statut professionnel incompatible") })
    }

    @Test
    fun `IDCC 493 niveau VII echelon A est traite sans code metier`() {
        val text = """
            Pour l'application des dispositions conventionnelles de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les salariés cadres et ingénieurs relevant des positions hiérarchiques conventionnelles 7A (niveau VII - échelon A) et au-delà ;
            Pour l'application des dispositions conventionnelles de l'article 2.2 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les salariés agents de maîtrise et agents techniques relevant des positions hiérarchiques conventionnelles 6A (niveau VI - échelon A) et 6B (niveau VI - échelon B).
        """.trimIndent()
        val diagnostic = parse(
            article(text, id = "KALIARTI000050394592"),
            profile(
                idcc = "493",
                status = "CADRE",
                classification = ConventionClassificationV2(level = "VII", echelon = "A")
            ),
            verifiedIdcc = "0493"
        )

        assertNotNull(diagnostic.rule)
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_1, diagnostic.rule!!.aniCategory)
        assertEquals("VII", diagnostic.rule!!.classification.level)
        assertEquals("A", diagnostic.rule!!.classification.echelon)
    }

    @Test
    fun `IDCC 493 niveau VI echelon B est traite en ANI 2 2`() {
        val text = """
            Pour l'application des dispositions conventionnelles de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les salariés cadres et ingénieurs relevant des positions hiérarchiques conventionnelles 7A (niveau VII - échelon A) et au-delà ;
            Pour l'application des dispositions conventionnelles de l'article 2.2 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les salariés agents de maîtrise et agents techniques relevant des positions hiérarchiques conventionnelles 6A (niveau VI - échelon A) et 6B (niveau VI - échelon B).
        """.trimIndent()
        val diagnostic = parse(
            article(text, id = "KALIARTI000050394592"),
            profile(
                idcc = "493",
                status = "NON_CADRE",
                classification = ConventionClassificationV2(level = "VI", echelon = "B")
            ),
            verifiedIdcc = "493"
        )

        assertNotNull(diagnostic.rule)
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_2, diagnostic.rule!!.aniCategory)
    }

    @Test
    fun `niveau VI echelon C voisin est refuse`() {
        val text = "Pour l'application des dispositions conventionnelles de l'article 2.2 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les agents de maîtrise relevant du niveau VI - échelons A et B."
        val diagnostic = parse(
            article(text, id = "KALIARTI000050394592"),
            profile(
                idcc = "493",
                status = "NON_CADRE",
                classification = ConventionClassificationV2(level = "VI", echelon = "C")
            ),
            verifiedIdcc = "493"
        )

        assertNull(diagnostic.rule)
    }

    @Test
    fun `niveau et echelon ne sont jamais croises entre deux couples`() {
        val text = "Pour l'application de l'article 2.2 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les salariés du niveau VI - échelon A et du niveau VII - échelon B."
        val diagnostic = parse(
            article(text),
            profile(status = "NON_CADRE", classification = ConventionClassificationV2(level = "VI", echelon = "B"))
        )

        assertNull(diagnostic.rule)
    }

    @Test
    fun `liste explicite de positions est acceptee sans equivalence`() {
        val text = "Pour l'application de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les cadres relevant des positions I, II et III."
        val accepted = parse(article(text), profile(classification = ConventionClassificationV2(position = "II")))
        val rejected = parse(article(text), profile(classification = ConventionClassificationV2(position = "IV")))

        assertNotNull(accepted.rule)
        assertNull(rejected.rule)
    }

    @Test
    fun `texte non etendu reste une preuve mais jamais applicable automatiquement`() {
        val diagnostic = parse(
            article(
                content = "Pour l'application de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les cadres relevant des coefficients 900 à 940.",
                status = "VIGUEUR_NON_ETEN",
                extension = null
            )
        )

        assertNotNull(diagnostic.rule)
        assertEquals(ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED, diagnostic.rule!!.extensionStatus)
        assertTrue(diagnostic.reasons.any { it.contains("non étendu") })
    }

    @Test
    fun `date extension absente conserve la preuve mais bloque applicabilite`() {
        val diagnostic = parse(
            article(
                content = "Pour l'application de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les cadres relevant des coefficients 900 à 940.",
                extension = null
            )
        )

        assertNotNull(diagnostic.rule)
        assertEquals(ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN, diagnostic.rule!!.extensionStatus)
        assertTrue(diagnostic.reasons.any { it.contains("applicabilité automatique bloquée") })
    }

    @Test
    fun `deux categories contradictoires pour la meme classification bloquent toujours`() {
        val text = """
            Pour l'application de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les salariés relevant du coefficient 830 ;
            Pour l'application de l'article 2.2 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les salariés relevant du coefficient 830.
        """.trimIndent()
        val diagnostic = parse(
            article(text),
            profile(status = "NON_CADRE", classification = ConventionClassificationV2(coefficient = 830))
        )

        assertNull(diagnostic.rule)
        assertTrue(diagnostic.reasons.any { it.contains("contradictoires") })
    }

    @Test
    fun `IDCC de collecte different bloque la regle`() {
        val diagnostic = parse(
            article("Pour l'application de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les cadres relevant du coefficient 910."),
            profile(),
            verifiedIdcc = "493"
        )

        assertNull(diagnostic.rule)
        assertTrue(diagnostic.reasons.any { it.contains("IDCC") })
    }

    @Test
    fun `appel historique sans IDCC de collecte reste fail closed`() {
        val diagnostic = OfficialKaliProtectionCategoryParserV2.parse(
            article("Pour l'application de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les cadres relevant du coefficient 910."),
            profile(),
            auditDate
        )

        assertNull(diagnostic.rule)
        assertTrue(diagnostic.reasons.any { it.contains("IDCC de la collecte KALI non fourni") })
    }

    @Test
    fun `article hors periode est refuse`() {
        val diagnostic = parse(
            article(
                content = "Pour l'application de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les cadres relevant du coefficient 910.",
                effectiveFrom = LocalDate.of(2026, 10, 1)
            )
        )

        assertNull(diagnostic.rule)
        assertTrue(diagnostic.reasons.any { it.contains("hors période") })
    }

    @Test
    fun `statut officiel inconnu est refuse`() {
        val diagnostic = parse(
            article(
                content = "Pour l'application de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les cadres relevant du coefficient 910.",
                status = "VIGUEUR"
            )
        )

        assertNull(diagnostic.rule)
        assertTrue(diagnostic.reasons.any { it.contains("statut officiel") })
    }

    @Test
    fun `identifiant non KALIARTI est refuse`() {
        val diagnostic = parse(
            article(
                content = "Pour l'application de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les cadres relevant du coefficient 910.",
                id = "KALITEXT000012345678"
            )
        )

        assertNull(diagnostic.rule)
    }

    @Test
    fun `fonction equivalente sans classification explicite est refusee`() {
        val diagnostic = parse(
            article(
                "Pour l'application de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visées les fonctions équivalentes aux cadres de haut niveau."
            )
        )

        assertNull(diagnostic.rule)
    }
}
