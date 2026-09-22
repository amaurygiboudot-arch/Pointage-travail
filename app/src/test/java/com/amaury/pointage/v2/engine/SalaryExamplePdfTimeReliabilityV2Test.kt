package com.amaury.pointage.v2.engine

import com.amaury.pointage.V2SalaryAdapter
import org.junit.Assert.assertEquals
import org.junit.Test

class SalaryExamplePdfTimeReliabilityV2Test {
    @Test
    fun `les valeurs de temps sont masquees quand la source est non fiable`() {
        val values = SalaryExamplePdfV2.timeSectionValues(
            salary = salary(paidTimeReliable = false),
            unpaidPauseMs = 30 * 60_000L
        )

        assertEquals("À confirmer", values.completedSessions)
        assertEquals("À confirmer", values.paidTime)
        assertEquals("À confirmer", values.regularHours)
        assertEquals("À confirmer", values.overtimeHours)
        assertEquals("À confirmer", values.unpaidPauses)
    }

    @Test
    fun `une estimation absente ne retombe pas sur un faux zero`() {
        val values = SalaryExamplePdfV2.timeSectionValues(
            salary = null,
            unpaidPauseMs = null
        )

        assertEquals("À confirmer", values.paidTime)
        assertEquals("À confirmer", values.regularHours)
    }

    @Test
    fun `les valeurs fiables sont formatees`() {
        val values = SalaryExamplePdfV2.timeSectionValues(
            salary = salary(paidTimeReliable = true),
            unpaidPauseMs = 30 * 60_000L
        )

        assertEquals("2", values.completedSessions)
        assertEquals("07h30", values.paidTime)
        assertEquals("07h00", values.regularHours)
        assertEquals("HS +25 %: 00h30", values.overtimeHours)
        assertEquals("00h30", values.unpaidPauses)
    }

    private fun salary(paidTimeReliable: Boolean) = V2SalaryAdapter.Result(
        regularMs = 7 * 60 * 60_000L,
        overtimeTiers = listOf(
            V2SalaryAdapter.TierDuration("HS +25 %", 30 * 60_000L, 1.25)
        ),
        totalWorkedMs = 7 * 60 * 60_000L + 30 * 60_000L,
        regularGross = 100.0,
        overtimeGross = 10.0,
        premiumsGross = 0.0,
        monthlyEstimatedGross = 110.0,
        monthlyGrossReliable = paidTimeReliable,
        nightMs = 0L,
        saturdayMs = 0L,
        sundayMs = 0L,
        complementaryMinutes = 0,
        completedSessions = 2,
        warnings = emptyList(),
        paidTimeReliable = paidTimeReliable
    )
}
