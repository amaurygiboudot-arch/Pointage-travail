package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentedSalaryCanonicalProductionV2Test {
    @Test
    fun `chaine fiable produit la sortie canonique sans recalcul consommateur`() {
        val result = SegmentedSalaryCanonicalProductionV2.calculate(
            worked = worked(complementaryMinutes = 0),
            fixed = ConfirmedCashGrossComponentsV2(
                components = emptyList(),
                exhaustive = true,
                sourceId = "fixed-confirmed"
            ),
            year = 2026,
            companyPayroll = completeCompany()
        )

        assertTrue(result.complementaryMinutesReliable)
        assertTrue(result.output.cashGrossReliable)
        assertTrue(result.output.netBeforeIncomeTaxComplete)
        assertEquals(1_000.0, result.output.cashGross!!, 0.0001)
        assertEquals(0, result.output.complementaryMinutes)
    }

    @Test
    fun `minutes complementaires invalides bloquent le net sans effacer le brut prouve`() {
        val result = SegmentedSalaryCanonicalProductionV2.calculate(
            worked = worked(complementaryMinutes = -1),
            fixed = ConfirmedCashGrossComponentsV2(
                components = emptyList(),
                exhaustive = true,
                sourceId = "fixed-confirmed"
            ),
            year = 2026,
            companyPayroll = completeCompany()
        )

        assertFalse(result.complementaryMinutesReliable)
        assertTrue(result.output.workedGrossReliable)
        assertTrue(result.output.cashGrossReliable)
        assertFalse(result.output.netBeforeIncomeTaxComplete)
        assertNull(result.output.netBeforeIncomeTax)
        assertTrue(result.output.warnings.contains(
            SegmentedCashGrossNetProjectionV2.COMPLEMENTARY_WARNING
        ))
    }

    private fun worked(
        complementaryMinutes: Int
    ): SegmentedWorkedGrossProductionResultV2 {
        val week = SegmentedPayrollWeekEvidenceV2(
            weekYear = 2026,
            weekOfYear = 40,
            week = PayrollWeekV2(
                paidMinutes = 2_100,
                nightMinutes = 0,
                saturdayMinutes = 0,
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
                    overtimeGross = 0.0,
                    complementaryGross = 0.0,
                    premiumGross = 0.0,
                    variableOvertimeMinutes = 0,
                    complementaryMinutes = complementaryMinutes
                )
            )
        )
        val base = SegmentedMonthlyBaseResultV2(
            pieces = emptyList(),
            baseGross = 1_000.0,
            reliable = true,
            warnings = emptyList()
        )
        val assembly = SegmentedWorkedGrossAssemblyResultV2(
            baseGross = 1_000.0,
            variableGross = 0.0,
            workedGross = 1_000.0,
            reliable = true,
            warnings = emptyList()
        )
        return SegmentedWorkedGrossProductionResultV2(
            evidence = evidence,
            variables = variables,
            base = base,
            assembly = assembly
        )
    }

    private fun completeCompany() = CompanyPayrollOverridesV2.Snapshot(
        companyId = "company",
        idcc = null,
        referenceDate = LocalDate.of(2026, 1, 31),
        entryDate = LocalDate.of(2020, 1, 1),
        seniorityMonths = 72,
        contractType = ContractTypeV2.FULL_TIME,
        contractualWeeklyMinutes = 35 * 60,
        forfaitAnnualDays = null,
        unpaidAbsenceDays = 0,
        hasUnpaidAbsence = false,
        mutualEmployeeAmount = 0.0,
        providentEmployeeAmount = 0.0,
        transportEmployeeAmount = 0.0,
        employerProtectionTaxableAmount = 0.0,
        employeeProvidentNonDeductibleAmount = 0.0,
        incomeTaxRate = 0.05,
        professionalStatus = "NON_CADRE",
        protectionCategory = PlasturgieProtectionCategoryV2.classify(
            null,
            LocalDate.of(2026, 1, 31),
            null
        ),
        warnings = emptyList(),
        alsaceMoselleLocalRegime = false,
        employerProtectionCsgCrdsBaseAmount = 0.0,
        verifiedProtectionCategory = ProtectionCategoryV2.noConventionOverride(),
        benefitsInKindGross = 0.0,
        benefitsInKindReliable = true
    )
}
