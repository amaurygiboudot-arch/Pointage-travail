package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class EmployerWorkforceContributionsV2Test {
    @Test
    fun `under 11 uses capped FNAL 0 point 10 and training 0 point 55`() {
        val result = EmployerWorkforceContributionsV2.calculate(
            grossSocial = 5000.0,
            applicableMonthlyCeiling = 4005.0,
            year = 2026,
            band = EmployerWorkforceContributionsV2.Band.UNDER_11
        )
        assertEquals(4.005, result.fnalAmount!!, 0.001)
        assertEquals(27.50, result.trainingAmount!!, 0.001)
        assertTrue(result.complete)
    }

    @Test
    fun `11 to 49 uses capped FNAL and one percent training`() {
        val result = EmployerWorkforceContributionsV2.calculate(
            grossSocial = 5000.0,
            applicableMonthlyCeiling = 4005.0,
            year = 2026,
            band = EmployerWorkforceContributionsV2.Band.FROM_11_TO_49
        )
        assertEquals(4.005, result.fnalAmount!!, 0.001)
        assertEquals(50.0, result.trainingAmount!!, 0.001)
    }

    @Test
    fun `50 or more uses uncapped FNAL 0 point 50 and one percent training`() {
        val result = EmployerWorkforceContributionsV2.calculate(
            grossSocial = 5000.0,
            applicableMonthlyCeiling = 4005.0,
            year = 2026,
            band = EmployerWorkforceContributionsV2.Band.AT_LEAST_50
        )
        assertEquals(25.0, result.fnalAmount!!, 0.001)
        assertEquals(50.0, result.trainingAmount!!, 0.001)
    }

    @Test
    fun `missing band never invents an employer contribution`() {
        val result = EmployerWorkforceContributionsV2.calculate(2500.0, 4005.0, 2026, null)
        assertFalse(result.complete)
        assertTrue(result.warnings.any { it.contains("effectif", ignoreCase = true) })
    }

    @Test
    fun `overlapping workforce bands block resolution`() {
        val first = EmployerWorkforceContributionsV2.Record("a", EmployerWorkforceContributionsV2.Band.UNDER_11, YearMonth.of(2026, 1), null, "DSN")
        val second = EmployerWorkforceContributionsV2.Record("b", EmployerWorkforceContributionsV2.Band.FROM_11_TO_49, YearMonth.of(2026, 6), null, "DSN")
        val result = EmployerWorkforceContributionsV2.resolve(listOf(first, second), YearMonth.of(2026, 9))
        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("chevauchent", ignoreCase = true) })
    }
}
