package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialKaliProvidentBenefitSeniorityIsolationV2Test {
    private fun profile() = ConventionLegalProfileV2(
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

    private fun category() = ProtectionCategoryV2.Result(
        aniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
        confirmed = true
    )

    private fun article(id: String, content: String) = OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
        articleId = id,
        status = "VIGUEUR_ETEN",
        content = content,
        effectiveFrom = LocalDate.of(2025, 1, 1),
        effectiveTo = null,
        title = "Garanties prévoyance",
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
    )

    @Test
    fun `ancienneté décès ne complète jamais l incapacité d un autre article`() {
        val deathId = "KALIARTI000000000801"
        val incapacityId = "KALIARTI000000000802"
        val diagnostic = OfficialKaliProvidentBenefitParserV2.parse(
            profile = profile(),
            protectionCategory = category(),
            verifiedIdcc = "292",
            auditDate = LocalDate.of(2026, 9, 8),
            articles = listOf(
                article(
                    deathId,
                    "Cadres coefficient 910. Après 3 mois ancienneté. Le capital décès est égal à 100 % du salaire annuel de référence."
                ),
                article(
                    incapacityId,
                    "Cadres coefficient 910. En cas d'incapacité temporaire, des indemnités assurent 80 % du salaire mensuel de référence, y compris les indemnités journalières de la sécurité sociale, après une franchise de 30 jours."
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
        assertEquals(
            setOf(
                ConventionProvidentBenefitV2.Family.DEATH_CAPITAL,
                ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT
            ),
            diagnostic.observedFamilies
        )
        assertEquals(setOf(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL), diagnostic.structuredFamilies)
        assertTrue(diagnostic.reasons.any { it.contains(incapacityId) })
    }

    @Test
    fun `sans anciennete décès et trois mois incapacité dans le meme article restent separes`() {
        val articleId = "KALIARTI000000000803"
        val diagnostic = OfficialKaliProvidentBenefitParserV2.parse(
            profile = profile(),
            protectionCategory = category(),
            verifiedIdcc = "292",
            auditDate = LocalDate.of(2026, 9, 8),
            articles = listOf(
                article(
                    articleId,
                    "Cadres coefficient 910. Sans condition d'ancienneté. " +
                        "Le capital décès est égal à 100 % du salaire annuel de référence. " +
                        "En cas d'incapacité temporaire, après 3 mois d'ancienneté, des indemnités assurent 80 % du salaire mensuel de référence, " +
                        "y compris les indemnités journalières de la sécurité sociale, après une franchise de 30 jours."
                )
            ),
            articleTextIds = mapOf(articleId to "KALITEXT000000000803")
        )

        assertEquals(2, diagnostic.rules.size)
        val death = diagnostic.rules.single {
            it.guarantees.single().family == ConventionProvidentBenefitV2.Family.DEATH_CAPITAL
        }
        val incapacity = diagnostic.rules.single {
            it.guarantees.single().family == ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT
        }
        assertEquals(0, death.minimumSeniorityMonths)
        assertEquals(3, incapacity.minimumSeniorityMonths)
    }
}
