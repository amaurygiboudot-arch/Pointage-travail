package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayslipEngineV2Test {

    @Test
    fun `panier confirme identique reste conforme`() {
        val key = PayslipDocumentParserV2.KEY_MEAL_BASKETS

        val result = PayslipEngineV2.compare(
            expected = mapOf(key to 53.80),
            observed = mapOf(key to 53.80),
            tolerance = 0.02
        )

        assertTrue(result.conforming)
        assertTrue(result.discrepancies.isEmpty())
    }

    @Test
    fun `ecart panier est visible meme si le brut concorde`() {
        val gross = PayslipDocumentParserV2.KEY_GROSS
        val baskets = PayslipDocumentParserV2.KEY_MEAL_BASKETS

        val result = PayslipEngineV2.compare(
            expected = mapOf(gross to 2350.40, baskets to 53.80),
            observed = mapOf(gross to 2350.40, baskets to 48.42),
            tolerance = 0.02
        )

        assertFalse(result.conforming)
        assertEquals(1, result.discrepancies.size)
        assertEquals(baskets, result.discrepancies.single().category)
        assertEquals(53.80, result.discrepancies.single().expected!!, 0.001)
        assertEquals(48.42, result.discrepancies.single().observed!!, 0.001)
    }

    @Test
    fun `tolerance de deux centimes evite un faux ecart de comparaison`() {
        val key = PayslipDocumentParserV2.KEY_MEAL_BASKETS

        val result = PayslipEngineV2.compare(
            expected = mapOf(key to 53.80),
            observed = mapOf(key to 53.79),
            tolerance = 0.02
        )

        assertTrue(result.conforming)
    }

    @Test
    fun `ecart exact de deux centimes reste conforme mais trois centimes est signale`() {
        val key = PayslipDocumentParserV2.KEY_MEAL_BASKETS

        val twoCents = PayslipEngineV2.compare(
            expected = mapOf(key to 53.80),
            observed = mapOf(key to 53.78),
            tolerance = 0.02
        )
        val threeCents = PayslipEngineV2.compare(
            expected = mapOf(key to 53.80),
            observed = mapOf(key to 53.77),
            tolerance = 0.02
        )

        assertTrue(twoCents.conforming)
        assertFalse(threeCents.conforming)
    }

    @Test
    fun `tolerance invalide est refusee`() {
        val key = PayslipDocumentParserV2.KEY_GROSS

        assertTrue(
            runCatching {
                PayslipEngineV2.compare(mapOf(key to 1.0), mapOf(key to 1.0), -0.01)
            }.isFailure
        )
        assertTrue(
            runCatching {
                PayslipEngineV2.compare(mapOf(key to Double.NaN), mapOf(key to 1.0), 0.02)
            }.isFailure
        )
    }

    @Test fun `unrepresentable inputs never saturate into false conformity`() {
        val invalid = listOf(1e17, 1e308, Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NaN, -0.001,
            90_071_992_547_409.92)
        for (value in invalid) {
            assertTrue(runCatching { PayslipEngineV2.compare(mapOf("gross" to value), mapOf("gross" to value)) }.isFailure)
            assertTrue(runCatching { PayslipEngineV2.compare(mapOf("gross" to 1.0), mapOf("gross" to value)) }.isFailure)
            assertTrue(runCatching { PayslipEngineV2.compare(mapOf("gross" to 1.0), mapOf("gross" to 1.0), value) }.isFailure)
        }
        assertTrue(runCatching { PayslipEngineV2.compare(mapOf("gross" to 1e17), mapOf("gross" to 2e17)) }.isFailure)
    }

    @Test fun `comparison uses canonical decimal half up without changing ordinary cents`() {
        assertTrue(PayslipEngineV2.compare(mapOf("gross" to 1.005), mapOf("gross" to 1.01), 0.0).conforming)
        assertFalse(PayslipEngineV2.compare(mapOf("gross" to 1.005), mapOf("gross" to 1.00), 0.0).conforming)
        assertTrue(PayslipEngineV2.compare(mapOf("gross" to 2.675), mapOf("gross" to 2.68), 0.0).conforming)
        assertTrue(PayslipEngineV2.compare(mapOf("gross" to 0.0), mapOf("gross" to 0.0), 0.0).conforming)
        val upper = 90_071_992_547_409.9
        assertTrue(PayslipEngineV2.isValidAmount(upper))
        assertTrue(PayslipEngineV2.compare(mapOf("gross" to upper), mapOf("gross" to upper), 0.0).conforming)
        assertFalse(PayslipEngineV2.compare(mapOf("gross" to 0.0), mapOf("gross" to upper), 0.0).conforming)
    }

}
