package com.amaury.pointage.v2.engine

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyIncomeTaxRateResolverV2Test {
    @Test
    fun `taux date applicable est converti en decimal`() {
        val result = CompanyIncomeTaxRateResolverV2.resolve(
            records = listOf(
                CompanyIncomeTaxRateResolverV2.Record(
                    id = "pas_1",
                    ratePercent = 3.2,
                    effectiveFrom = YearMonth.of(2026, 9),
                    source = "Bulletin 09/2026"
                )
            ),
            period = YearMonth.of(2026, 10)
        )

        assertEquals(0.032, result.rate!!, 0.000001)
        assertEquals(3.2, result.ratePercent!!, 0.000001)
        assertEquals("Bulletin 09/2026", result.source)
        assertTrue(result.reliable)
    }

    @Test
    fun `zero explicite est un taux confirme valide`() {
        val result = CompanyIncomeTaxRateResolverV2.resolve(
            records = listOf(
                CompanyIncomeTaxRateResolverV2.Record(
                    id = "pas_zero",
                    ratePercent = 0.0,
                    effectiveFrom = YearMonth.of(2026, 9),
                    effectiveTo = YearMonth.of(2026, 12),
                    source = "Bulletin officiel"
                )
            ),
            period = YearMonth.of(2026, 10)
        )

        assertEquals(0.0, result.rate!!, 0.0)
        assertTrue(result.reliable)
    }

    @Test
    fun `chevauchement bloque le calcul apres impot`() {
        val result = CompanyIncomeTaxRateResolverV2.resolve(
            records = listOf(
                CompanyIncomeTaxRateResolverV2.Record(
                    id = "pas_a",
                    ratePercent = 2.0,
                    effectiveFrom = YearMonth.of(2026, 1),
                    effectiveTo = YearMonth.of(2026, 12),
                    source = "Bulletin A"
                ),
                CompanyIncomeTaxRateResolverV2.Record(
                    id = "pas_b",
                    ratePercent = 4.0,
                    effectiveFrom = YearMonth.of(2026, 9),
                    source = "Bulletin B"
                )
            ),
            period = YearMonth.of(2026, 10)
        )

        assertNull(result.rate)
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("chevauchent") })
    }

    @Test
    fun `trou de periode ne ressuscite pas ancien taux`() {
        val dated = CompanyIncomeTaxRateResolverV2.resolve(
            records = listOf(
                CompanyIncomeTaxRateResolverV2.Record(
                    id = "pas_2025",
                    ratePercent = 1.0,
                    effectiveFrom = YearMonth.of(2025, 1),
                    effectiveTo = YearMonth.of(2025, 12),
                    source = "Bulletin 2025"
                )
            ),
            period = YearMonth.of(2026, 9)
        )
        val result = CompanyIncomeTaxRateResolverV2.withLegacyFallback(dated, legacyRatePercent = 5.0)

        assertNull(result.rate)
        assertFalse(result.legacyUsed)
        assertFalse(result.reliable)
    }

    @Test
    fun `ancien taux sans periode ne participe plus au calcul V2`() {
        val empty = CompanyIncomeTaxRateResolverV2.resolve(emptyList(), YearMonth.of(2026, 9))
        val result = CompanyIncomeTaxRateResolverV2.withLegacyFallback(empty, legacyRatePercent = 3.2)

        assertNull(result.rate)
        assertNull(result.ratePercent)
        assertNull(result.source)
        assertFalse(result.legacyUsed)
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("non utilisé", ignoreCase = true) })
    }

    @Test
    fun `source absente bloque un taux date`() {
        val result = CompanyIncomeTaxRateResolverV2.resolve(
            records = listOf(
                CompanyIncomeTaxRateResolverV2.Record(
                    id = "pas_no_source",
                    ratePercent = 3.2,
                    effectiveFrom = YearMonth.of(2026, 9),
                    source = ""
                )
            ),
            period = YearMonth.of(2026, 9)
        )

        assertNull(result.rate)
        assertFalse(result.reliable)
    }
}
