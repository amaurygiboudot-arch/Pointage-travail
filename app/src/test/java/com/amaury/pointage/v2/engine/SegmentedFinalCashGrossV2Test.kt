package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentedFinalCashGrossV2Test {
    @Test
    fun `ajustements confirmes sont appliques une seule fois`() {
        val result = SegmentedFinalCashGrossV2.assemble(
            worked = worked(1_500.0),
            adjustments = source(
                listOf(
                    adjustment("seniority", 75.0),
                    adjustment("absence", -50.0)
                )
            )
        )

        assertTrue(result.reliable)
        assertEquals(25.0, result.adjustmentGross!!, 0.0001)
        assertEquals(1_525.0, result.cashGross!!, 0.0001)
    }

    @Test
    fun `source vide exhaustive vaut zero explicitement`() {
        val result = SegmentedFinalCashGrossV2.assemble(
            worked(1_500.0),
            source(emptyList())
        )
        assertTrue(result.reliable)
        assertEquals(0.0, result.adjustmentGross!!, 0.0)
        assertEquals(1_500.0, result.cashGross!!, 0.0)
    }

    @Test
    fun `source non exhaustive ne devient jamais zero`() {
        val result = SegmentedFinalCashGrossV2.assemble(
            worked(1_500.0),
            source(emptyList()).copy(exhaustive = false)
        )
        assertFalse(result.reliable)
        assertNull(result.cashGross)
        assertTrue(result.warnings.contains(SegmentedFinalCashGrossV2.SOURCE_WARNING))
    }

    @Test
    fun `ajustement duplique ou non fiable bloque`() {
        val duplicated = adjustment("same", 10.0)
        val result = SegmentedFinalCashGrossV2.assemble(
            worked(1_500.0),
            source(listOf(duplicated, duplicated))
        )
        assertFalse(result.reliable)
        assertNull(result.cashGross)
        assertTrue(result.warnings.contains(SegmentedFinalCashGrossV2.ADJUSTMENT_WARNING))
    }

    @Test
    fun `brut final negatif est bloque`() {
        val result = SegmentedFinalCashGrossV2.assemble(
            worked(100.0),
            source(listOf(adjustment("deduction", -150.0)))
        )
        assertFalse(result.reliable)
        assertNull(result.cashGross)
        assertTrue(result.warnings.contains(SegmentedFinalCashGrossV2.AMOUNT_WARNING))
    }

    private fun worked(amount: Double) = SegmentedWorkedGrossAssemblyResultV2(
        baseGross = amount,
        variableGross = 0.0,
        workedGross = amount,
        reliable = true,
        warnings = emptyList()
    )

    private fun source(items: List<SegmentedCashGrossAdjustmentV2>) =
        SegmentedCashGrossAdjustmentSourceV2(
            sourceId = "confirmed-adjustments",
            reliable = true,
            exhaustive = true,
            adjustments = items
        )

    private fun adjustment(id: String, delta: Double) =
        SegmentedCashGrossAdjustmentV2(
            id = id,
            label = id,
            grossDelta = delta,
            reliable = true
        )
}
