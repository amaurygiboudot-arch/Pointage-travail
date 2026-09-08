package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialKaliProvidentContributionParserV2Test {
    private val date = LocalDate.of(2026, 1, 31)
    private val text1 = "KALITEXT000000000001"
    private val text2 = "KALITEXT000000000002"

    private val profile = ConventionLegalProfileV2(
        companyId = "company",
        idcc = "292",
        siret = "",
        professionalStatus = "NON_CADRE",
        classification = ConventionClassificationV2(coefficient = 700),
        contractType = null,
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = null,
        weeklyHours = null,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private val outsideAni = ProtectionCategoryV2.Result(
        aniCategory = ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2,
        confirmed = true,
        source = "test KALI + APEC"
    )

    private fun article(
        id: String,
        title: String,
        content: String,
        extensionDate: LocalDate? = LocalDate.of(2025, 1, 1),
        effectiveFrom: LocalDate = LocalDate.of(2025, 1, 1)
    ) = OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
        articleId = id,
        status = "VIGUEUR_ETEN",
        content = content,
        effectiveFrom = effectiveFrom,
        effectiveTo = null,
        title = title,
        extensionEffectiveFrom = extensionDate
    )

    private fun standardArticles() = listOf(
        article(
            "KALIARTI000000000003",
            "Article 3 - Bénéficiaires",
            "Le régime de prévoyance bénéficie aux salariés ne relevant pas des articles 2.1 et 2.2 après 3 mois d'ancienneté."
        ),
        article(
            "KALIARTI000000000004",
            "Article 4 - Salaire de référence",
            "Le salaire de référence servant d'assiette est limité à 4 fois le plafond mensuel de la sécurité sociale."
        ),
        article(
            "KALIARTI000000000005",
            "Article 5 - Cotisations",
            "Cotisation de prévoyance. Part salariale : 0,40 % ; part patronale : 0,40 %."
        )
    )

    private fun parse(
        articles: List<OfficialKaliOvertimeRuleParserV2.VerifiedArticle> = standardArticles(),
        parents: Map<String, String> = articles.associate { it.articleId to text1 },
        category: ProtectionCategoryV2.Result = outsideAni,
        ambiguous: Set<String> = emptySet(),
        verifiedIdcc: String = "292"
    ) = OfficialKaliProvidentContributionParserV2.parse(
        profile = profile,
        protectionCategory = category,
        verifiedIdcc = verifiedIdcc,
        auditDate = date,
        articles = articles,
        articleTextIds = parents,
        ambiguousArticleTextIds = ambiguous
    )

    @Test
    fun `assemble beneficiaires assiette et taux seulement dans le meme KALITEXT`() {
        val result = parse()

        assertTrue(result.rule != null)
        assertEquals(text1, result.conventionScopeKey)
        assertEquals(3, result.usedArticleIds.size)
        val rule = result.rule!!
        assertTrue(rule.structurallyValid())
        assertEquals(3, rule.tiers.single().minimumSeniorityMonths)
        val band = rule.tiers.single().bands.single()
        assertEquals(4.0, band.upperCeilingMultiple!!, 0.001)
        assertEquals(0.004, band.employeeRate, 0.000001)
        assertEquals(0.004, band.employerRate, 0.000001)
        assertEquals(text1, rule.conventionScopeKey)
    }

    @Test
    fun `articles repartis sur deux KALITEXT ne sont jamais assembles`() {
        val articles = standardArticles()
        val parents = mapOf(
            articles[0].articleId to text1,
            articles[1].articleId to text2,
            articles[2].articleId to text2
        )

        val result = parse(articles = articles, parents = parents)

        assertNull(result.rule)
        assertTrue(result.reasons.any { it.contains("aucun KALITEXT") })
    }

    @Test
    fun `taux global sans repartition explicite est refuse`() {
        val articles = standardArticles().dropLast(1) + article(
            "KALIARTI000000000005",
            "Article 5 - Cotisations",
            "La cotisation de prévoyance est de 0,80 %."
        )

        val result = parse(articles = articles)

        assertNull(result.rule)
    }

    @Test
    fun `deux repartitions contradictoires dans le meme KALITEXT bloquent`() {
        val articles = standardArticles() + article(
            "KALIARTI000000000006",
            "Article 5 bis - Cotisations",
            "Cotisation de prévoyance. Part salariale : 0,50 % ; part patronale : 0,30 %."
        )

        val result = parse(articles = articles)

        assertNull(result.rule)
    }

    @Test
    fun `categorie ANI non confirmee bloque avant toute lecture de taux`() {
        val result = parse(
            category = ProtectionCategoryV2.Result(
                aniCategory = ProtectionCategoryV2.AniCategory.TO_CONFIRM,
                confirmed = false
            )
        )

        assertNull(result.rule)
        assertTrue(result.reasons.any { it.contains("catégorie ANI") })
    }

    @Test
    fun `IDCC voisin est refuse`() {
        val result = parse(verifiedIdcc = "493")

        assertNull(result.rule)
        assertTrue(result.reasons.any { it.contains("IDCC") })
    }

    @Test
    fun `article de taux au parent ambigu est exclu et bloque le bareme`() {
        val articles = standardArticles()
        val result = parse(
            articles = articles,
            ambiguous = setOf(articles[2].articleId)
        )

        assertNull(result.rule)
    }

    @Test
    fun `texte explicitement non etendu conserve la preuve mais bloque son applicabilite automatique`() {
        val articles = standardArticles().map { it.copy(extensionEffectiveFrom = null, status = "VIGUEUR_NON_ETEN") }

        val result = parse(articles = articles)

        assertTrue(result.rule != null)
        assertEquals(
            com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED,
            result.rule!!.extensionStatus
        )
        assertTrue(result.reasons.any { it.contains("extension") })
    }

    @Test
    fun `article futur ne complete jamais un KALITEXT courant`() {
        val articles = standardArticles().mapIndexed { index, value ->
            if (index == 2) value.copy(effectiveFrom = LocalDate.of(2027, 1, 1)) else value
        }

        val result = parse(articles = articles)

        assertNull(result.rule)
    }

    @Test
    fun `anciennete non explicite bloque la regle`() {
        val articles = standardArticles().mapIndexed { index, value ->
            if (index == 0) value.copy(
                content = "Le régime de prévoyance bénéficie aux salariés ne relevant pas des articles 2.1 et 2.2."
            ) else value
        }

        val result = parse(articles = articles)

        assertNull(result.rule)
    }

    @Test
    fun `assiette non explicite bloque la regle`() {
        val articles = standardArticles().mapIndexed { index, value ->
            if (index == 1) value.copy(content = "Le salaire de référence est défini par le régime.") else value
        }

        val result = parse(articles = articles)

        assertNull(result.rule)
    }
}
