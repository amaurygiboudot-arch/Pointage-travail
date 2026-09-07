package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class EmployerApprenticeshipTaxV2Test {
    @Test
    fun `general 2026 rates calculate principal and balance separately`() {
        val result = EmployerApprenticeshipTaxV2.calculate(
            grossSocial = 2500.0,
            principalRate = 0.0059,
            balanceRate = 0.0009
        )

        assertTrue(result.complete)
        assertEquals(14.75, result.principalAmount!!, 0.001)
        assertEquals(2.25, result.balanceAccrualAmount!!, 0.001)
        assertEquals(17.00, result.totalEmployerAmount!!, 0.001)
    }

    @Test
    fun `alsace moselle can be represented without balance`() {
        val result = EmployerApprenticeshipTaxV2.calculate(
            grossSocial = 2500.0,
            principalRate = 0.0044,
            balanceRate = 0.0
        )

        assertTrue(result.complete)
        assertEquals(11.00, result.principalAmount!!, 0.001)
        assertEquals(0.0, result.balanceAccrualAmount!!, 0.001)
        assertEquals(11.00, result.totalEmployerAmount!!, 0.001)
    }

    @Test
    fun `confirmed exemption can be represented with zero rates`() {
        val result = EmployerApprenticeshipTaxV2.calculate(2500.0, 0.0, 0.0)

        assertTrue(result.complete)
        assertEquals(0.0, result.totalEmployerAmount!!, 0.001)
    }

    @Test
    fun `overlapping records block automatic calculation`() {
        val records = listOf(
            EmployerApprenticeshipTaxV2.Record(
                id = "a",
                principalRate = 0.0059,
                balanceRate = 0.0009,
                effectiveFrom = YearMonth.of(2026, 1),
                effectiveTo = null,
                source = "Urssaf"
            ),
            EmployerApprenticeshipTaxV2.Record(
                id = "b",
                principalRate = 0.0044,
                balanceRate = 0.0,
                effectiveFrom = YearMonth.of(2026, 7),
                effectiveTo = null,
                source = "Régime local confirmé"
            )
        )

        val snapshot = EmployerApprenticeshipTaxV2.resolve(records, YearMonth.of(2026, 9))

        assertFalse(snapshot.reliable)
        assertNull(snapshot.principalRate)
        assertTrue(snapshot.warnings.any { it.contains("chevauchent", ignoreCase = true) })
    }
}
