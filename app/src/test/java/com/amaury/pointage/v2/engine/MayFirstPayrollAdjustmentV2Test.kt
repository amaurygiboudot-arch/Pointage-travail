package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MayFirstPayrollAdjustmentV2Test {
    @Test
    fun `aucun travail le 1er mai ne cree aucune indemnité`() {
        val result = MayFirstPayrollAdjustmentV2.calculate(
            MayFirstPayrollAdjustmentV2.Input(0, 12.0, 1.0)
        )
        assertEquals(0.0, result.extraGross, 0.0001)
        assertTrue(result.reliable)
        assertNull(result.warning)
    }

    @Test
    fun `regle LEGI verifiee valorise seulement le cas horaire simple`() {
        val result = MayFirstPayrollAdjustmentV2.calculate(
            MayFirstPayrollAdjustmentV2.Input(8 * 60, 12.0, 1.0)
        )
        assertEquals(96.0, result.extraGross, 0.0001)
        assertTrue(result.reliable)
    }

    @Test
    fun `absence de regle LEGI interdit toute invention`() {
        val result = MayFirstPayrollAdjustmentV2.calculate(
            MayFirstPayrollAdjustmentV2.Input(60, 12.0, null)
        )
        assertEquals(0.0, result.extraGross, 0.0001)
        assertFalse(result.reliable)
    }

    @Test
    fun `heures sup ou complementaires bloquent la base automatique`() {
        val result = MayFirstPayrollAdjustmentV2.calculate(
            MayFirstPayrollAdjustmentV2.Input(60, 12.0, 1.0, overtimeOrComplementaryOverlap = true)
        )
        assertEquals(0.0, result.extraGross, 0.0001)
        assertFalse(result.reliable)
    }

    @Test
    fun `prime nuit bloque la base automatique`() {
        val result = MayFirstPayrollAdjustmentV2.calculate(
            MayFirstPayrollAdjustmentV2.Input(60, 12.0, 1.0, nightPremiumOverlap = true)
        )
        assertEquals(0.0, result.extraGross, 0.0001)
        assertFalse(result.reliable)
    }

    @Test
    fun `prime weekend bloque la base automatique`() {
        val result = MayFirstPayrollAdjustmentV2.calculate(
            MayFirstPayrollAdjustmentV2.Input(60, 12.0, 1.0, weekendPremiumOverlap = true)
        )
        assertEquals(0.0, result.extraGross, 0.0001)
        assertFalse(result.reliable)
    }
}
