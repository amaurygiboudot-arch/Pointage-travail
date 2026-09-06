package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate

class OfficialKaliOvertimeRuleParserV2Test {
    private val referenceDate = LocalDate.of(2026, 9, 30)
    private val articleId = "KALIARTI000012345678"

    private fun response(content: String, from: String = "2026-01-01", status: String = "VIGUEUR"): Map<String, Any> = mapOf(
        "etat" to status,
        "dateDebutVersion" to from,
        "articles" to listOf(
            mapOf(
                "id" to articleId,
                "etat" to status,
                "dateDebut" to from,
                "num" to "Heures supplémentaires",
                "content" to content
            )
        )
    )

    @Test
    fun `structure deux tranches seulement si le seuil 35h et les taux sont explicites`() {
        val data = response(
            "Au-delà de 35 heures, les huit premières heures supplémentaires, de la 36e à la 43e heure, " +
                "sont majorées de 25 %. Les heures suivantes sont majorées de 50 %."
        )
        val article = OfficialKaliOvertimeRuleParserV2.parseApplicableArticle(data, articleId, referenceDate)
        assertNotNull(article)

        val schedule = OfficialKaliOvertimeRuleParserV2.parseCompleteSchedule(article!!)
        assertNotNull(schedule)
        assertEquals(35 * 60, schedule!!.weeklyRegularMinutes)
        assertEquals(listOf(1.25, 1.50), schedule.tiers.map { it.multiplier })
        assertEquals(43 * 60, schedule.tiers.first().toMinutes)
        assertNull(schedule.tiers.last().toMinutes)
    }

    @Test
    fun `refuse de supposer le seuil legal quand 35h n est pas ecrit`() {
        val data = response(
            "Les huit premières heures supplémentaires sont majorées de 25 %. Les heures suivantes sont majorées de 50 %."
        )
        val article = OfficialKaliOvertimeRuleParserV2.parseApplicableArticle(data, articleId, referenceDate)!!
        assertNull(OfficialKaliOvertimeRuleParserV2.parseCompleteSchedule(article))
    }

    @Test
    fun `accepte un taux unique explicite pour toutes les heures au dela de 35h`() {
        val data = response(
            "Au-delà de 35 heures, toutes les heures supplémentaires donnent lieu à une majoration de 10 %."
        )
        val article = OfficialKaliOvertimeRuleParserV2.parseApplicableArticle(data, articleId, referenceDate)!!
        val schedule = OfficialKaliOvertimeRuleParserV2.parseCompleteSchedule(article)!!
        assertEquals(1, schedule.tiers.size)
        assertEquals(1.10, schedule.tiers.single().multiplier, 0.0001)
        assertNull(schedule.tiers.single().toMinutes)
    }

    @Test
    fun `classe un ancien seuil avec taux sans le convertir en bareme 35h`() {
        val data = response(
            "Dans le cadre de la législation en vigueur, les heures supplémentaires effectuées au-delà de 40 heures " +
                "donnent lieu à une majoration de 25 % pour les 8 premières heures supplémentaires et de 50 % au-delà."
        )
        val article = OfficialKaliOvertimeRuleParserV2.parseApplicableArticle(data, articleId, referenceDate)!!
        val diagnostic = OfficialKaliOvertimeRuleParserV2.analyzeArticle(article)

        assertNull(diagnostic.schedule)
        assertEquals(
            OfficialKaliOvertimeRuleParserV2.DiagnosticKind.EXPLICIT_RATES_WITHOUT_35H,
            diagnostic.kind
        )
        assertEquals(listOf(25.0, 50.0), diagnostic.percentages)
        assertEquals(listOf(40), diagnostic.hourThresholds)
    }

    @Test
    fun `classe un renvoi a la loi sans inventer de taux`() {
        val data = response(
            "Les heures supplémentaires sont décomptées au-delà de la durée légale de travail et traitées selon les dispositions légales et réglementaires en vigueur."
        )
        val article = OfficialKaliOvertimeRuleParserV2.parseApplicableArticle(data, articleId, referenceDate)!!
        val diagnostic = OfficialKaliOvertimeRuleParserV2.analyzeArticle(article)

        assertNull(diagnostic.schedule)
        assertEquals(
            OfficialKaliOvertimeRuleParserV2.DiagnosticKind.LEGAL_REFERENCE_ONLY,
            diagnostic.kind
        )
    }

    @Test
    fun `rejette une version future ou abrogee`() {
        assertNull(
            OfficialKaliOvertimeRuleParserV2.parseApplicableArticle(
                response("Au-delà de 35 heures, toutes les heures supplémentaires sont majorées de 20 %.", from = "2027-01-01"),
                articleId,
                referenceDate
            )
        )
        assertNull(
            OfficialKaliOvertimeRuleParserV2.parseApplicableArticle(
                response("Au-delà de 35 heures, toutes les heures supplémentaires sont majorées de 20 %.", status = "ABROGE"),
                articleId,
                referenceDate
            )
        )
    }
}
