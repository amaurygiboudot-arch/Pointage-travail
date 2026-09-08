package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProvidentContributionV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProvidentFinancingAllocationV2Test {
    private val date = LocalDate.of(2026, 1, 31)
    private val scope = "KALITEXT000000000292"
    private val classification = ConventionClassificationV2(coefficient = 700)
    private val category = ProtectionCategoryV2.Result(
        aniCategory = ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2,
        confirmed = true,
        source = "test KALI + APEC"
    )
    private val profile = ConventionLegalProfileV2(
        companyId = "company",
        idcc = "292",
        siret = "",
        professionalStatus = "NON_CADRE",
        classification = classification,
        contractType = null,
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = null,
        weeklyHours = null,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private fun article(id: String, title: String, content: String) =
        OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
            articleId = id,
            status = "VIGUEUR_ETEN",
            content = content,
            effectiveFrom = LocalDate.of(2025, 1, 1),
            effectiveTo = null,
            title = title,
            extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
        )

    private fun baseArticles(financing: String) = listOf(
        article(
            "KALIARTI000000000291",
            "Bénéficiaires",
            "Le régime de prévoyance bénéficie aux salariés ne relevant pas des articles 2.1 et 2.2 après 3 mois d'ancienneté."
        ),
        article(
            "KALIARTI000000000292",
            "Assiette",
            "Le salaire de référence servant d'assiette est limité à 4 fois le plafond mensuel de la sécurité sociale."
        ),
        article("KALIARTI000000000293", "Financement", financing)
    )

    private fun parse(financing: String): OfficialKaliProvidentContributionParserV2.Diagnostic {
        val articles = baseArticles(financing)
        return OfficialKaliProvidentContributionParserV2.parse(
            profile = profile,
            protectionCategory = category,
            verifiedIdcc = "292",
            auditDate = date,
            articles = articles,
            articleTextIds = articles.associate { it.articleId to scope }
        )
    }

    @Test
    fun `minimum total employeur et repartition par defaut modifiable restent distincts`() {
        val result = parse(
            """
            Cotisation de prévoyance. Financement minimal : 0,80 %.
            Part patronale minimale : 0,40 %.
            À défaut d'accord d'entreprise, la répartition par défaut est : part salariale : 0,40 % ; part patronale : 0,40 %.
            Cette répartition peut être modifiée par accord d'entreprise.
            """.trimIndent()
        )

        assertNotNull(result.rule)
        val band = result.rule!!.tiers.single().bands.single()
        assertEquals(ConventionProvidentContributionV2.AllocationRule.DEFAULT_MODIFIABLE_BY_COMPANY_AGREEMENT, band.allocationRule)
        assertEquals(0.008, band.minimumTotalRate!!, 0.000001)
        assertEquals(0.004, band.minimumEmployerRate!!, 0.000001)
        assertEquals(0.004, band.employeeRate, 0.000001)
        assertEquals(0.004, band.employerRate, 0.000001)
        assertTrue(band.structurallyValid())
    }

    @Test
    fun `minima sans preuve de modification par accord entreprise ne deviennent jamais une repartition exacte`() {
        val result = parse(
            """
            Cotisation de prévoyance. Financement minimal : 0,80 %.
            Part patronale minimale : 0,40 %.
            Répartition par défaut : part salariale : 0,40 % ; part patronale : 0,40 %.
            """.trimIndent()
        )

        assertNull(result.rule)
    }

    @Test
    fun `moteur bloque la retenue salariale tant que la repartition entreprise reste inconnue`() {
        val parsed = parse(
            """
            Cotisation de prévoyance. Financement minimal : 0,80 %.
            Part patronale minimale : 0,40 %.
            À défaut d'accord d'entreprise, la répartition par défaut est : part salariale : 0,40 % ; part patronale : 0,40 %.
            Cette répartition peut être modifiée par accord d'entreprise.
            """.trimIndent()
        )
        val rule = parsed.rule!!

        val result = ConventionProvidentContributionV2.calculate(
            rules = listOf(rule),
            idcc = "292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "NON_CADRE",
            protectionCategory = category,
            seniorityMonths = 12,
            gross = 3000.0,
            applicableMonthlyCeiling = 4000.0
        )

        assertTrue(result.applicable)
        assertFalse(result.reliable)
        assertNull(result.employeeAmount)
        assertNull(result.employerAmount)
        assertTrue(result.warnings.any { it.contains("accord d'entreprise") })
    }

    @Test
    fun `repartition par defaut ne devient calculable qu apres confirmation entreprise explicite`() {
        val parsed = parse(
            """
            Cotisation de prévoyance. Financement minimal : 0,80 %.
            Part patronale minimale : 0,40 %.
            À défaut d'accord d'entreprise, la répartition par défaut est : part salariale : 0,40 % ; part patronale : 0,40 %.
            Cette répartition peut être modifiée par accord d'entreprise.
            """.trimIndent()
        )
        val rule = parsed.rule!!

        val result = ConventionProvidentContributionV2.calculate(
            rules = listOf(rule),
            idcc = "292",
            referenceDate = date,
            classification = classification,
            professionalStatus = "NON_CADRE",
            protectionCategory = category,
            seniorityMonths = 12,
            gross = 3000.0,
            applicableMonthlyCeiling = 4000.0,
            companyDefaultAllocationConfirmed = true
        )

        assertTrue(result.reliable)
        assertEquals(12.0, result.employeeAmount!!, 0.000001)
        assertEquals(12.0, result.employerAmount!!, 0.000001)
        assertEquals(0.008, result.lines.single().minimumTotalRate!!, 0.000001)
        assertEquals(0.004, result.lines.single().minimumEmployerRate!!, 0.000001)
    }

    @Test
    fun `default split inferieur au minimum total est structurellement refuse`() {
        val band = ConventionProvidentContributionV2.Band(
            label = "test",
            employeeRate = 0.002,
            employerRate = 0.004,
            minimumTotalRate = 0.008,
            minimumEmployerRate = 0.004,
            allocationRule = ConventionProvidentContributionV2.AllocationRule.DEFAULT_MODIFIABLE_BY_COMPANY_AGREEMENT
        )

        assertFalse(band.structurallyValid())
    }
}
