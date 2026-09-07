package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialKaliNightRuleParserV2Test {
    private val referenceDate = LocalDate.of(2026, 9, 30)
    private val articleId = "KALIARTI000012345678"

    private fun response(
        content: String,
        from: String = "2026-01-01",
        status: String = "VIGUEUR"
    ): Map<String, Any> = mapOf(
        "etat" to status,
        "dateDebutVersion" to from,
        "articles" to listOf(
            mapOf(
                "id" to articleId,
                "etat" to status,
                "dateDebut" to from,
                "num" to "Travail de nuit",
                "content" to content
            )
        )
    )

    @Test
    fun `structure une plage et un taux uniques et rend le candidat calculable`() {
        val article = OfficialKaliNightRuleParserV2.parseApplicableArticle(
            response("Les heures de nuit effectuées de 21 h à 6 h donnent lieu à une majoration de 25 %."),
            articleId,
            referenceDate
        )
        assertNotNull(article)

        val diagnostic = OfficialKaliNightRuleParserV2.analyzeArticle(article!!)
        assertEquals(OfficialKaliNightRuleParserV2.DiagnosticKind.STRUCTURED_CANDIDATE, diagnostic.kind)
        val candidate = diagnostic.candidate
        assertNotNull(candidate)
        assertEquals(21 * 60, candidate!!.window.startMinute)
        assertEquals(6 * 60, candidate.window.endMinute)
        assertEquals(25.0, candidate.percentage, 0.0001)
        assertEquals(1.25, candidate.multiplier, 0.0001)
        assertTrue(candidate.calculationReady)
    }

    @Test
    fun `refuse de choisir entre plusieurs taux de nuit`() {
        val article = OfficialKaliNightRuleParserV2.parseApplicableArticle(
            response(
                "Les heures de nuit de 21 h à 6 h sont majorées de 20 % en semaine et de 30 % le dimanche."
            ),
            articleId,
            referenceDate
        )!!

        val diagnostic = OfficialKaliNightRuleParserV2.analyzeArticle(article)
        assertEquals(OfficialKaliNightRuleParserV2.DiagnosticKind.MULTIPLE_RATES, diagnostic.kind)
        assertEquals(listOf(20.0, 30.0), diagnostic.percentages)
        assertNull(diagnostic.candidate)
    }

    @Test
    fun `une qualification de travailleur de nuit reste conditionnelle`() {
        val article = OfficialKaliNightRuleParserV2.parseApplicableArticle(
            response(
                "Les travailleurs de nuit accomplissant au moins 6 heures de travail entre 21 h et 6 h bénéficient d'une majoration de 25 %."
            ),
            articleId,
            referenceDate
        )!!

        val diagnostic = OfficialKaliNightRuleParserV2.analyzeArticle(article)
        assertEquals(OfficialKaliNightRuleParserV2.DiagnosticKind.CONDITIONAL_RULE, diagnostic.kind)
        assertNull(diagnostic.candidate)
    }

    @Test
    fun `un taux sans plage horaire explicite reste a verifier`() {
        val article = OfficialKaliNightRuleParserV2.parseApplicableArticle(
            response("Toute heure de nuit ouvre droit à une majoration de 15 %."),
            articleId,
            referenceDate
        )!!

        val diagnostic = OfficialKaliNightRuleParserV2.analyzeArticle(article)
        assertEquals(OfficialKaliNightRuleParserV2.DiagnosticKind.RATE_WITHOUT_WINDOW, diagnostic.kind)
        assertEquals(listOf(15.0), diagnostic.percentages)
        assertNull(diagnostic.candidate)
    }

    @Test
    fun `une version future ou abrogee est rejetee avant analyse`() {
        assertNull(
            OfficialKaliNightRuleParserV2.parseApplicableArticle(
                response("Les heures de nuit de 21 h à 6 h sont majorées de 25 %.", from = "2027-01-01"),
                articleId,
                referenceDate
            )
        )
        assertNull(
            OfficialKaliNightRuleParserV2.parseApplicableArticle(
                response("Les heures de nuit de 21 h à 6 h sont majorées de 25 %.", status = "ABROGE"),
                articleId,
                referenceDate
            )
        )
    }
}
