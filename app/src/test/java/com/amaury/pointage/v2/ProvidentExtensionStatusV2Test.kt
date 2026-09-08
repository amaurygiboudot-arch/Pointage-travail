package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProvidentExtensionStatusV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val classification = ConventionClassificationV2(coefficient = 910)
    private val profile = ConventionLegalProfileV2(
        companyId = "company",
        idcc = "292",
        siret = "",
        professionalStatus = "CADRE",
        classification = classification,
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

    private fun article(
        id: String,
        content: String,
        status: String = "VIGUEUR_NON_ETEN",
        fakeExtension: LocalDate? = LocalDate.of(2025, 1, 1)
    ) = OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
        articleId = id,
        status = status,
        content = content,
        effectiveFrom = LocalDate.of(2025, 1, 1),
        effectiveTo = null,
        title = "Prévoyance",
        extensionEffectiveFrom = fakeExtension
    )

    @Test
    fun `cotisation non etendue reste non etendue meme si une date residuelle est fournie`() {
        val articles = listOf(
            article(
                "KALIARTI000000008001",
                "Le régime de prévoyance bénéficie aux cadres relevant de l'article 2.1, sans condition d'ancienneté."
            ),
            article(
                "KALIARTI000000008002",
                "L'assiette de cotisation est le salaire brut total non plafonné."
            ),
            article(
                "KALIARTI000000008003",
                "Cotisation de prévoyance : part salariale : 0,40 % ; part patronale : 0,60 %."
            )
        )
        val scope = "KALITEXT000000008000"
        val diagnostic = OfficialKaliProvidentContributionParserV2.parse(
            profile = profile,
            protectionCategory = category,
            verifiedIdcc = "292",
            auditDate = date,
            articles = articles,
            articleTextIds = articles.associate { it.articleId to scope }
        )

        val rule = diagnostic.rule!!
        assertEquals(ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED, rule.extensionStatus)
        assertNull(rule.extensionEffectiveFrom)
        assertTrue(rule.structurallyValid())
    }

    @Test
    fun `garantie non etendue reste non etendue meme si une date residuelle est fournie`() {
        val id = "KALIARTI000000008101"
        val diagnostic = OfficialKaliProvidentBenefitParserV2.parse(
            profile = profile,
            protectionCategory = category,
            verifiedIdcc = "292",
            auditDate = date,
            articles = listOf(
                article(
                    id,
                    "Cadres coefficient 910. Sans condition d'ancienneté. Le capital décès est égal à 100 % du salaire annuel de référence."
                )
            ),
            articleTextIds = mapOf(id to "KALITEXT000000008100")
        )

        val rule = diagnostic.rules.single()
        assertEquals(ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED, rule.extensionStatus)
        assertNull(rule.extensionEffectiveFrom)
        assertTrue(rule.structurallyValid())

        val resolution = ConventionProvidentBenefitV2.resolve(
            rules = listOf(rule),
            idcc = "292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "CADRE",
            protectionCategory = category,
            seniorityMonths = 12
        )
        assertTrue(!resolution.reliable)
    }
}
