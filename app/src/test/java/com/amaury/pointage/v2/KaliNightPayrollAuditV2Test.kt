package com.amaury.pointage.v2

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class KaliNightPayrollAuditV2Test {
    private val article = OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
        articleId = "KALIARTI000012345678",
        status = "VIGUEUR",
        content = "Les heures de nuit de 21 h à 6 h sont majorées de 25 %.",
        effectiveFrom = LocalDate.of(2026, 1, 1),
        effectiveTo = null,
        title = "Travail de nuit"
    )

    @Test
    fun `le diagnostic rappelle qu un candidat structure reste non applique`() {
        val candidate = OfficialKaliNightRuleParserV2.StructuredCandidate(
            article = article,
            window = OfficialKaliNightRuleParserV2.NightWindow(21 * 60, 6 * 60),
            percentage = 25.0
        )
        val diagnostic = OfficialKaliNightRuleParserV2.ArticleDiagnostic(
            article = article,
            kind = OfficialKaliNightRuleParserV2.DiagnosticKind.STRUCTURED_CANDIDATE,
            candidate = candidate,
            percentages = listOf(25.0),
            windows = listOf(candidate.window)
        )

        val warnings = KaliNightPayrollAuditV2.diagnosticWarnings(listOf(diagnostic))
        assertTrue(warnings.any { it.contains("KALIARTI000012345678") })
        assertTrue(warnings.any { it.contains("21:00-06:00") })
        assertTrue(warnings.any { it.contains("+25 %") })
    }

    @Test
    fun `un taux sans plage ne peut pas reutiliser la plage de poste de l application`() {
        val diagnostic = OfficialKaliNightRuleParserV2.ArticleDiagnostic(
            article = article,
            kind = OfficialKaliNightRuleParserV2.DiagnosticKind.RATE_WITHOUT_WINDOW,
            percentages = listOf(25.0)
        )

        val warnings = KaliNightPayrollAuditV2.diagnosticWarnings(listOf(diagnostic))
        assertTrue(warnings.any { it.contains("refuse d'utiliser la plage de poste") })
    }
}
