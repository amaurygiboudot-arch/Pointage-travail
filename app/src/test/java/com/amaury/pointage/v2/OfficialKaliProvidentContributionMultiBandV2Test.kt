package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialKaliProvidentContributionMultiBandV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val scope = "KALITEXT000000000951"

    private val profile = ConventionLegalProfileV2(
        companyId = "company",
        idcc = "292",
        siret = "12345678901234",
        professionalStatus = "NON_CADRE",
        classification = ConventionClassificationV2(coefficient = 700),
        contractType = "CDI",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = LocalDate.of(2020, 1, 1),
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private val category = ProtectionCategoryV2.Result(
        aniCategory = ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2,
        confirmed = true,
        source = "test KALI + APEC"
    )

    private fun article(
        id: String,
        content: String,
        status: String = "VIGUEUR_ETEN",
        extension: LocalDate? = LocalDate.of(2025, 1, 1)
    ) = OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
        articleId = id,
        status = status,
        content = content,
        effectiveFrom = LocalDate.of(2025, 1, 1),
        effectiveTo = null,
        title = "Prévoyance",
        extensionEffectiveFrom = extension
    )

    private fun beneficiary(
        status: String = "VIGUEUR_ETEN",
        extension: LocalDate? = LocalDate.of(2025, 1, 1)
    ) = article(
        id = "KALIARTI000000000951",
        content = "Le régime de prévoyance bénéficie aux salariés ne relevant pas des articles 2.1 et 2.2 sans condition d'ancienneté.",
        status = status,
        extension = extension
    )

    private fun multiBand(
        status: String = "VIGUEUR_ETEN",
        extension: LocalDate? = LocalDate.of(2025, 1, 1)
    ) = article(
        id = "KALIARTI000000000952",
        content = "Cotisations de prévoyance dans la limite de 4 PMSS. " +
            "Tranche 1 : de 0 à 1 PMSS, part salariale : 0,40 %, part patronale : 0,60 %. " +
            "Tranche 2 : de 1 à 4 PMSS, part salariale : 0,80 %, part patronale : 1,20 %.",
        status = status,
        extension = extension
    )

    private fun parse(
        articles: List<OfficialKaliOvertimeRuleParserV2.VerifiedArticle>,
        parents: Map<String, String> = articles.associate { it.articleId to scope }
    ) = OfficialKaliProvidentContributionParserV2.parse(
        profile = profile,
        protectionCategory = category,
        verifiedIdcc = "292",
        auditDate = date,
        articles = articles,
        articleTextIds = parents
    )

    @Test
    fun `barème KALI multi tranches devient une règle unique avec deux bandes`() {
        val result = parse(listOf(beneficiary(), multiBand()))

        val rule = result.rule!!
        assertTrue(rule.structurallyValid())
        assertEquals(scope, result.conventionScopeKey)
        assertEquals(2, rule.tiers.single().bands.size)
        assertEquals(0.0, rule.tiers.single().bands[0].lowerCeilingMultiple, 0.000001)
        assertEquals(1.0, rule.tiers.single().bands[0].upperCeilingMultiple!!, 0.000001)
        assertEquals(1.0, rule.tiers.single().bands[1].lowerCeilingMultiple, 0.000001)
        assertEquals(4.0, rule.tiers.single().bands[1].upperCeilingMultiple!!, 0.000001)
        assertEquals(ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED, rule.extensionStatus)
        assertEquals(2, result.usedArticleIds.size)
    }

    @Test
    fun `plafond global dans un article séparé bloque le barème complet`() {
        val bandsOnly = article(
            id = "KALIARTI000000000952",
            content = "Tranche 1 : de 0 à 1 PMSS, salarié 0,40 %, employeur 0,60 %. " +
                "Tranche 2 : de 1 à 4 PMSS, salarié 0,80 %, employeur 1,20 %."
        )
        val capOnly = article(
            id = "KALIARTI000000000953",
            content = "Les cotisations de prévoyance sont dans la limite de 4 PMSS."
        )

        val result = parse(listOf(beneficiary(), bandsOnly, capOnly))

        assertNull(result.rule)
    }

    @Test
    fun `barème explicitement non étendu reste une preuve non applicable`() {
        val result = parse(
            listOf(
                beneficiary(status = "VIGUEUR_NON_ETEN", extension = null),
                multiBand(status = "VIGUEUR_NON_ETEN", extension = null)
            )
        )

        val rule = result.rule!!
        assertEquals(ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED, rule.extensionStatus)
        assertTrue(result.reasons.any { it.contains("extension") })
    }
}
