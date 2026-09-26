package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentedSalaryCanonicalOutputV2Test {
    @Test
    fun `sortie riche conserve temps brut cash et net sans recalcul`() {
        val worked = fixtureWorked()
        val cash = cash(worked, 1_100.0)
        val net = net(cash, complete = true)

        val result = SegmentedSalaryCanonicalOutputAssemblerV2.assemble(worked, cash, net)

        assertTrue(result.paidTimeReliable)
        assertTrue(result.premiumTimeReliable)
        assertTrue(result.workedGrossReliable)
        assertTrue(result.cashGrossReliable)
        assertTrue(result.netBeforeIncomeTaxComplete)
        assertEquals(2_400, result.paidMinutes)
        assertEquals(300, result.variableOvertimeMinutes)
        assertEquals(200.0, result.structuralOvertimeMinutes!!, 0.0001)
        assertEquals(500.0, result.totalOvertimeMinutes!!, 0.0001)
        assertEquals(50.0, result.overtimeGross!!, 0.0001)
        assertEquals(40.0, result.structuralOvertimeGross!!, 0.0001)
        assertEquals(90.0, result.totalOvertimeGross!!, 0.0001)
        assertEquals(0, result.complementaryMinutes)
        assertEquals(120, result.nightMinutes)
        assertEquals(1_000.0, result.workedGross!!, 0.0001)
        assertEquals(1_100.0, result.cashGross!!, 0.0001)
        assertEquals(900.0, result.netBeforeIncomeTax!!, 0.0001)
    }

    @Test
    fun `net incomplet ne masque pas un brut ni un temps prouves`() {
        val worked = fixtureWorked()
        val cash = cash(worked, 1_100.0)
        val net = net(cash, complete = false)

        val result = SegmentedSalaryCanonicalOutputAssemblerV2.assemble(worked, cash, net)

        assertTrue(result.paidTimeReliable)
        assertTrue(result.workedGrossReliable)
        assertTrue(result.cashGrossReliable)
        assertFalse(result.netBeforeIncomeTaxComplete)
        assertEquals(1_100.0, result.cashGross!!, 0.0001)
        assertNull(result.netBeforeIncomeTax)
    }

    @Test
    fun `chaine cash differente est bloquee sans faux montant final`() {
        val worked = fixtureWorked()
        val cash = cash(worked, 1_100.0)
        val otherCash = cash(worked, 1_200.0)
        val net = net(otherCash, complete = true)

        val result = SegmentedSalaryCanonicalOutputAssemblerV2.assemble(worked, cash, net)

        assertFalse(result.workedGrossReliable)
        assertFalse(result.cashGrossReliable)
        assertFalse(result.netBeforeIncomeTaxComplete)
        assertNull(result.cashGross)
        assertTrue(result.warnings.contains(SegmentedSalaryCanonicalOutputAssemblerV2.CHAIN_WARNING))
    }

    private fun fixtureWorked(): SegmentedWorkedGrossProductionResultV2 {
        val week = SegmentedPayrollWeekEvidenceV2(
            weekYear = 2026,
            weekOfYear = 40,
            week = PayrollWeekV2(
                paidMinutes = 2_400,
                nightMinutes = 120,
                saturdayMinutes = 60,
                sundayMinutes = 0,
                publicHolidayMinutes = 0
            ),
            fullWeekContextReliable = true
        )
        val slice = SegmentedPayrollSliceEvidenceV2(
            startEpochDay = 1,
            endEpochDay = 7,
            contractVersionId = "c1",
            ruleVersionId = "r1",
            weeks = listOf(week),
            paidTimeReliable = true,
            premiumTimeBreakdownReliable = true,
            payrollRulesReliable = true
        )
        val evidence = SegmentedPayrollSessionEvidenceResultV2(
            slices = listOf(slice),
            reliable = true,
            warnings = emptyList(),
            sourceId = "source",
            contributingSessionIds = listOf("s1")
        )
        val variables = SegmentedWorkedVariableGrossSourceResultV2(
            pieces = emptyList(),
            reliable = true,
            warnings = emptyList(),
            breakdowns = listOf(
                SegmentedWorkedVariableGrossBreakdownV2(
                    employerId = "company",
                    versionId = "c1",
                    startEpochDay = 1,
                    endEpochDay = 7,
                    overtimeGross = 50.0,
                    complementaryGross = 0.0,
                    premiumGross = 25.0,
                    variableOvertimeMinutes = 300,
                    complementaryMinutes = 0
                )
            )
        )
        val base = SegmentedMonthlyBaseResultV2(
            pieces = listOf(
                SegmentedMonthlyBasePieceV2(
                    versionId = "c1",
                    startEpochDay = 1,
                    endEpochDay = 7,
                    scheduledMinutes = 2_100,
                    factor = 1.0,
                    fullMonthBaseGross = 925.0,
                    proratedBaseGross = 925.0,
                    fullMonthStructuralOvertimeMinutes = 200.0,
                    proratedStructuralOvertimeMinutes = 200.0,
                    fullMonthStructuralOvertimeGross = 40.0,
                    proratedStructuralOvertimeGross = 40.0
                )
            ),
            baseGross = 925.0,
            reliable = true,
            warnings = emptyList()
        )
        val assembly = SegmentedWorkedGrossAssemblyResultV2(
            baseGross = 925.0,
            variableGross = 75.0,
            workedGross = 1_000.0,
            reliable = true,
            warnings = emptyList()
        )
        return SegmentedWorkedGrossProductionResultV2(evidence, variables, base, assembly)
    }

    private fun cash(
        worked: SegmentedWorkedGrossProductionResultV2,
        amount: Double
    ) = SegmentedCashGrossAssemblyResultV2(
        workedGross = worked.workedGross,
        additionalCashGross = amount - (worked.workedGross ?: 0.0),
        cashGross = amount,
        reliable = true,
        warnings = emptyList()
    )

    private fun net(
        cash: SegmentedCashGrossAssemblyResultV2,
        complete: Boolean
    ): SegmentedCashGrossNetProjectionResultV2 {
        val projection = EmployeeNetProjectionV2.Result(
            payroll = NetSalaryEngineV2.Result(
                gross = cash.cashGross ?: 0.0,
                socialSecurityCeiling = 4_005.0,
                socialSecurityCeilingComplete = true,
                statutory = 100.0,
                complementaryRetirement = 50.0,
                conventionProvidentEmployee = 0.0,
                conventionProvidentEmployer = 0.0,
                companyEmployeeDeductions = 50.0,
                employerStatusContributions = 0.0,
                employerAtMpContribution = null,
                netBeforeIncomeTax = 900.0,
                netTaxable = if (complete) 950.0 else null,
                incomeTax = if (complete) 45.0 else null,
                netAfterIncomeTax = if (complete) 855.0 else null,
                complete = complete,
                warnings = emptyList(),
                grossReliable = true
            ),
            netBeforeIncomeTax = if (complete) 900.0 else null,
            netTaxable = if (complete) 950.0 else null,
            incomeTax = if (complete) 45.0 else null,
            netAfterIncomeTax = if (complete) 855.0 else null,
            netBeforeIncomeTaxComplete = complete,
            warnings = emptyList()
        )
        return SegmentedCashGrossNetProjectionResultV2(
            cash = cash,
            projection = projection,
            cashGrossReliable = true,
            netBeforeIncomeTaxComplete = complete,
            warnings = emptyList()
        )
    }
}
