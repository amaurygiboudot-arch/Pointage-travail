package com.amaury.pointage.v2.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class MonthlySalaryProrationV2Test {
    private val march = LocalDate.of(2026, 3, 31)

    @Test
    fun `entree avant le mois permet le brut mensualise complet`() {
        val result = MonthlySalaryProrationV2.assess(LocalDate.of(2020, 1, 1), march)
        assertEquals(MonthlySalaryProrationV2.State.FULL_MONTH, result.state)
        assertTrue(result.exactMonthlyGrossAvailable)
    }

    @Test
    fun `entree le premier jour permet le brut mensualise complet`() {
        val result = MonthlySalaryProrationV2.assess(LocalDate.of(2026, 3, 1), march)
        assertEquals(MonthlySalaryProrationV2.State.FULL_MONTH, result.state)
        assertTrue(result.exactMonthlyGrossAvailable)
    }

    @Test
    fun `entree en cours de mois bloque le faux plein mois`() {
        val result = MonthlySalaryProrationV2.assess(LocalDate.of(2026, 3, 16), march)
        assertEquals(MonthlySalaryProrationV2.State.ENTRY_DURING_MONTH, result.state)
        assertFalse(result.exactMonthlyGrossAvailable)
        assertTrue(result.warning.orEmpty().contains("Aucun prorata calendaire"))
    }

    @Test
    fun `periode anterieure au contrat ne produit pas de brut fiable`() {
        val result = MonthlySalaryProrationV2.assess(LocalDate.of(2026, 4, 1), march)
        assertEquals(MonthlySalaryProrationV2.State.BEFORE_EMPLOYMENT, result.state)
        assertFalse(result.exactMonthlyGrossAvailable)
    }

    @Test
    fun `date entree inconnue reste explicitement non fiable`() {
        val result = MonthlySalaryProrationV2.assess(null, march)
        assertEquals(MonthlySalaryProrationV2.State.ENTRY_DATE_UNKNOWN, result.state)
        assertFalse(result.exactMonthlyGrossAvailable)
    }
}
