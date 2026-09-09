package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.EmployerGeneralReductionAnnualRegularizationV2
import com.amaury.pointage.v2.engine.EmployerGeneralReductionObservedAdvanceV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RgduAnnualAdvanceSelectionV2Test {
    @Test
    fun `complete observed basis has priority over reconstructed advances`() {
        val reconstructed = advances(amount = 100.0)
        val observed = EmployerGeneralReductionObservedAdvanceV2.YearSnapshot(
            state = EmployerGeneralReductionObservedAdvanceV2.YearState.COMPLETE_CONFIRMED,
            monthlyAdvances = advances(amount = 80.0),
            sources = listOf("DSN 2026"),
            warnings = emptyList()
        )

        val selection = CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.selectAdvances(
            reconstructed = reconstructed,
            observed = observed
        )

        assertTrue(selection.reliable)
        assertEquals(
            CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.AdvanceBasis.CONFIRMED_OBSERVED,
            selection.basis
        )
        assertEquals(80.0, selection.monthlyAdvances.first().amount, 0.0)
        assertTrue(selection.notes.any { it.contains("DSN 2026") })
    }

    @Test
    fun `incomplete observed basis falls back to clearly labelled reconstruction`() {
        val reconstructed = advances(amount = 100.0)
        val observed = EmployerGeneralReductionObservedAdvanceV2.YearSnapshot(
            state = EmployerGeneralReductionObservedAdvanceV2.YearState.INCOMPLETE,
            monthlyAdvances = emptyList(),
            sources = listOf("DSN janvier"),
            warnings = listOf("RGDU observée : mois manquants.")
        )

        val selection = CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.selectAdvances(
            reconstructed = reconstructed,
            observed = observed
        )

        assertTrue(selection.reliable)
        assertEquals(
            CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.AdvanceBasis.RECONSTRUCTED_AUTOMATIC,
            selection.basis
        )
        assertEquals(reconstructed, selection.monthlyAdvances)
        assertTrue(selection.notes.any { it.contains("mois manquants", ignoreCase = true) })
        assertTrue(selection.notes.any { it.contains("reconstruites automatiquement", ignoreCase = true) })
        assertTrue(selection.warnings.isEmpty())
    }

    @Test
    fun `invalid observed store blocks regularization instead of silently falling back`() {
        val observed = EmployerGeneralReductionObservedAdvanceV2.YearSnapshot(
            state = EmployerGeneralReductionObservedAdvanceV2.YearState.INVALID,
            monthlyAdvances = emptyList(),
            sources = emptyList(),
            warnings = listOf("RGDU observée : stockage local incohérent.")
        )

        val selection = CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.selectAdvances(
            reconstructed = advances(amount = 100.0),
            observed = observed
        )

        assertFalse(selection.reliable)
        assertEquals(null, selection.basis)
        assertTrue(selection.monthlyAdvances.isEmpty())
        assertTrue(selection.warnings.any { it.contains("incohérent", ignoreCase = true) })
    }

    private fun advances(amount: Double) = (1..12).map { month ->
        EmployerGeneralReductionAnnualRegularizationV2.MonthlyAdvance(
            month = month,
            amount = amount
        )
    }
}
