package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionSicknessMaintenanceV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialKaliSicknessMaintenanceParserV2Test {
    private val auditDate = LocalDate.of(2026, 9, 1)

    private fun profile(
        coefficient: Int = 800,
        status: String = "NON_CADRE",
        classification: ConventionClassificationV2 = ConventionClassificationV2(coefficient = coefficient)
    ) = ConventionLegalProfileV2(
        companyId = "c1",
        idcc = "9998",
        siret = "12345678901234",
        professionalStatus = status,
        classification = classification,
        contractType = "CDI",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = LocalDate.of(2020, 1, 1),
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private fun article(
        text: String = validText(),
        status: String = "VIGUEUR_ETEN",
        extension: LocalDate? = LocalDate.of(2024, 1, 1)
    ) = OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
        articleId = "KALIARTI000000000001",
        status = status,
        content = text,
        effectiveFrom = LocalDate.of(2023, 1, 1),
        effectiveTo = null,
        title = "Maintien de salaire en cas de maladie",
        extensionEffectiveFrom = extension
    )

    @Test
    fun `barème complet classifié et étendu est structuré`() {
        val diagnostic = OfficialKaliSicknessMaintenanceParserV2.parse(article(), profile(), auditDate)
        val rule = diagnostic.rule
        assertNotNull(rule)
        rule!!
        assertEquals(12, rule.minimumSeniorityMonths)
        assertEquals("NON_CADRE", rule.professionalStatus)
        assertEquals(800, rule.classification.coefficient)
        assertEquals(ConventionSicknessMaintenanceV2.ReferenceBasis.GROSS, rule.referenceBasis)
        assertEquals(ConventionSicknessMaintenanceV2.WaitingPolicy.FIXED_EACH_STOP, rule.waitingPolicy)
        assertEquals(3, rule.waitingDays)
        assertEquals(90, rule.tiers.single().annualLimitDays)
        assertEquals(60, rule.tiers.single().perStopLimitDays)
        assertEquals(ConventionSicknessMaintenanceV2.BandConsumptionScope.PER_STOP, rule.tiers.single().bandConsumptionScope)
        assertEquals(listOf(30 to 1.0, 30 to 0.75), rule.tiers.single().bands.map { it.calendarDays to it.targetRate })
        assertEquals(ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED, rule.extensionStatus)
        assertEquals(LocalDate.of(2024, 1, 1), rule.extensionEffectiveFrom)
    }

    @Test
    fun `date extension absente bloque applicabilité automatique`() {
        val diagnostic = OfficialKaliSicknessMaintenanceParserV2.parse(article(extension = null), profile(), auditDate)
        assertNotNull(diagnostic.rule)
        assertEquals(ConventionMinimumSalaryV2.ExtensionStatus.UNKNOWN, diagnostic.rule!!.extensionStatus)
        assertTrue(diagnostic.reasons.any { it.contains("date exacte d'extension") })
    }

    @Test
    fun `mauvais coefficient ne récupère pas la règle`() {
        val diagnostic = OfficialKaliSicknessMaintenanceParserV2.parse(article(), profile(coefficient = 900), auditDate)
        assertNull(diagnostic.rule)
    }

    @Test
    fun `mauvais statut ne récupère pas la règle`() {
        val diagnostic = OfficialKaliSicknessMaintenanceParserV2.parse(article(), profile(status = "CADRE"), auditDate)
        assertNull(diagnostic.rule)
    }

    @Test
    fun `article mélangeant maladie et accident du travail est refusé`() {
        val text = validText().replace(
            "En cas de maladie ou d'arrêt de travail dûment justifié,",
            "En cas de maladie ou d'accident du travail dûment justifié,"
        )
        val diagnostic = OfficialKaliSicknessMaintenanceParserV2.parse(article(text), profile(), auditDate)
        assertNull(diagnostic.rule)
        assertTrue(diagnostic.reasons.any { it.contains("autre motif médical") })
    }

    @Test
    fun `article non cadre sans classification ne peut jamais matcher un cadre`() {
        val diagnostic = OfficialKaliSicknessMaintenanceParserV2.parse(
            article(statusOnlyText("Non-cadres")),
            profile(status = "CADRE", classification = ConventionClassificationV2()),
            auditDate
        )
        assertNull(diagnostic.rule)
    }

    @Test
    fun `article non cadre sans classification peut matcher seulement un non cadre`() {
        val diagnostic = OfficialKaliSicknessMaintenanceParserV2.parse(
            article(statusOnlyText("Non-cadres")),
            profile(status = "NON_CADRE", classification = ConventionClassificationV2()),
            auditDate
        )
        assertNotNull(diagnostic.rule)
        assertTrue(diagnostic.rule!!.classification.isEmpty())
        assertEquals("NON_CADRE", diagnostic.rule!!.professionalStatus)
    }

    @Test
    fun `article cadre sans classification ne peut jamais matcher un non cadre`() {
        val diagnostic = OfficialKaliSicknessMaintenanceParserV2.parse(
            article(statusOnlyText("Cadres")),
            profile(status = "NON_CADRE", classification = ConventionClassificationV2()),
            auditDate
        )
        assertNull(diagnostic.rule)
    }

    @Test
    fun `portée des tranches absente bloque la règle`() {
        val text = validText().replace(
            "Pour chaque arrêt, les 30 premiers jours sont indemnisés à 100 % puis les 30 jours suivants à 75 %.",
            "Les 30 premiers jours sont indemnisés à 100 % puis les 30 jours suivants à 75 %."
        )
        val diagnostic = OfficialKaliSicknessMaintenanceParserV2.parse(article(text), profile(), auditDate)
        assertNull(diagnostic.rule)
    }

    @Test
    fun `plusieurs paliers ancienneté sont refusés tant qu ils ne sont pas reliés sans ambiguïté`() {
        val text = validText() + " Après 5 ans d'ancienneté, les durées sont augmentées."
        val diagnostic = OfficialKaliSicknessMaintenanceParserV2.parse(article(text), profile(), auditDate)
        assertNull(diagnostic.rule)
    }

    @Test
    fun `plafond annuel manquant bloque la règle`() {
        val text = validText().replace("Au cours d'une même année civile, le total ne peut excéder 90 jours.", "")
        val diagnostic = OfficialKaliSicknessMaintenanceParserV2.parse(article(text), profile(), auditDate)
        assertNull(diagnostic.rule)
    }

    @Test
    fun `plafond par arrêt manquant bloque la règle`() {
        val text = validText().replace("Pour chaque arrêt, le total ne peut excéder 60 jours.", "")
        val diagnostic = OfficialKaliSicknessMaintenanceParserV2.parse(article(text), profile(), auditDate)
        assertNull(diagnostic.rule)
    }

    @Test
    fun `carence ne peut pas être confondue avec plafond par arrêt`() {
        val text = validText().replace("Pour chaque arrêt, le total ne peut excéder 60 jours.", "")
        val diagnostic = OfficialKaliSicknessMaintenanceParserV2.parse(article(text), profile(), auditDate)
        assertNull(diagnostic.rule)
    }

    @Test
    fun `base de référence non explicite bloque la règle`() {
        val text = validText().replace("sur la base du salaire brut", "sur la base du salaire de référence")
        val diagnostic = OfficialKaliSicknessMaintenanceParserV2.parse(article(text), profile(), auditDate)
        assertNull(diagnostic.rule)
    }

    @Test
    fun `texte non étendu reste non étendu`() {
        val diagnostic = OfficialKaliSicknessMaintenanceParserV2.parse(
            article(status = "VIGUEUR_NON_ETEN", extension = null),
            profile(),
            auditDate
        )
        assertNotNull(diagnostic.rule)
        assertEquals(ConventionMinimumSalaryV2.ExtensionStatus.NOT_EXTENDED, diagnostic.rule!!.extensionStatus)
        assertTrue(diagnostic.reasons.any { it.contains("non étendu") })
    }

    private fun validText() = """
        Non-cadres - coefficient 800. En cas de maladie ou d'arrêt de travail dûment justifié,
        le salarié ayant au moins 12 mois d'ancienneté bénéficie d'un maintien de salaire
        sur la base du salaire brut. Pour chaque arrêt, un délai de carence de 3 jours est appliqué.
        Pour chaque arrêt, les 30 premiers jours sont indemnisés à 100 % puis les 30 jours suivants à 75 %.
        Pour chaque arrêt, le total ne peut excéder 60 jours.
        Au cours d'une même année civile, le total ne peut excéder 90 jours.
    """.trimIndent()

    private fun statusOnlyText(status: String) = """
        $status. En cas de maladie ou d'arrêt de travail dûment justifié,
        le salarié ayant au moins 12 mois d'ancienneté bénéficie d'un maintien de salaire
        sur la base du salaire brut. Pour chaque arrêt, un délai de carence de 3 jours est appliqué.
        Pour chaque arrêt, les 30 premiers jours sont indemnisés à 100 % puis les 30 jours suivants à 75 %.
        Pour chaque arrêt, le total ne peut excéder 60 jours.
        Au cours d'une même année civile, le total ne peut excéder 90 jours.
    """.trimIndent()
}