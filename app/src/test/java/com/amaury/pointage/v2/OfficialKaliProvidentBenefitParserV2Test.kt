package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialKaliProvidentBenefitParserV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val textId = "KALITEXT000000000001"

    private fun profile(
        idcc: String = "292",
        coefficient: Int = 910,
        status: String = "CADRE"
    ) = ConventionLegalProfileV2(
        companyId = "c1",
        idcc = idcc,
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

    private fun category() = ProtectionCategoryV2.Result(
        aniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
        confirmed = true
    )

    private fun article(
        id: String,
        content: String,
        extension: LocalDate? = LocalDate.of(2025, 1, 1)
    ) = OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
        articleId = id,
        status = "VIGUEUR_ETEN",
        content = content,
        effectiveFrom = LocalDate.of(2025, 1, 1),
        effectiveTo = null,
        title = "Garanties du régime de prévoyance",
        extensionEffectiveFrom = extension
    )

    private fun parse(
        articles: List<OfficialKaliOvertimeRuleParserV2.VerifiedArticle>,
        profile: ConventionLegalProfileV2 = profile(),
        idcc: String = "292",
        parents: Map<String, String> = articles.associate { it.articleId to textId },
        ambiguous: Set<String> = emptySet()
    ) = OfficialKaliProvidentBenefitParserV2.parse(
        profile = profile,
        protectionCategory = category(),
        verifiedIdcc = idcc,
        auditDate = date,
        articles = articles,
        articleTextIds = parents,
        ambiguousArticleTextIds = ambiguous
    )

    @Test
    fun `capital décès exact est structuré sur le coefficient local`() {
        val id = "KALIARTI000000000101"
        val result = parse(
            listOf(
                article(
                    id,
                    "Cadres coefficient 910. Sans condition d'ancienneté. Le capital décès est égal à 100 % du salaire annuel de référence."
                )
            )
        )

        assertEquals(setOf(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL), result.observedFamilies)
        assertEquals(setOf(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL), result.structuredFamilies)
        val guarantee = result.rules.single().guarantees.single()
        assertEquals(ConventionProvidentBenefitV2.Basis.ANNUAL_REFERENCE_SALARY, guarantee.formula.basis)
        assertEquals(1.0, guarantee.formula.coefficient!!, 0.000001)
        assertEquals(setOf(id), guarantee.evidenceArticleIds)
    }

    @Test
    fun `coefficient voisin cité bloque la structuration`() {
        val result = parse(
            listOf(
                article(
                    "KALIARTI000000000102",
                    "Cadres coefficient 920. Sans condition d'ancienneté. Le capital décès est égal à 100 % du salaire annuel de référence."
                )
            )
        )

        assertTrue(result.rules.isEmpty())
        assertEquals(setOf(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL), result.observedFamilies)
    }

    @Test
    fun `mention décès sans formule reste observée mais jamais inventée`() {
        val result = parse(
            listOf(
                article(
                    "KALIARTI000000000103",
                    "Cadres coefficient 910. Sans condition d'ancienneté. Une garantie de capital décès est prévue par le régime."
                )
            )
        )

        assertTrue(result.rules.isEmpty())
        assertEquals(setOf(ConventionProvidentBenefitV2.Family.DEATH_CAPITAL), result.observedFamilies)
        assertTrue(result.reasons.any { it.contains("mentionnée") })
    }

    @Test
    fun `incapacité conserve franchise et interaction sécurité sociale`() {
        val result = parse(
            listOf(
                article(
                    "KALIARTI000000000104",
                    "Cadres coefficient 910. Sans condition d'ancienneté. En cas d'incapacité temporaire, des indemnités assurent 80 % du salaire mensuel de référence, y compris les indemnités journalières de la sécurité sociale, après une franchise de 30 jours."
                )
            )
        )

        val guarantee = result.rules.single().guarantees.single()
        assertEquals(ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT, guarantee.family)
        assertEquals(30, guarantee.waitingPeriodDays)
        assertEquals(
            ConventionProvidentBenefitV2.SocialSecurityTreatment.INCLUDED_IN_TARGET_TOTAL,
            guarantee.socialSecurityTreatment
        )
    }

    @Test
    fun `invalidité citant deux catégories dans la même formule reste non structurée`() {
        val result = parse(
            listOf(
                article(
                    "KALIARTI000000000105",
                    "Cadres coefficient 910. Sans condition d'ancienneté. En invalidité 2e catégorie et 3e catégorie, une rente égale à 70 % du salaire annuel de référence est versée."
                )
            )
        )

        assertTrue(result.rules.isEmpty())
        assertEquals(setOf(ConventionProvidentBenefitV2.Family.INVALIDITY_PENSION), result.observedFamilies)
    }

    @Test
    fun `article dont le KALITEXT parent est ambigu est ignoré`() {
        val id = "KALIARTI000000000106"
        val result = parse(
            articles = listOf(
                article(
                    id,
                    "Cadres coefficient 910. Sans condition d'ancienneté. Le capital décès est égal à 100 % du salaire annuel de référence."
                )
            ),
            ambiguous = setOf(id)
        )

        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `deux familles explicites du même KALITEXT restent deux preuves indépendantes`() {
        val death = article(
            "KALIARTI000000000107",
            "Cadres coefficient 910. Sans condition d'ancienneté. Le capital décès est égal à 100 % du salaire annuel de référence."
        )
        val incapacity = article(
            "KALIARTI000000000108",
            "Cadres coefficient 910. Sans condition d'ancienneté. En cas d'incapacité temporaire, des indemnités assurent 80 % du salaire mensuel de référence après une franchise de 30 jours."
        )
        val result = parse(listOf(death, incapacity))

        assertEquals(2, result.rules.size)
        assertEquals(
            setOf(
                ConventionProvidentBenefitV2.Family.DEATH_CAPITAL,
                ConventionProvidentBenefitV2.Family.INCAPACITY_INCOME_REPLACEMENT
            ),
            result.structuredFamilies
        )
    }

    @Test
    fun `IDCC différent bloque avant toute garantie`() {
        val result = parse(
            listOf(
                article(
                    "KALIARTI000000000109",
                    "Cadres coefficient 910. Sans condition d'ancienneté. Le capital décès est égal à 100 % du salaire annuel de référence."
                )
            ),
            idcc = "493"
        )

        assertTrue(result.rules.isEmpty())
        assertTrue(result.reasons.any { it.contains("IDCC") })
    }
}
