package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialKaliProvidentBenefitFamilyIsolationV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val profile = ConventionLegalProfileV2(
        companyId = "company",
        idcc = "292",
        siret = "",
        professionalStatus = "CADRE",
        classification = ConventionClassificationV2(coefficient = 910),
        contractType = null,
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = LocalDate.of(2020, 1, 1),
        weeklyHours = null,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )
    private val category = ProtectionCategoryV2.Result(
        aniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
        confirmed = true
    )

    private fun parse(content: String): OfficialKaliProvidentBenefitParserV2.Diagnostic {
        val articleId = "KALIARTI000000009100"
        val article = OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
            articleId = articleId,
            status = "VIGUEUR_ETEN",
            content = content,
            effectiveFrom = LocalDate.of(2025, 1, 1),
            effectiveTo = null,
            title = "Garanties de prévoyance",
            extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
        )
        return OfficialKaliProvidentBenefitParserV2.parse(
            profile = profile,
            protectionCategory = category,
            verifiedIdcc = "292",
            auditDate = date,
            articles = listOf(article),
            articleTextIds = mapOf(articleId to "KALITEXT000000009100")
        )
    }

    @Test
    fun `formule invalidite voisine ne complete jamais un capital deces sans formule`() {
        val diagnostic = parse(
            """
            Cadres coefficient 910. Sans condition d'ancienneté.
            Capital décès : garantie prévue par le régime.
            Invalidité : rente égale à 80 % du salaire annuel de référence, sans condition d'ancienneté, y compris la pension de sécurité sociale.
            """.trimIndent()
        )

        assertTrue(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL in diagnostic.observedFamilies)
        assertFalse(diagnostic.rules.flatMap { it.guarantees }.any { it.family == ConventionProvidentBenefitV2.Family.DEATH_CAPITAL })
    }

    @Test
    fun `formule deces voisine ne complete jamais une invalidite sans formule`() {
        val diagnostic = parse(
            """
            Cadres coefficient 910. Sans condition d'ancienneté.
            Capital décès : 200 % du salaire annuel de référence, sans condition d'ancienneté.
            Invalidité : une rente est prévue par le régime, y compris la pension de sécurité sociale.
            """.trimIndent()
        )

        assertTrue(ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION in diagnostic.observedFamilies)
        assertFalse(diagnostic.rules.flatMap { it.guarantees }.any { it.family == ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION })
    }
}
