package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SalaryExamplePdfWarningIsolationV2Test {
    @Test
    fun `pdf warning sections keep employer only warnings outside salary net controls`() {
        val sections = SalaryExamplePdfV2.warningSections(
            salaryWarnings = listOf("Heures : règle à confirmer"),
            payrollWarnings = listOf("PAS : taux personnel non renseigné"),
            employerCostWarnings = listOf(
                "Coût employeur : AT/MP à confirmer pour l'établissement.",
                "Coût employeur : versement mobilité à confirmer pour l'établissement et la période."
            )
        )

        assertEquals(
            listOf("Heures : règle à confirmer", "PAS : taux personnel non renseigné"),
            sections.salaryAndNet
        )
        assertTrue(sections.employerCost.any { it.contains("AT/MP") })
        assertTrue(sections.employerCost.any { it.contains("mobilité", ignoreCase = true) })
        assertFalse(sections.salaryAndNet.any { it.contains("AT/MP") })
        assertFalse(sections.salaryAndNet.any { it.contains("mobilité", ignoreCase = true) })
    }

    @Test
    fun `pdf warning sections deduplicate inside each reliability channel`() {
        val sections = SalaryExamplePdfV2.warningSections(
            salaryWarnings = listOf("Net à confirmer"),
            payrollWarnings = listOf("Net à confirmer"),
            employerCostWarnings = listOf("Coût employeur non certifié", "Coût employeur non certifié")
        )

        assertEquals(listOf("Net à confirmer"), sections.salaryAndNet)
        assertEquals(listOf("Coût employeur non certifié"), sections.employerCost)
    }
}
