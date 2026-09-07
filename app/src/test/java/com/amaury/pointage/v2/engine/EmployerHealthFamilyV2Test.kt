package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class EmployerHealthFamilyV2Test {
    @Test
    fun `confirmed general 2026 rates calculate employer amounts`() {
        val result = EmployerHealthFamilyV2.calculate(2500.0, 0.13, 0.0525)

        assertTrue(result.complete)
        assertEquals(325.0, result.healthAmount!!, 0.001)
        assertEquals(131.25, result.familyAmount!!, 0.001)
        assertEquals(456.25, result.totalEmployerAmount!!, 0.001)
    }

    @Test
    fun `overlapping rules block automatic selection`() {
        val first = EmployerHealthFamilyV2.Record("a", 0.13, 0.0525, YearMonth.of(2026, 1), null, "DSN")
        val second = EmployerHealthFamilyV2.Record("b", 0.07, 0.0345, YearMonth.of(2026, 6), null, "Exonération confirmée")
        val result = EmployerHealthFamilyV2.resolve(listOf(first, second), YearMonth.of(2026, 9))

        assertFalse(result.reliable)
        assertNull(result.healthRate)
        assertTrue(result.warnings.any { it.contains("chevauchent", ignoreCase = true) })
    }

    @Test
    fun `missing confirmed rates never invent an employer amount`() {
        val result = EmployerHealthFamilyV2.calculate(2500.0, null, null)

        assertFalse(result.complete)
        assertNull(result.totalEmployerAmount)
        assertTrue(result.warnings.any { it.contains("manquants", ignoreCase = true) })
    }
}
