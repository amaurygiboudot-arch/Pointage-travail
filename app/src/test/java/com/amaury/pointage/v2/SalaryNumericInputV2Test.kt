package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SalaryNumericInputV2Test {
    @Test
    fun `decimal positif accepte virgule et reste fini`() {
        assertEquals(12.5, SalaryNumericInputV2.positiveDecimal("12,5")!!, 0.0)
    }

    @Test
    fun `valeurs non finies ne franchissent jamais la frontiere`() {
        assertNull(SalaryNumericInputV2.positiveDecimal("Infinity"))
        assertNull(SalaryNumericInputV2.positiveDecimal("NaN"))
        assertNull(SalaryNumericInputV2.positiveDecimal(Double.POSITIVE_INFINITY))
        assertNull(SalaryNumericInputV2.nonNegativeDecimal(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `positif refuse zero et negatif tandis que non negatif accepte zero`() {
        assertNull(SalaryNumericInputV2.positiveDecimal("0"))
        assertNull(SalaryNumericInputV2.positiveDecimal("-1"))
        assertEquals(0.0, SalaryNumericInputV2.nonNegativeDecimal("0")!!, 0.0)
    }

    @Test
    fun `heures contractuelles utilisent le meme arrondi a la minute`() {
        assertEquals(35 * 60, SalaryNumericInputV2.positiveMinutesFromHours("35"))
        assertEquals(2101, SalaryNumericInputV2.positiveMinutesFromHours("35,01"))
    }

    @Test
    fun `conversion heures minutes refuse debordement ou non fini`() {
        assertNull(SalaryNumericInputV2.positiveMinutesFromHours("Infinity"))
        assertNull(SalaryNumericInputV2.positiveMinutesFromHours("1e308"))
    }
}
