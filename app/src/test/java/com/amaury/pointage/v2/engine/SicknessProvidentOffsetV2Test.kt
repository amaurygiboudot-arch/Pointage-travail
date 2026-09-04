package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.AbsenceProvidentTreatmentV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SicknessProvidentOffsetV2Test {
    @Test
    fun `prevoyance a confirmer ne fabrique pas de complement final`() {
        val result = SicknessProvidentOffsetV2.apply(
            500.0,
            AbsenceProvidentTreatmentV2.TO_CONFIRM,
            null
        )
        assertFalse(result.overlapConfirmed)
        assertNull(result.finalEmployerComplementNet)
    }

    @Test
    fun `absence de prevoyance confirmee conserve le complement`() {
        val result = SicknessProvidentOffsetV2.apply(
            500.0,
            AbsenceProvidentTreatmentV2.NONE_CONFIRMED,
            null
        )
        assertTrue(result.overlapConfirmed)
        assertEquals(0.0, result.providentNetDeducted!!, 0.001)
        assertEquals(500.0, result.finalEmployerComplementNet!!, 0.001)
    }

    @Test
    fun `montant net confirme est deduit une seule fois`() {
        val result = SicknessProvidentOffsetV2.apply(
            500.0,
            AbsenceProvidentTreatmentV2.NET_AMOUNT_CONFIRMED,
            120.0
        )
        assertTrue(result.overlapConfirmed)
        assertEquals(120.0, result.providentNetDeducted!!, 0.001)
        assertEquals(380.0, result.finalEmployerComplementNet!!, 0.001)
    }

    @Test
    fun `prevoyance superieure au complement ne produit jamais un negatif`() {
        val result = SicknessProvidentOffsetV2.apply(
            100.0,
            AbsenceProvidentTreatmentV2.NET_AMOUNT_CONFIRMED,
            150.0
        )
        assertEquals(100.0, result.providentNetDeducted!!, 0.001)
        assertEquals(0.0, result.finalEmployerComplementNet!!, 0.001)
        assertTrue(result.warnings.any { it.contains("supérieure") })
    }
}
