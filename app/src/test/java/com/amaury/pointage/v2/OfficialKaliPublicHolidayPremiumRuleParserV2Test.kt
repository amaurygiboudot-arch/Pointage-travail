package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class OfficialKaliPublicHolidayPremiumRuleParserV2Test {
    private val referenceDate = LocalDate.of(2026, 9, 30)
    private val articleId = "KALIARTI000012345680"

    private fun response(content: String): Map<String, Any> = mapOf(
        "etat" to "VIGUEUR",
        "dateDebutVersion" to "2026-01-01",
        "articles" to listOf(
            mapOf(
                "id" to articleId,
                "etat" to "VIGUEUR",
                "dateDebut" to "2026-01-01",
                "num" to "Jours fériés",
                "content" to content
            )
        )
    )

    private fun article(content: String) =
        OfficialKaliPublicHolidayPremiumRuleParserV2.parseApplicableArticle(
            response(content), articleId, referenceDate
        )!!

    @Test
    fun `majoration uniforme des jours feries devient calculable`() {
        val diagnostic = OfficialKaliPublicHolidayPremiumRuleParserV2.analyzeArticle(
            article("Les heures travaillées les jours fériés donnent lieu à une majoration de 100 %.")
        )

        assertEquals(
            OfficialKaliPublicHolidayPremiumRuleParserV2.DiagnosticKind.STRUCTURED_CANDIDATE,
            diagnostic.kind
        )
        assertNotNull(diagnostic.candidate)
        assertEquals(100.0, diagnostic.candidate!!.percentage, 0.0001)
        assertEquals(2.0, diagnostic.candidate!!.multiplier, 0.0001)
    }

    @Test
    fun `premier mai bloque le modele uniforme`() {
        val diagnostic = OfficialKaliPublicHolidayPremiumRuleParserV2.analyzeArticle(
            article("Les jours fériés sont majorés de 50 %, sauf le 1er mai qui suit un régime spécifique.")
        )

        assertEquals(
            OfficialKaliPublicHolidayPremiumRuleParserV2.DiagnosticKind.CONDITIONAL_RULE,
            diagnostic.kind
        )
        assertNull(diagnostic.candidate)
    }

    @Test
    fun `jour ferie nomme bloque le modele uniforme`() {
        val diagnostic = OfficialKaliPublicHolidayPremiumRuleParserV2.analyzeArticle(
            article("Les jours fériés sont majorés de 50 % ; le 25 décembre est traité séparément.")
        )

        assertEquals(
            OfficialKaliPublicHolidayPremiumRuleParserV2.DiagnosticKind.CONDITIONAL_RULE,
            diagnostic.kind
        )
        assertNull(diagnostic.candidate)
    }

    @Test
    fun `clause de non cumul reste conditionnelle`() {
        val diagnostic = OfficialKaliPublicHolidayPremiumRuleParserV2.analyzeArticle(
            article("Les jours fériés donnent lieu à une majoration de 100 %, non cumulable avec la majoration de nuit.")
        )

        assertEquals(
            OfficialKaliPublicHolidayPremiumRuleParserV2.DiagnosticKind.CONDITIONAL_RULE,
            diagnostic.kind
        )
        assertNull(diagnostic.candidate)
    }

    @Test
    fun `plusieurs taux interdisent le choix automatique`() {
        val diagnostic = OfficialKaliPublicHolidayPremiumRuleParserV2.analyzeArticle(
            article("Les jours fériés sont majorés de 50 % le jour et de 100 % la nuit.")
        )

        assertEquals(
            OfficialKaliPublicHolidayPremiumRuleParserV2.DiagnosticKind.MULTIPLE_RATES,
            diagnostic.kind
        )
        assertNull(diagnostic.candidate)
    }

    @Test
    fun `un pourcentage sans contexte de majoration ne devient pas une prime`() {
        val diagnostic = OfficialKaliPublicHolidayPremiumRuleParserV2.analyzeArticle(
            article("Le maintien de salaire des jours fériés est fixé à 100 % de la rémunération habituelle.")
        )

        assertEquals(
            OfficialKaliPublicHolidayPremiumRuleParserV2.DiagnosticKind.RATE_WITHOUT_SIMPLE_PREMIUM_CONTEXT,
            diagnostic.kind
        )
        assertNull(diagnostic.candidate)
    }
}
