package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProtectionCategoryV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        id: String = "KALIARTI_TEST",
        effectiveFrom: LocalDate = LocalDate.of(2025, 1, 1)
    ) = OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
        articleId = id,
        status = status,
        content = content,
        effectiveFrom = effectiveFrom,
        effectiveTo = null,
        title = "Catégories objectives de protection sociale complémentaire",
        extensionEffectiveFrom = extension
    )

    private fun approval(date: LocalDate = LocalDate.of(2024, 10, 9)) =
        ConventionProtectionCategoryV2.ApprovalEvidence(
            authority = ConventionProtectionCategoryV2.ApprovalAuthority.APEC,
            approvedOn = date,
            source = "APEC — décision de test"
        )

    @Test
    fun `Plasturgie plage 900 a 940 parse 910 mais exige encore agrement`() {
        val diagnostic = OfficialKaliProtectionCategoryParserV2.parse(
            article("Pour l'application des stipulations de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les cadres relevant des coefficients 900 à 940."),
            profile(),
            auditDate
        )

        val rule = diagnostic.rule
        assertNotNull(rule)
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_1, rule!!.aniCategory)
        assertTrue(rule.approvalRequired)
        assertNull(rule.approvalEvidence)

        val blocked = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = auditDate,
            classification = ConventionClassificationV2(coefficient = 910),
            professionalStatus = "CADRE",
            rules = listOf(rule)
        )
        assertFalse(blocked.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.TO_CONFIRM, blocked.category.aniCategory)
    }

    @Test
    fun `Plasturgie 910 devient ANI 2 1 seulement avec agrement applicable`() {
        val parsed = OfficialKaliProtectionCategoryParserV2.parse(
            article("Pour l'application des stipulations de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les cadres relevant des coefficients 900 à 940."),
            profile(),
            auditDate
        ).rule!!

        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = auditDate,
            classification = ConventionClassificationV2(coefficient = 910),
            professionalStatus = "CADRE",
            rules = listOf(parsed.copy(approvalEvidence = approval()))
        )

        assertTrue(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_1, result.category.aniCategory)
        assertTrue(result.category.aniBeneficiaryConfirmed)
    }

    @Test
    fun `Plasturgie coefficient 830 parse ANI 2 2`() {
        val diagnostic = OfficialKaliProtectionCategoryParserV2.parse(
            article("Pour l'application des stipulations de l'article 2.2 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les techniciens et agents de maîtrise relevant du coefficient 830."),
            profile(status = "NON_CADRE", classification = ConventionClassificationV2(coefficient = 830)),
            auditDate
        )

        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_2, diagnostic.rule?.aniCategory)
    }

    @Test
    fun `Plasturgie 800 a 820 reste extension eligible et jamais beneficiaire ANI`() {
        val parsed = OfficialKaliProtectionCategoryParserV2.parse(
            article("Pour l'application de l'article R. 242-1-1 du code de la sécurité sociale, les techniciens et agents de maîtrise des coefficients 800 à 820 peuvent être intégrés à la catégorie des cadres en vue de la constitution d'une catégorie objective."),
            profile(status = "NON_CADRE", classification = ConventionClassificationV2(coefficient = 810)),
            auditDate
        ).rule!!

        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = auditDate,
            classification = ConventionClassificationV2(coefficient = 810),
            professionalStatus = "NON_CADRE",
            rules = listOf(parsed.copy(approvalEvidence = approval()))
        )

        assertTrue(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.EXTENSION_ELIGIBLE, result.category.aniCategory)
        assertFalse(result.category.aniBeneficiaryConfirmed)
        assertTrue(result.warnings.any { it.contains("affiliation", ignoreCase = true) || it.contains("choix", ignoreCase = true) })
    }

    @Test
    fun `coefficient voisin 899 est refuse`() {
        val diagnostic = OfficialKaliProtectionCategoryParserV2.parse(
            article("Pour l'application de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les cadres relevant des coefficients 900 à 940."),
            profile(classification = ConventionClassificationV2(coefficient = 899)),
            auditDate
        )
        assertNull(diagnostic.rule)
    }

    @Test
    fun `borne coefficient egal ou superieur est comprise sans code metier`() {
        val diagnostic = OfficialKaliProtectionCategoryParserV2.parse(
            article("Pour l'application de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les cadres dont le coefficient est égal ou supérieur à 350."),
            profile(idcc = "999", classification = ConventionClassificationV2(coefficient = 420)),
            auditDate
        )
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_1, diagnostic.rule?.aniCategory)
    }

    @Test
    fun `liste de coefficients explicite est comprise`() {
        val diagnostic = OfficialKaliProtectionCategoryParserV2.parse(
            article("Pour l'application de l'article 2.2 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les techniciens relevant des coefficients 270 et 300."),
            profile(idcc = "999", status = "NON_CADRE", classification = ConventionClassificationV2(coefficient = 300)),
            auditDate
        )
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_2, diagnostic.rule?.aniCategory)
    }

    @Test
    fun `groupes F et G sont compris`() {
        val diagnostic = OfficialKaliProtectionCategoryParserV2.parse(
            article("Pour l'application de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les cadres classés groupes F et G."),
            profile(idcc = "999", classification = ConventionClassificationV2(group = "G")),
            auditDate
        )
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_1, diagnostic.rule?.aniCategory)
    }

    @Test
    fun `groupe requis mais absent de la fiche bloque le classement`() {
        val diagnostic = OfficialKaliProtectionCategoryParserV2.parse(
            article("Pour l'application de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les cadres classés groupes F et G et coefficient 500."),
            profile(idcc = "999", classification = ConventionClassificationV2(coefficient = 500)),
            auditDate
        )
        assertNull(diagnostic.rule)
    }

    @Test
    fun `positions 1 a 3 sont comprises`() {
        val diagnostic = OfficialKaliProtectionCategoryParserV2.parse(
            article("Pour l'application de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les cadres des positions 1 à 3."),
            profile(idcc = "999", classification = ConventionClassificationV2(position = "2")),
            auditDate
        )
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_1, diagnostic.rule?.aniCategory)
    }

    @Test
    fun `niveau VI echelon B est ANI 2 2 dans une autre convention`() {
        val diagnostic = OfficialKaliProtectionCategoryParserV2.parse(
            article("Pour l'application de l'article 2.2 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les agents de maîtrise relevant du niveau VI - échelons A et B.", id = "KALIARTI_493"),
            profile(idcc = "493", status = "NON_CADRE", classification = ConventionClassificationV2(level = "VI", echelon = "B")),
            auditDate
        )
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_2, diagnostic.rule?.aniCategory)
    }

    @Test
    fun `niveau VI echelon C voisin est refuse`() {
        val diagnostic = OfficialKaliProtectionCategoryParserV2.parse(
            article("Pour l'application de l'article 2.2 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les agents de maîtrise relevant du niveau VI - échelons A et B.", id = "KALIARTI_493"),
            profile(idcc = "493", status = "NON_CADRE", classification = ConventionClassificationV2(level = "VI", echelon = "C")),
            auditDate
        )
        assertNull(diagnostic.rule)
    }

    @Test
    fun `coefficient correct mais echelon contradictoire est refuse`() {
        val diagnostic = OfficialKaliProtectionCategoryParserV2.parse(
            article("Pour l'application de l'article 2.2 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les techniciens coefficient 310, niveau V, échelon D."),
            profile(idcc = "999", status = "NON_CADRE", classification = ConventionClassificationV2(coefficient = 310, level = "V", echelon = "C")),
            auditDate
        )
        assertNull(diagnostic.rule)
    }

    @Test
    fun `texte non etendu est parse comme preuve mais resolution reste bloquee`() {
        val parsed = OfficialKaliProtectionCategoryParserV2.parse(
            article(
                content = "Pour l'application de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les cadres relevant des coefficients 900 à 940.",
                status = "VIGUEUR_NON_ETEN",
                extension = null
            ),
            profile(),
            auditDate
        ).rule!!

        assertEquals(ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED, parsed.extensionStatus)
        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = auditDate,
            classification = ConventionClassificationV2(coefficient = 910),
            professionalStatus = "CADRE",
            rules = listOf(parsed.copy(approvalEvidence = approval()))
        )
        assertFalse(result.reliable)
    }

    @Test
    fun `agrement futur bloque la periode anterieure`() {
        val parsed = OfficialKaliProtectionCategoryParserV2.parse(
            article("Pour l'application de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visés les cadres relevant des coefficients 900 à 940."),
            profile(),
            auditDate
        ).rule!!

        val result = ConventionProtectionCategoryV2.resolve(
            idcc = "292",
            referenceDate = LocalDate.of(2025, 1, 10),
            classification = ConventionClassificationV2(coefficient = 910),
            professionalStatus = "CADRE",
            rules = listOf(parsed.copy(approvalEvidence = approval(LocalDate.of(2025, 2, 1))))
        )
        assertFalse(result.reliable)
        assertEquals(ProtectionCategoryV2.AniCategory.TO_CONFIRM, result.category.aniCategory)
    }

    @Test
    fun `fonction equivalente sans classification explicite est refusee`() {
        val diagnostic = OfficialKaliProtectionCategoryParserV2.parse(
            article("Pour l'application de l'article 2.1 de l'accord national interprofessionnel du 17 novembre 2017 relatif à la prévoyance des cadres, sont visées les fonctions équivalentes aux cadres de haut niveau."),
            profile(),
            auditDate
        )
        assertNull(diagnostic.rule)
    }
}
