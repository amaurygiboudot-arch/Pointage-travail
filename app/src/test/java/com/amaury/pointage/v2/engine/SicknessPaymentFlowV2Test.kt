package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.AbsenceSalaryTreatmentV2
import com.amaury.pointage.v2.model.AbsenceSubrogationV2
import com.amaury.pointage.v2.model.AbsenceV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class SicknessPaymentFlowV2Test {
    private fun absence(
        treatment: AbsenceSalaryTreatmentV2,
        subrogation: AbsenceSubrogationV2
    ) = AbsenceV2(
        id = "sick",
        employerId = "company-a",
        type = AbsencePayrollImpactV2.TYPE_SICKNESS,
        startMs = 1L,
        endMs = 2L,
        salaryTreatment = treatment,
        fullDay = true,
        subrogation = subrogation
    )

    private val allowance = SicknessDailyAllowanceV2.Result(
        complete = true,
        dailyGross = 40.0,
        payableDays = 5,
        estimatedGrossTotal = 200.0,
        referenceMonths = listOf(
            SicknessDailyAllowanceV2.SalaryMonth(YearMonth.of(2026, 5), 2000.0),
            SicknessDailyAllowanceV2.SalaryMonth(YearMonth.of(2026, 6), 2000.0),
            SicknessDailyAllowanceV2.SalaryMonth(YearMonth.of(2026, 7), 2000.0)
        ),
        warnings = emptyList()
    )

    @Test
    fun `sans subrogation IJSS vont directement au salarie`() {
        val result = SicknessPaymentFlowV2.resolve(
            absence(AbsenceSalaryTreatmentV2.UNPAID, AbsenceSubrogationV2.NO),
            allowance
        )
        assertEquals(SicknessPaymentFlowV2.IjssRecipient.EMPLOYEE, result.ijssRecipient)
        assertEquals(200.0, result.directEmployeeIjssGross!!, 0.001)
        assertNull(result.employerIjssReimbursementGross)
        assertTrue(result.doubleCountSafe)
    }

    @Test
    fun `avec subrogation IJSS vont a l employeur et ne sont pas ajoutees au salarie`() {
        val result = SicknessPaymentFlowV2.resolve(
            absence(AbsenceSalaryTreatmentV2.FULLY_MAINTAINED, AbsenceSubrogationV2.YES),
            allowance
        )
        assertEquals(SicknessPaymentFlowV2.IjssRecipient.EMPLOYER, result.ijssRecipient)
        assertNull(result.directEmployeeIjssGross)
        assertEquals(200.0, result.employerIjssReimbursementGross!!, 0.001)
        assertTrue(result.doubleCountSafe)
    }

    @Test
    fun `subrogation inconnue bloque le routage automatique`() {
        val result = SicknessPaymentFlowV2.resolve(
            absence(AbsenceSalaryTreatmentV2.FULLY_MAINTAINED, AbsenceSubrogationV2.TO_CONFIRM),
            allowance
        )
        assertEquals(SicknessPaymentFlowV2.IjssRecipient.TO_CONFIRM, result.ijssRecipient)
        assertNull(result.directEmployeeIjssGross)
        assertNull(result.employerIjssReimbursementGross)
        assertFalse(result.doubleCountSafe)
    }

    @Test
    fun `subrogation avec absence declaree sans maintien est incoherente`() {
        val result = SicknessPaymentFlowV2.resolve(
            absence(AbsenceSalaryTreatmentV2.UNPAID, AbsenceSubrogationV2.YES),
            allowance
        )
        assertFalse(result.doubleCountSafe)
        assertTrue(result.warnings.any { it.contains("combinaison à vérifier") })
    }

    @Test
    fun `maintien partiel reste non calculable sans montant exact`() {
        val result = SicknessPaymentFlowV2.resolve(
            absence(AbsenceSalaryTreatmentV2.PARTIALLY_MAINTAINED, AbsenceSubrogationV2.YES),
            allowance
        )
        assertFalse(result.salaryMaintenanceKnown)
        assertTrue(result.warnings.any { it.contains("partiel") })
    }
}