package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class EmployerUnemploymentAgsV2Test {
    @Test
    fun `confirmed rates are resolved for the selected period`() {
        val record = EmployerUnemploymentAgsV2.Record(
            id = "rule",
            unemploymentRate = 0.04,
            agsRate = 0.0025,
            effectiveFrom = YearMonth.of(2026, 1),
            effectiveTo = null,
            source = "Notification ou DSN"
        )
        val result = EmployerUnemploymentAgsV2.resolve(listOf(record), YearMonth.of(2026, 9))

        assertTrue(result.reliable)
        assertEquals(0.04, result.unemploymentRate!!, 0.000001)
        assertEquals(0.0025, result.agsRate!!, 0.000001)
    }

    @Test
    fun `overlapping records block automatic calculation`() {
        val first = EmployerUnemploymentAgsV2.Record("a", 0.04, 0.0025, YearMonth.of(2026, 1), null, "A")
        val second = EmployerUnemploymentAgsV2.Record("b", 0.035, 0.0025, YearMonth.of(2026, 3), null, "B")
        val result = EmployerUnemploymentAgsV2.resolve(listOf(first, second), YearMonth.of(2026, 9))

        assertFalse(result.reliable)
        assertNull(result.unemploymentRate)
        assertTrue(result.warnings.any { it.contains("chevauchent", ignoreCase = true) })
    }

    @Test
    fun `general confirmed rates calculate employer only amounts`() {
        val result = EmployerUnemploymentAgsV2.calculate(
            grossSocial = 2500.0,
            fourTimesApplicableCeiling = 16020.0,
            unemploymentRate = 0.04,
            agsRate = 0.0025
        )

        assertTrue(result.complete)
        assertEquals(2500.0, result.baseAmount!!, 0.001)
        assertEquals(100.0, result.unemploymentAmount!!, 0.001)
        assertEquals(6.25, result.agsAmount!!, 0.001)
        assertEquals(106.25, result.totalEmployerAmount!!, 0.001)
    }

    @Test
    fun `base is capped at four applicable social security ceilings`() {
        val result = EmployerUnemploymentAgsV2.calculate(
            grossSocial = 20000.0,
            fourTimesApplicableCeiling = 16020.0,
            unemploymentRate = 0.04,
            agsRate = 0.0025
        )

        assertEquals(16020.0, result.baseAmount!!, 0.001)
        assertEquals(16020.0 * 0.04, result.unemploymentAmount!!, 0.001)
        assertEquals(16020.0 * 0.0025, result.agsAmount!!, 0.001)
    }
}
