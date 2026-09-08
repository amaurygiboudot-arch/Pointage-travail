package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialKaliProvidentBenefitOccurrenceV2Test {
    @Test
    fun `un supplement deces non structure reste visible meme si le capital de base est prouve`() {
        val articleId = "KALIARTI000000009700"
        val diagnostic = OfficialKaliProvidentBenefitParserV2.parse(
            profile = ConventionLegalProfileV2(
                companyId = "company",
                idcc = "292",
                siret = "12345678901234",
                professionalStatus = "CADRE",
                classification = ConventionClassificationV2(coefficient = 910),
                contractType = "CDI",
                entryDate = LocalDate.of(2020, 1, 1),
                conventionSeniorityDate = LocalDate.of(2020, 1, 1),
                weeklyHours = 35.0,
                forfaitAnnualHours = null,
                forfaitAnnualDays = null
            ),
            protectionCategory = ProtectionCategoryV2.Result(
                aniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
                confirmed = true,
                source = "test KALI + APEC"
            ),
            verifiedIdcc = "292",
            auditDate = LocalDate.of(2026, 9, 8),
            articles = listOf(
                OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
                    articleId = articleId,
                    status = "VIGUEUR_ETEN",
                    content = "Cadres coefficient 910 relevant de l'article 2.1. Sans condition d'ancienneté. " +
                        "Le capital décès est égal à 100 % du salaire annuel de référence. " +
                        "Un capital décès accidentel complémentaire est également prévu selon les conditions du régime.",
                    effectiveFrom = LocalDate.of(2025, 1, 1),
                    effectiveTo = null,
                    title = "Garanties prévoyance",
                    extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
                )
            ),
            articleTextIds = mapOf(articleId to "KALITEXT000000009700")
        )

        assertEquals(1, diagnostic.rules.size)
        assertEquals(
            ConventionProvidentBenefitV2.Family.DEATH_CAPITAL,
            diagnostic.rules.single().guarantees.single().family
        )
        assertTrue(
            ConventionProvidentBenefitV2.Family.DEATH_CAPITAL in diagnostic.unresolvedOccurrenceFamilies
        )
    }
}
