package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialKaliProvidentBenefitWaitingPeriodV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val articleId = "KALIARTI000000009600"
    private val textId = "KALITEXT000000009600"

    private val profile = ConventionLegalProfileV2(
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
    )

    private fun parse(content: String) = OfficialKaliProvidentBenefitParserV2.parse(
        profile = profile,
        protectionCategory = ProtectionCategoryV2.Result(
            aniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
            confirmed = true,
            source = "test KALI + APEC"
        ),
        verifiedIdcc = "292",
        auditDate = date,
        articles = listOf(
            OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
                articleId = articleId,
                status = "VIGUEUR_ETEN",
                content = content,
                effectiveFrom = LocalDate.of(2025, 1, 1),
                effectiveTo = null,
                title = "Incapacité temporaire",
                extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
            )
        ),
        articleTextIds = mapOf(articleId to textId)
    )

    @Test
    fun `franchises maladie et accident differentes restent ambigues`() {
        val result = parse(
            "Cadres coefficient 910 relevant de l'article 2.1. Sans condition d'ancienneté. " +
                "En incapacité temporaire de travail, une indemnité égale à 80 % du salaire mensuel de référence est versée. " +
                "Franchise de 30 jours en cas de maladie et franchise de 0 jours en cas d'accident."
        )

        assertTrue(result.rules.isEmpty())
        assertTrue(ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT in result.observedFamilies)
    }

    @Test
    fun `franchise unique reste structurée`() {
        val result = parse(
            "Cadres coefficient 910 relevant de l'article 2.1. Sans condition d'ancienneté. " +
                "En incapacité temporaire de travail, une indemnité égale à 80 % du salaire mensuel de référence est versée. " +
                "Franchise de 30 jours."
        )

        assertEquals(1, result.rules.size)
        assertEquals(30, result.rules.single().guarantees.single().waitingPeriodDays)
    }
}
