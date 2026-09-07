package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.WeekdayPremiumKindV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class OfficialKaliWeekdayPremiumRuleParserV2Test {
    private val referenceDate = LocalDate.of(2026, 9, 30)
    private val articleId = "KALIARTI000012345679"

    private fun response(content: String): Map<String, Any> = mapOf(
        "etat" to "VIGUEUR",
        "dateDebutVersion" to "2026-01-01",
        "articles" to listOf(
            mapOf(
                "id" to articleId,
                "etat" to "VIGUEUR",
                "dateDebut" to "2026-01-01",
                "num" to "Majoration",
                "content" to content
            )
        )
    )

    @Test
    fun `samedi simple devient candidat calculable`() {
        val article = OfficialKaliWeekdayPremiumRuleParserV2.parseApplicableArticle(
            response("Les heures travaillées le samedi donnent lieu à une majoration de 25 %."),
            articleId,
            referenceDate
        )!!
        val diagnostic = OfficialKaliWeekdayPremiumRuleParserV2.analyzeArticle(
            article,
            WeekdayPremiumKindV2.SATURDAY
        )
        assertEquals(OfficialKaliWeekdayPremiumRuleParserV2.DiagnosticKind.STRUCTURED_CANDIDATE, diagnostic.kind)
        assertNotNull(diagnostic.candidate)
        assertEquals(25.0, diagnostic.candidate!!.percentage, 0.0001)
        assertEquals(1.25, diagnostic.candidate!!.multiplier, 0.0001)
    }

    @Test
    fun `dimanche simple devient candidat calculable`() {
        val article = OfficialKaliWeekdayPremiumRuleParserV2.parseApplicableArticle(
            response("Toute heure effectuée le dimanche est majorée de 100 %."),
            articleId,
            referenceDate
        )!!
        val diagnostic = OfficialKaliWeekdayPremiumRuleParserV2.analyzeArticle(
            article,
            WeekdayPremiumKindV2.SUNDAY
        )
        assertEquals(OfficialKaliWeekdayPremiumRuleParserV2.DiagnosticKind.STRUCTURED_CANDIDATE, diagnostic.kind)
        assertEquals(100.0, diagnostic.candidate!!.percentage, 0.0001)
    }

    @Test
    fun `plage horaire sur samedi reste conditionnelle`() {
        val article = OfficialKaliWeekdayPremiumRuleParserV2.parseApplicableArticle(
            response("Le samedi de 18 h à 23 h, les heures sont majorées de 25 %."),
            articleId,
            referenceDate
        )!!
        val diagnostic = OfficialKaliWeekdayPremiumRuleParserV2.analyzeArticle(
            article,
            WeekdayPremiumKindV2.SATURDAY
        )
        assertEquals(OfficialKaliWeekdayPremiumRuleParserV2.DiagnosticKind.CONDITIONAL_RULE, diagnostic.kind)
        assertNull(diagnostic.candidate)
    }

    @Test
    fun `dimanche et jour ferie ne sont jamais confondus`() {
        val article = OfficialKaliWeekdayPremiumRuleParserV2.parseApplicableArticle(
            response("Le dimanche et les jours fériés donnent lieu à une majoration de 100 %."),
            articleId,
            referenceDate
        )!!
        val diagnostic = OfficialKaliWeekdayPremiumRuleParserV2.analyzeArticle(
            article,
            WeekdayPremiumKindV2.SUNDAY
        )
        assertEquals(OfficialKaliWeekdayPremiumRuleParserV2.DiagnosticKind.CONDITIONAL_RULE, diagnostic.kind)
        assertNull(diagnostic.candidate)
    }

    @Test
    fun `clause de cumul sur dimanche reste conditionnelle`() {
        val article = OfficialKaliWeekdayPremiumRuleParserV2.parseApplicableArticle(
            response("Le dimanche est majoré de 100 %. Cette majoration n'est pas cumulable avec les heures supplémentaires."),
            articleId,
            referenceDate
        )!!
        val diagnostic = OfficialKaliWeekdayPremiumRuleParserV2.analyzeArticle(
            article,
            WeekdayPremiumKindV2.SUNDAY
        )
        assertEquals(OfficialKaliWeekdayPremiumRuleParserV2.DiagnosticKind.CONDITIONAL_RULE, diagnostic.kind)
        assertNull(diagnostic.candidate)
    }

    @Test
    fun `plusieurs taux bloquent le choix automatique`() {
        val article = OfficialKaliWeekdayPremiumRuleParserV2.parseApplicableArticle(
            response("Le dimanche est majoré de 50 % le matin et de 100 % le soir."),
            articleId,
            referenceDate
        )!!
        val diagnostic = OfficialKaliWeekdayPremiumRuleParserV2.analyzeArticle(
            article,
            WeekdayPremiumKindV2.SUNDAY
        )
        assertEquals(OfficialKaliWeekdayPremiumRuleParserV2.DiagnosticKind.MULTIPLE_RATES, diagnostic.kind)
        assertNull(diagnostic.candidate)
    }
}
