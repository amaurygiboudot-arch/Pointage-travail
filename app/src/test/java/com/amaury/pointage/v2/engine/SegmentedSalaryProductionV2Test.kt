package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentedSalaryProductionV2Test {
    @Test
    fun reliableFixedFactsReachCashAndNetOnce() {
        val result = SegmentedSalaryProductionV2.calculate(
            worked = worked(1_000.0),
            fixedFacts = listOf(
                ConfirmedCashGrossFixedFactV2("seniority", 50.0, true, true)
            ),
            fixedExhaustive = true,
            fixedSourceId = "fixed",
            year = 2026,
            companyPayroll = completeCompany(),
            complementaryMinutes = 0,
            complementaryMinutesReliable = true
        )

        assertTrue(result.cashGrossReliable)
        assertTrue(result.netComplete)
        assertEquals(1_050.0, result.cash.cashGross!!, 0.0001)
        assertEquals(1_050.0, result.net.projection!!.payroll.gross, 0.0001)
    }

    @Test
    fun unknownFixedFactBlocksBeforeNetInsteadOfBecomingZero() {
        val result = SegmentedSalaryProductionV2.calculate(
            worked = worked(1_000.0),
            fixedFacts = listOf(
                ConfirmedCashGrossFixedFactV2("seniority", null, true, false)
            ),
            fixedExhaustive = true,
            fixedSourceId = "fixed",
            year = 2026,
            companyPayroll = completeCompany(),
            complementaryMinutes = 0,
            complementaryMinutesReliable = true
        )

        assertFalse(result.cashGrossReliable)
        assertFalse(result.netComplete)
        assertNull(result.cash.cashGross)
        assertNull(result.net.projection)
        assertTrue(result.warnings.contains(ConfirmedCashGrossComponentsResolverV2.FACT_WARNING))
    }

    private fun worked(amount: Double) = SegmentedWorkedGrossProductionResultV2(
        evidence = SegmentedPayrollSessionEvidenceResultV2(
            slices = emptyList(),
            reliable = true,
            warnings = emptyList(),
            sourceId = "worked-test",
            contributingSessionIds = emptyList()
        ),
        variables = SegmentedWorkedVariableGrossSourceResultV2(
            pieces = emptyList(),
            reliable = true,
            warnings = emptyList()
        ),
        base = SegmentedMonthlyBaseResultV2(
            pieces = emptyList(),
            baseGross = amount,
            reliable = true,
            warnings = emptyList()
        ),
        assembly = SegmentedWorkedGrossAssemblyResultV2(
            baseGross = amount,
            variableGross = 0.0,
            workedGross = amount,
            reliable = true,
            warnings = emptyList()
        )
    )

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
