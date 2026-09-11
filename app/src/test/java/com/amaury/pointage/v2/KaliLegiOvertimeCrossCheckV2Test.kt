package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class KaliLegiOvertimeCrossCheckV2Test {
    private val referenceDate = LocalDate.of(2026, 9, 1)

    private fun diagnostic(
        articleId: String = "KALIARTI000001",
        percentages: List<Double> = listOf(25.0, 50.0),
        thresholds: List<Int> = emptyList(),
        referencesCurrentLaw: Boolean = true
    ) = OfficialKaliOvertimeRuleParserV2.ArticleDiagnostic(
        article = OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
            articleId = articleId,
            status = "VIGUEUR",
            content = "Taux d'heures supplémentaires.",
            effectiveFrom = LocalDate.of(2020, 1, 1),
            effectiveTo = null,
            title = "Article test"
        ),
        kind = OfficialKaliOvertimeRuleParserV2.DiagnosticKind.EXPLICIT_RATES_WITHOUT_35H,
        percentages = percentages,
        hourThresholds = thresholds,
        referencesCurrentLaw = referencesCurrentLaw
    )

    private fun legalRecord(): LegalPayrollSourceStoreV2.Record {
        val zone = ZoneId.systemDefault()
        return LegalPayrollSourceStoreV2.Record(
            topic = OfficialLegalCodeSourceV2.Topic.OVERTIME,
            articleId = "LEGIARTI-L3121-36",
            articleNumber = "L3121-36",
            status = "VIGUEUR",
            excerpt = "Les huit premières heures supplémentaires donnent lieu à une majoration de 25 %. Les heures suivantes donnent lieu à une majoration de 50 %.",
            effectiveFromMs = LocalDate.of(2020, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli(),
            effectiveToMs = null,
            referenceAtMs = referenceDate.atStartOfDay(zone).toInstant().toEpochMilli(),
            checkedAtMs = referenceDate.atStartOfDay(zone).toInstant().toEpochMilli()
        )
    }

    private fun snapshot(
        records: List<LegalPayrollSourceStoreV2.Record>,
        reliable: Boolean,
        warnings: List<String> = emptyList()
    ): LegalPayrollSourceStoreV2.Snapshot {
        val covered = records.map { it.topic }.toSet()
        val allTopics = OfficialLegalCodeSourceV2.Topic.entries.toSet()
        return LegalPayrollSourceStoreV2.Snapshot(
            referenceAtMs = referenceDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            records = records,
            coveredTopics = covered,
            missingTopics = allTopics - covered,
            reliable = reliable,
            warnings = warnings
        )
    }

    @Test
    fun `stockage LEGI non fiable ignore meme un ancien record exploitable`() {
        val result = KaliLegiOvertimeCrossCheckV2.analyzeSnapshot(
            snapshot = snapshot(
                records = listOf(legalRecord()),
                reliable = false,
                warnings = listOf("Sources légales LEGI : stockage local incohérent.")
            ),
            referenceDate = referenceDate,
            diagnostics = listOf(diagnostic())
        )

        assertFalse(result.statutoryAvailable)
        assertTrue(result.matchingCurrentLawArticleIds.isEmpty())
        assertTrue(result.warnings.any { it.contains("stockage local incohérent") })
        assertTrue(result.warnings.any { it.contains("aucun barème supplétif ni absence") })
    }

    @Test
    fun `stockage LEGI fiable mais vide conserve le diagnostic absence de bareme verifie`() {
        val result = KaliLegiOvertimeCrossCheckV2.analyzeSnapshot(
            snapshot = snapshot(emptyList(), reliable = true),
            referenceDate = referenceDate,
            diagnostics = listOf(diagnostic())
        )

        assertFalse(result.statutoryAvailable)
        assertTrue(result.warnings.any { it.contains("aucun barème supplétif LEGI vérifié") })
        assertFalse(result.warnings.any { it.contains("stockage LEGI local non fiable") })
    }

    @Test
    fun `snapshot LEGI fiable recoupe les memes taux sans inventer de bareme KALI`() {
        val result = KaliLegiOvertimeCrossCheckV2.analyzeSnapshot(
            snapshot = snapshot(listOf(legalRecord()), reliable = true),
            referenceDate = referenceDate,
            diagnostics = listOf(diagnostic())
        )

        assertTrue(result.statutoryAvailable)
        assertEquals("LEGIARTI-L3121-36", result.statutoryArticleId)
        assertEquals(listOf("KALIARTI000001"), result.matchingCurrentLawArticleIds)
        assertTrue(result.warnings.any { it.contains("preuves recoupées, pas un barème KALI autonome") })
    }

    @Test
    fun `sans taux KALI explicites le stockage LEGI nest pas interprete`() {
        val nonRateDiagnostic = diagnostic().copy(
            kind = OfficialKaliOvertimeRuleParserV2.DiagnosticKind.LEGAL_REFERENCE_ONLY,
            percentages = emptyList()
        )
        val result = KaliLegiOvertimeCrossCheckV2.analyzeSnapshot(
            snapshot = snapshot(
                records = listOf(legalRecord()),
                reliable = false,
                warnings = listOf("corruption")
            ),
            referenceDate = referenceDate,
            diagnostics = listOf(nonRateDiagnostic)
        )

        assertFalse(result.statutoryAvailable)
        assertTrue(result.warnings.isEmpty())
    }
}
