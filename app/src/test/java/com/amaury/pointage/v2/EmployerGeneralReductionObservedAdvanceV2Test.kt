package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.EmployerGeneralReductionObservedAdvanceV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class EmployerGeneralReductionObservedAdvanceV2Test {
    @Test
    fun `twelve explicit months including zero create a confirmed observed basis`() {
        val records = (1..12).map { month ->
            EmployerGeneralReductionObservedAdvanceV2.Record(
                id = "m$month",
                month = YearMonth.of(2026, month),
                amount = if (month == 7) 0.0 else month + 0.37,
                source = if (month <= 6) "DSN S1" else "DSN S2"
            )
        }

        val result = EmployerGeneralReductionObservedAdvanceV2.resolveYear(records, 2026)

        assertEquals(EmployerGeneralReductionObservedAdvanceV2.YearState.COMPLETE_CONFIRMED, result.state)
        assertTrue(result.completeConfirmed)
        assertEquals(12, result.monthlyAdvances.size)
        assertEquals(0.0, result.monthlyAdvances.single { it.month == 7 }.amount, 0.0)
        assertEquals(12.37, result.monthlyAdvances.last().amount, 0.0)
        assertEquals(listOf("DSN S1", "DSN S2"), result.sources)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `missing month stays incomplete and is never converted to zero`() {
        val records = (1..11).map { month -> record(month = month, amount = 10.0) }

        val result = EmployerGeneralReductionObservedAdvanceV2.resolveYear(records, 2026)

        assertEquals(EmployerGeneralReductionObservedAdvanceV2.YearState.INCOMPLETE, result.state)
        assertFalse(result.completeConfirmed)
        assertTrue(result.monthlyAdvances.isEmpty())
        assertTrue(result.warnings.any { it.contains("12") })
    }

    @Test
    fun `duplicate month invalidates observed historical basis`() {
        val records = (1..12).map { month -> record(month = month, amount = 10.0) } +
            record(month = 4, amount = 12.0, id = "duplicate")

        val result = EmployerGeneralReductionObservedAdvanceV2.resolveYear(records, 2026)

        assertEquals(EmployerGeneralReductionObservedAdvanceV2.YearState.INVALID, result.state)
        assertTrue(result.monthlyAdvances.isEmpty())
        assertTrue(result.warnings.any { it.contains("plusieurs", ignoreCase = true) })
    }

    @Test
    fun `negative amount invalidates observed historical basis`() {
        val result = EmployerGeneralReductionObservedAdvanceV2.resolveYear(
            records = listOf(record(month = 1, amount = -0.01)),
            year = 2026
        )

        assertEquals(EmployerGeneralReductionObservedAdvanceV2.YearState.INVALID, result.state)
    }

    @Test
    fun `non finite amount invalidates observed historical basis`() {
        val result = EmployerGeneralReductionObservedAdvanceV2.resolveYear(
            records = listOf(record(month = 1, amount = Double.NaN)),
            year = 2026
        )

        assertEquals(EmployerGeneralReductionObservedAdvanceV2.YearState.INVALID, result.state)
    }

    @Test
    fun `blank source invalidates observed historical basis`() {
        val result = EmployerGeneralReductionObservedAdvanceV2.resolveYear(
            records = listOf(record(month = 1, amount = 10.0, source = " ")),
            year = 2026
        )

        assertEquals(EmployerGeneralReductionObservedAdvanceV2.YearState.INVALID, result.state)
    }

    @Test
    fun `other years do not fill missing months of requested year`() {
        val records = (1..12).map { month -> record(month = month, amount = 10.0, year = 2025) } +
            (1..11).map { month -> record(month = month, amount = 20.0, year = 2026, id = "current-$month") }

        val result = EmployerGeneralReductionObservedAdvanceV2.resolveYear(records, 2026)

        assertEquals(EmployerGeneralReductionObservedAdvanceV2.YearState.INCOMPLETE, result.state)
        assertTrue(result.warnings.any { it.contains("12") })
    }

    private fun record(
        month: Int,
        amount: Double,
        year: Int = 2026,
        id: String = "$year-$month",
        source: String = "DSN"
    ) = EmployerGeneralReductionObservedAdvanceV2.Record(
        id = id,
        month = YearMonth.of(year, month),
        amount = amount,
        source = source
    )
}
