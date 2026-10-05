package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SalaryExamplePdfContractV2Test {
    @Test
    fun `le PDF expose uniquement les valeurs du contrat V2 resolu pour la periode`() {
        val contract = ContractV2(
            id = "contract-history-2026-09",
            employerId = "company-a",
            type = ContractTypeV2.FULL_TIME,
            contractualWeeklyMinutes = 39 * 60,
            grossHourlyRate = 14.25,
            hireDateEpochDay = 0L,
            monthlyGrossSalary = 2_500.0
        )

        val display = SalaryExamplePdfV2.contractDisplayValues(contract)

        assertEquals("FULL_TIME", display.typeLabel)
        assertEquals(39 * 60, display.contractualWeeklyMinutes)
        assertEquals(14.25, display.grossHourlyRate ?: -1.0, 0.0)
        assertEquals(2_500.0, display.monthlyGrossSalary ?: -1.0, 0.0)
    }

    @Test
    fun `une periode sans contrat mensuel unique ne reutilise aucune valeur courante`() {
        val display = SalaryExamplePdfV2.contractDisplayValues(null)

        assertNull(display.typeLabel)
        assertNull(display.contractualWeeklyMinutes)
        assertNull(display.grossHourlyRate)
        assertNull(display.monthlyGrossSalary)
    }
}
