package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialKaliProvidentContributionClassificationScopeV2Test {
    private val referenceDate = LocalDate.of(2026, 1, 31)
    private val scope = "KALITEXT000000000001"
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
    private val category = ProtectionCategoryV2.Result(
        aniCategory = ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2,
        confirmed = true,
        source = "test KALI + APEC"
    )

    private fun article(id: String, content: String) = OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
        articleId = id,
        status = "VIGUEUR_ETEN",
        content = content,
        effectiveFrom = LocalDate.of(2025, 1, 1),
        effectiveTo = null,
        title = null,
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
    )

    private fun parse(articles: List<OfficialKaliOvertimeRuleParserV2.VerifiedArticle>) =
        OfficialKaliProvidentContributionParserV2.parse(
            profile = profile,
            protectionCategory = category,
            verifiedIdcc = "292",
            auditDate = referenceDate,
            articles = articles,
            articleTextIds = articles.associate { it.articleId to scope }
        )

    private fun baseArticles(rateContent: String) = listOf(
        article(
            "KALIARTI000000000003",
            "Le régime de prévoyance bénéficie aux salariés ne relevant pas des articles 2.1 et 2.2 après 3 mois d'ancienneté."
        ),
        article(
            "KALIARTI000000000004",
            "Le salaire de référence servant d'assiette est limité à 4 fois le plafond mensuel de la sécurité sociale."
        ),
        article("KALIARTI000000000005", rateContent)
    )

    @Test
    fun `preuve generale est persistee uniquement pour la classification exacte du profil`() {
        val result = parse(
            baseArticles("Cotisation de prévoyance. Part salariale : 0,40 % ; part patronale : 0,40 %.")
        )

        assertTrue(result.rule != null)
        assertEquals(700, result.rule!!.classification.coefficient)
    }

    @Test
    fun `taux explicitement reserve a un coefficient voisin ne complete jamais la regle`() {
        val result = parse(
            baseArticles("Coefficient 800. Cotisation de prévoyance. Part salariale : 0,40 % ; part patronale : 0,40 %.")
        )

        assertNull(result.rule)
    }

    @Test
    fun `taux explicitement lie au coefficient exact peut etre utilise`() {
        val result = parse(
            baseArticles("Coefficient 700. Cotisation de prévoyance. Part salariale : 0,40 % ; part patronale : 0,40 %.")
        )

        assertTrue(result.rule != null)
        assertEquals(700, result.rule!!.classification.coefficient)
    }

    @Test
    fun `article sans coefficient mais reserve aux cadres ne finance jamais un non cadre`() {
        val result = parse(
            baseArticles("Cadres : cotisation de prévoyance. Part salariale : 0,40 % ; part patronale : 0,40 %.")
        )

        assertNull(result.rule)
    }

    @Test
    fun `article 2_2 ne finance jamais un salarie confirme hors 2_1 2_2`() {
        val result = parse(
            baseArticles(
                "Salariés relevant de l'article 2.2 : cotisation de prévoyance. " +
                    "Part salariale : 0,40 % ; part patronale : 0,40 %."
            )
        )

        assertNull(result.rule)
    }

    @Test
    fun `plusieurs baremes de classifications dans le meme article bloquent au lieu de prendre le premier`() {
        val result = parse(
            baseArticles(
                "Coefficient 700. Cotisation de prévoyance. Part salariale : 0,40 % ; part patronale : 0,40 %. " +
                    "Coefficient 800. Cotisation de prévoyance. Part salariale : 0,60 % ; part patronale : 0,20 %."
            )
        )

        assertNull(result.rule)
    }
}
