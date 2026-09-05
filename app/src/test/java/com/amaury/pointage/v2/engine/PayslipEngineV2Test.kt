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
}
