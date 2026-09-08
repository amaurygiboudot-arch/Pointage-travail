package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialKaliProvidentBenefitAmbiguityV2Test {
    @Test
    fun `deux formules décès voisines ne sont jamais départagées arbitrairement`() {
        val articleId = "KALIARTI000000000777"
        val article = OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
            articleId = articleId,
            status = "VIGUEUR_ETEN",
            content = """
                Cadres coefficient 910. Sans condition d'ancienneté.
                Le capital décès est égal à 100 % du salaire annuel de référence ;
                une variante porte ce capital à 200 % du salaire annuel de référence.
            """.trimIndent(),
            effectiveFrom = LocalDate.of(2025, 1, 1),
            effectiveTo = null,
            title = "Capital décès",
            extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
        )
        val profile = ConventionLegalProfileV2(
            companyId = "c1",
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
        val diagnostic = OfficialKaliProvidentBenefitParserV2.parse(
            profile = profile,
            protectionCategory = ProtectionCategoryV2.Result(
                aniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
                confirmed = true
            ),
            verifiedIdcc = "292",
            auditDate = LocalDate.of(2026, 9, 8),
            articles = listOf(article),
            articleTextIds = mapOf(articleId to "KALITEXT000000000777")
        )

        assertTrue(diagnostic.rules.isEmpty())
        assertEquals(1, diagnostic.observedFamilies.size)
        assertTrue(diagnostic.reasons.any { it.contains("formule", ignoreCase = true) })
    }
}
