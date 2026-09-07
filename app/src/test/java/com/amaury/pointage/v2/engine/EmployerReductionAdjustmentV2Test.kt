package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class EmployerReductionAdjustmentV2Test {
    @Test
    fun `zero amount explicitly confirms no employer reduction`() {
        val month=YearMonth.of(2026,9)
        val result=EmployerReductionAdjustmentV2.resolve(
            listOf(EmployerReductionAdjustmentV2.Record("r",month,0.0,"DSN")),month
        )
        assertTrue(result.reliable)
        assertEquals(0.0,result.amount!!,0.001)
    }

    @Test
    fun `confirmed amount is returned for exact month only`() {
        val september=YearMonth.of(2026,9)
        val result=EmployerReductionAdjustmentV2.resolve(
            listOf(EmployerReductionAdjustmentV2.Record("r",september,420.50,"DSN","RGDU")),september
        )
        assertTrue(result.reliable)
        assertEquals(420.50,result.amount!!,0.001)
    }

    @Test
    fun `missing month remains unknown`() {
        val result=EmployerReductionAdjustmentV2.resolve(emptyList(),YearMonth.of(2026,9))
        assertFalse(result.reliable)
        assertNull(result.amount)
    }

    @Test
    fun `duplicate totals for one month are blocked`() {
        val month=YearMonth.of(2026,9)
        val result=EmployerReductionAdjustmentV2.resolve(
            listOf(
                EmployerReductionAdjustmentV2.Record("a",month,100.0,"DSN"),
                EmployerReductionAdjustmentV2.Record("b",month,200.0,"Bulletin")
            ),month
        )
        assertFalse(result.reliable)
        assertNull(result.amount)
        assertTrue(result.warnings.any{it.contains("plusieurs",ignoreCase=true)})
    }
}
