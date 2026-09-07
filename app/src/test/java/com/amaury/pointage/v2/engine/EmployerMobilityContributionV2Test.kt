package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class EmployerMobilityContributionV2Test {
    @Test
    fun `applicable rule resolves only inside its period`() {
        val rule = EmployerMobilityContributionV2.Record(
            id = "vm_1",
            status = EmployerMobilityContributionV2.Status.APPLICABLE,
            rate = 0.025,
            effectiveFrom = YearMonth.of(2026, 1),
            effectiveTo = YearMonth.of(2026, 6),
            source = "Urssaf - taux confirme"
        )
        val june = EmployerMobilityContributionV2.resolve(listOf(rule), YearMonth.of(2026, 6))
        val july = EmployerMobilityContributionV2.resolve(listOf(rule), YearMonth.of(2026, 7))

        assertTrue(june.reliable)
        assertEquals(0.025, june.rate!!, 0.000001)
        assertFalse(july.reliable)
        assertNull(july.rate)
    }

    @Test
    fun `confirmed not applicable resolves to zero`() {
        val rule = EmployerMobilityContributionV2.Record(
            id = "vm_none",
            status = EmployerMobilityContributionV2.Status.NOT_APPLICABLE,
            rate = null,
            effectiveFrom = YearMonth.of(2026, 1),
            source = "Effectif ou zone confirme non assujetti"
        )
        val result = EmployerMobilityContributionV2.resolve(listOf(rule), YearMonth.of(2026, 9))

        assertTrue(result.reliable)
        assertEquals(false, result.applicable)
        assertEquals(0.0, result.rate!!, 0.0)
    }

    @Test
    fun `overlapping rules block automatic rate`() {
        val records = listOf(
            EmployerMobilityContributionV2.Record(
                "a",
                EmployerMobilityContributionV2.Status.APPLICABLE,
                0.02,
                YearMonth.of(2026, 1),
                YearMonth.of(2026, 12),
                "Urssaf A"
            ),
            EmployerMobilityContributionV2.Record(
                "b",
                EmployerMobilityContributionV2.Status.APPLICABLE,
                0.025,
                YearMonth.of(2026, 7),
                null,
                "Urssaf B"
            )
        )
        val result = EmployerMobilityContributionV2.resolve(records, YearMonth.of(2026, 9))

        assertFalse(result.reliable)
        assertNull(result.rate)
    }

    @Test
    fun `calculation uses social gross and stays employer only`() {
        val result = EmployerMobilityContributionV2.calculate(2700.0, 0.02)

        assertTrue(result.complete)
        assertEquals(54.0, result.employerAmount!!, 0.001)
    }
}
