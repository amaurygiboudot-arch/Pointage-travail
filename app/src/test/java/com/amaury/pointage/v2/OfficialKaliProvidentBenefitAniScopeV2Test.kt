package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialKaliProvidentBenefitAniScopeV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val articleId = "KALIARTI000000000881"
    private val textId = "KALITEXT000000000881"

    private fun profile(status: String = "NON_CADRE", coefficient: Int = 700) = ConventionLegalProfileV2(
        companyId = "company",
        idcc = "292",
        siret = "12345678901234",
        professionalStatus = status,
        classification = ConventionClassificationV2(coefficient = coefficient),
        contractType = "CDI",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = LocalDate.of(2020, 1, 1),
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private fun article(content: String) = OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
        articleId = articleId,
        status = "VIGUEUR_ETEN",
        content = content,
        effectiveFrom = LocalDate.of(2025, 1, 1),
        effectiveTo = null,
        title = "Garanties prévoyance",
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
    )

    private fun parse(
        content: String,
        category: ProtectionCategoryV2.AniCategory,
        profile: ConventionLegalProfileV2 = profile()
    ) = OfficialKaliProvidentBenefitParserV2.parse(
        profile = profile,
        protectionCategory = ProtectionCategoryV2.Result(category, true, source = "test KALI + APEC"),
        verifiedIdcc = "292",
        auditDate = date,
        articles = listOf(article(content)),
        articleTextIds = mapOf(articleId to textId)
    )

    @Test
    fun `garantie article 2_2 ne contamine jamais un salarie hors 2_1 2_2`() {
        val result = parse(
            content = "Non-cadres coefficient 700 relevant de l'article 2.2. Sans condition d'ancienneté. " +
                "Le capital décès est égal à 100 % du salaire annuel de référence.",
            category = ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2
        )

        assertTrue(result.rules.isEmpty())
        assertTrue(result.structuredFamilies.isEmpty())
    }

    @Test
    fun `garantie article 2_2 reste exploitable pour la categorie 2_2 exacte`() {
        val result = parse(
            content = "Non-cadres coefficient 700 relevant de l'article 2.2. Sans condition d'ancienneté. " +
                "Le capital décès est égal à 100 % du salaire annuel de référence.",
            category = ProtectionCategoryV2.AniCategory.ARTICLE_2_2
        )

        assertEquals(1, result.rules.size)
        assertEquals(setOf(ProtectionCategoryV2.AniCategory.ARTICLE_2_2), result.rules.single().aniCategories)
    }

    @Test
    fun `deux conditions anciennete dans la meme garantie restent ambigues`() {
        val cadreProfile = profile(status = "CADRE", coefficient = 910)
        val result = parse(
            content = "Cadres coefficient 910 relevant de l'article 2.1. Sans condition d'ancienneté. " +
                "Le capital décès est égal à 100 % du salaire annuel de référence après 3 mois d'ancienneté.",
            category = ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
            profile = cadreProfile
        )

        assertTrue(result.rules.isEmpty())
        assertTrue(result.observedFamilies.isNotEmpty())
    }
}
