package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class EmployerGeneralReductionContextV2Test {
    private val month = YearMonth.of(2026, 9)

    private fun record(
        id: String = "rgdu-2026-09",
        fullMonth: Boolean = true,
        standardCase: Boolean = true,
        noOtherReduction: Boolean = true,
        source: String = "Bulletin 09/2026"
    ) = EmployerGeneralReductionContextV2.Record(
        id = id,
        month = month,
        fullMonthPresent = fullMonth,
        standardCommonLawCaseConfirmed = standardCase,
        noOtherEmployerReductionConfirmed = noOtherReduction,
        source = source
    )

    @Test
    fun `missing month stays unknown`() {
        val result = EmployerGeneralReductionContextV2.resolve(emptyList(), month)

        assertFalse(result.reliable)
        assertNull(result.fullMonthPresent)
        assertNull(result.standardCommonLawCaseConfirmed)
        assertNull(result.noOtherEmployerReductionConfirmed)
    }

    @Test
    fun `confirmed false values are preserved as facts`() {
        val result = EmployerGeneralReductionContextV2.resolve(
            listOf(record(fullMonth = false, standardCase = false, noOtherReduction = false)),
            month
        )

        assertTrue(result.reliable)
        assertEquals(false, result.fullMonthPresent)
        assertEquals(false, result.standardCommonLawCaseConfirmed)
        assertEquals(false, result.noOtherEmployerReductionConfirmed)
        assertEquals("Bulletin 09/2026", result.source)
    }

    @Test
    fun `blank source blocks the whole context`() {
        val result = EmployerGeneralReductionContextV2.resolve(
            listOf(record(source = "  ")),
            month
        )

        assertFalse(result.reliable)
        assertNull(result.fullMonthPresent)
        assertTrue(result.warnings.any { it.contains("sans source") })
    }

    @Test
    fun `duplicate month blocks automatic use`() {
        val result = EmployerGeneralReductionContextV2.resolve(
            listOf(record(id = "a"), record(id = "b")),
            month
        )

        assertFalse(result.reliable)
        assertTrue(result.warnings.any { it.contains("plusieurs contextes") })
    }

    @Test
    fun `single sourced context is reliable`() {
        val result = EmployerGeneralReductionContextV2.resolve(listOf(record()), month)

        assertTrue(result.reliable)
        assertEquals(true, result.fullMonthPresent)
        assertEquals(true, result.standardCommonLawCaseConfirmed)
        assertEquals(true, result.noOtherEmployerReductionConfirmed)
        assertTrue(result.warnings.isEmpty())
    }
}
