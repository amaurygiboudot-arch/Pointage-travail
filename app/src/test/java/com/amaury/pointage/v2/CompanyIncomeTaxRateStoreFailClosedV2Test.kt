package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.CompanyIncomeTaxRateResolverV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class CompanyIncomeTaxRateStoreFailClosedV2Test {
    private val period = YearMonth.of(2026, 9)

    @Test
    fun `json PAS partiellement invalide bloque le store`() {
        val decoded = CompanyIncomeTaxRateStoreV2.decode(
            """[
                {"id":"ok","ratePercent":3.2,"effectiveFrom":"2026-01","effectiveTo":null,"source":"Bulletin"},
                {"id":"bad","ratePercent":4.0,"effectiveFrom":"not-a-month","effectiveTo":null,"source":"Bulletin"}
            ]""".trimIndent()
        )

        assertFalse(decoded.reliable)
        assertEquals(1, decoded.records.size)
        assertTrue(decoded.warnings.isNotEmpty())
    }

    @Test
    fun `store PAS incoherent interdit la reutilisation du taux legacy`() {
        val blocked = CompanyIncomeTaxRateStoreV2.resolve(
            CompanyIncomeTaxRateStoreV2.ReadResult(
                records = emptyList(),
                reliable = false,
                warnings = listOf("store PAS incohérent")
            ),
            period
        )
        val legacy = CompanyIncomeTaxRateResolverV2.withLegacyFallback(blocked, 3.2)

        assertNull(legacy.rate)
        assertNull(legacy.ratePercent)
        assertTrue(legacy.hasDatedRecords)
        assertFalse(legacy.reliable)
        assertFalse(legacy.legacyUsed)
    }

    @Test
    fun `store PAS vide sain conserve la migration legacy non fiable`() {
        val clean = CompanyIncomeTaxRateStoreV2.resolve(
            CompanyIncomeTaxRateStoreV2.ReadResult(emptyList(), true, emptyList()),
            period
        )
        val legacy = CompanyIncomeTaxRateResolverV2.withLegacyFallback(clean, 3.2)

        assertEquals(0.032, legacy.rate!!, 0.000001)
        assertEquals(3.2, legacy.ratePercent!!, 0.000001)
        assertTrue(legacy.legacyUsed)
        assertFalse(legacy.reliable)
    }

    @Test
    fun `doublon identifiant PAS bloque le store`() {
        val decoded = CompanyIncomeTaxRateStoreV2.decode(
            """[
                {"id":"same","ratePercent":3.2,"effectiveFrom":"2026-01","effectiveTo":"2026-06","source":"Bulletin A"},
                {"id":"same","ratePercent":4.0,"effectiveFrom":"2026-07","effectiveTo":null,"source":"Bulletin B"}
            ]""".trimIndent()
        )

        assertFalse(decoded.reliable)
        assertEquals(2, decoded.records.size)
    }

    @Test
    fun `taux hors limites et source vide sont refuses au decodage`() {
        val excessive = CompanyIncomeTaxRateStoreV2.decode(
            """[{"id":"a","ratePercent":101.0,"effectiveFrom":"2026-01","effectiveTo":null,"source":"Bulletin"}]"""
        )
        val blankSource = CompanyIncomeTaxRateStoreV2.decode(
            """[{"id":"b","ratePercent":3.2,"effectiveFrom":"2026-01","effectiveTo":null,"source":"   "}]"""
        )

        assertFalse(excessive.reliable)
        assertTrue(excessive.records.isEmpty())
        assertFalse(blankSource.reliable)
        assertTrue(blankSource.records.isEmpty())
    }
}
