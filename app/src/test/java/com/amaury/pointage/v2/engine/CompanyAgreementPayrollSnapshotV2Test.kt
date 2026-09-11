package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.CompanyAgreementRuleExtractorV2
import com.amaury.pointage.v2.CompanyAgreementRuleStoreV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CompanyAgreementPayrollSnapshotV2Test {
    private val period = PayrollPeriodV2.month(2026, 8)

    @Test
    fun `snapshot ACCO non fiable ne produit aucun segment meme avec une regle lisible`() {
        val stored = CompanyAgreementRuleStoreV2.ReadResult(
            records = listOf(candidate(effectiveFrom = "15/09/2026")),
            reliable = false,
            warnings = listOf("stockage ACCO corrompu")
        )

        val segments = CompanyAgreementPayrollSegmentsV2.load(stored, period)
        val bridge = CompanyAgreementPayrollBridgeV2.load(
            storedRules = stored,
            referenceDate = period.referenceDate,
            period = period
        )

        assertTrue(segments.isEmpty())
        assertFalse(bridge.reliable)
        assertTrue(bridge.periodSegments.isEmpty())
        assertTrue(bridge.applicableRules.isEmpty())
        assertTrue(bridge.warnings.any { it.contains("corrompu") })
    }

    @Test
    fun `meme snapshot fiable decoupe la periode et resout les regles de chaque segment`() {
        val stored = CompanyAgreementRuleStoreV2.ReadResult(
            records = listOf(candidate(effectiveFrom = "15/09/2026")),
            reliable = true,
            warnings = emptyList()
        )

        val segments = CompanyAgreementPayrollSegmentsV2.load(stored, period)
        val bridge = CompanyAgreementPayrollBridgeV2.load(
            storedRules = stored,
            referenceDate = period.referenceDate,
            period = period
        )

        assertEquals(2, segments.size)
        assertEquals(LocalDate.of(2026, 9, 1), segments[0].start)
        assertEquals(LocalDate.of(2026, 9, 14), segments[0].endInclusive)
        assertTrue(segments[0].applicableRules.isEmpty())
        assertEquals(LocalDate.of(2026, 9, 15), segments[1].start)
        assertEquals(LocalDate.of(2026, 9, 30), segments[1].endInclusive)
        assertEquals(1, segments[1].applicableRules.size)
        assertTrue(bridge.reliable)
        assertEquals(segments, bridge.periodSegments)
    }

    @Test
    fun `regle verifiee incomplete bloque aussi la segmentation sur un store lisible`() {
        val stored = CompanyAgreementRuleStoreV2.ReadResult(
            records = listOf(candidate(effectiveFrom = null)),
            reliable = true,
            warnings = emptyList()
        )

        val segments = CompanyAgreementPayrollSegmentsV2.load(stored, period)
        val bridge = CompanyAgreementPayrollBridgeV2.load(
            storedRules = stored,
            referenceDate = period.referenceDate,
            period = period
        )

        assertTrue(segments.isEmpty())
        assertFalse(bridge.reliable)
        assertTrue(bridge.periodSegments.isEmpty())
    }

    private fun candidate(effectiveFrom: String?) = CompanyAgreementRuleStoreV2.StoredCandidate(
        agreementId = "ACCO-SNAPSHOT-1",
        category = CompanyAgreementRuleExtractorV2.Category.OVERTIME,
        excerpt = "Les heures supplémentaires sont majorées de 25 %.",
        confidence = 0.95,
        verified = true,
        effectiveFrom = effectiveFrom,
        effectiveTo = null,
        scope = "Tous les salariés",
        calculationValueVerified = false
    )
}
