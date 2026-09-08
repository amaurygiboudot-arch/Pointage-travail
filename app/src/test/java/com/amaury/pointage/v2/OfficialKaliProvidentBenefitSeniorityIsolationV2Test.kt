package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialKaliProvidentBenefitSeniorityIsolationV2Test {
    @Test
    fun `ancienneté décès ne complète jamais l incapacité d un autre article`() {
        val deathId = "KALIARTI000000000801"
        val incapacityId = "KALIARTI000000000802"
        fun article(id: String, content: String) = OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
            articleId = id,
            status = "VIGUEUR_ETEN",
            content = content,
            effectiveFrom = LocalDate.of(2025, 1, 1),
            effectiveTo = null,
            title = "Garanties prévoyance",
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
            articles = listOf(
                article(
                    deathId,
                    "Cadres coefficient 910. Après 3 mois ancienneté. Le capital décès est égal à 100 % du salaire annuel de référence."
                ),
                article(
                    incapacityId,
                    "Cadres coefficient 910. En cas d'incapacité temporaire, des indemnités assurent 80 % du salaire mensuel de référence."
                )
            ),
            articleTextIds = mapOf(
                deathId to "KALITEXT000000000800",
                incapacityId to "KALITEXT000000000800"
            )
        )

        assertEquals(1, diagnostic.rules.size)
        assertEquals(
            ConventionProvidentBenefitV2.Family.DEATH_CAPITAL,
            diagnostic.rules.single().guarantees.single().family
        )
        assertEquals(3, diagnostic.rules.single().minimumSeniorityMonths)
        assertTrue(diagnostic.reasons.any { it.contains(incapacityId) && it.contains("ancienneté") })
    }
}
