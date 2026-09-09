package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class RgduObservedAdvanceStoreIntegrityV2Test {
    @Test
    fun `decoder preserves explicit zero amount`() {
        val result = CompanyEmployerGeneralReductionObservedAdvanceStoreV2.decode(
            """[{"id":"jan","month":"2026-01","amount":0.0,"source":"DSN janvier"}]"""
        )

        assertTrue(result.reliable)
        assertEquals(1, result.records.size)
        assertEquals(YearMonth.of(2026, 1), result.records.single().month)
        assertEquals(0.0, result.records.single().amount, 0.0)
        assertEquals("DSN janvier", result.records.single().source)
    }

    @Test
    fun `fractional euro amount is preserved`() {
        val result = CompanyEmployerGeneralReductionObservedAdvanceStoreV2.decode(
            """[{"id":"jan","month":"2026-01","amount":123.47,"source":"bulletin"}]"""
        )

        assertTrue(result.reliable)
        assertEquals(123.47, result.records.single().amount, 0.0)
    }

    @Test
    fun `negative amount blocks store reliability`() {
        val result = CompanyEmployerGeneralReductionObservedAdvanceStoreV2.decode(
            """[{"id":"jan","month":"2026-01","amount":-0.01,"source":"DSN"}]"""
        )

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun `string amount blocks store reliability`() {
        val result = CompanyEmployerGeneralReductionObservedAdvanceStoreV2.decode(
            """[{"id":"jan","month":"2026-01","amount":"123.47","source":"DSN"}]"""
        )

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun `blank source blocks store reliability`() {
        val result = CompanyEmployerGeneralReductionObservedAdvanceStoreV2.decode(
            """[{"id":"jan","month":"2026-01","amount":123.47,"source":""}]"""
        )

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun `invalid month blocks store reliability`() {
        val result = CompanyEmployerGeneralReductionObservedAdvanceStoreV2.decode(
            """[{"id":"jan","month":"2026-13","amount":123.47,"source":"DSN"}]"""
        )

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun `missing amount blocks store reliability`() {
        val result = CompanyEmployerGeneralReductionObservedAdvanceStoreV2.decode(
            """[{"id":"jan","month":"2026-01","source":"DSN"}]"""
        )

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun `one malformed item blocks whole store while preserving decodable records for diagnosis`() {
        val result = CompanyEmployerGeneralReductionObservedAdvanceStoreV2.decode(
            """[
                {"id":"jan","month":"2026-01","amount":100.0,"source":"DSN"},
                {"id":"broken","month":"2026-02","amount":-1.0,"source":"DSN"}
            ]""".trimIndent()
        )

        assertFalse(result.reliable)
        assertEquals(1, result.records.size)
        assertTrue(result.warnings.any { it.contains("stockage local incohérent", ignoreCase = true) })
    }

    @Test
    fun `invalid root json blocks store`() {
        val result = CompanyEmployerGeneralReductionObservedAdvanceStoreV2.decode("not-json")

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
    }
}
