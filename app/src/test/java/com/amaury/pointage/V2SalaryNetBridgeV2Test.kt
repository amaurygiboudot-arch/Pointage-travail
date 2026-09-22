package com.amaury.pointage

import com.amaury.pointage.v2.engine.CompanyPayrollOverridesV2
import com.amaury.pointage.v2.engine.EmployeeNetProjectionV2
import com.amaury.pointage.v2.engine.PlasturgieProtectionCategoryV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class V2SalaryNetBridgeV2Test {
    private fun salary(
        gross: Double = 2500.0,
        reliable: Boolean = true,
        paidTimeReliable: Boolean = reliable,
        warnings: List<String> = emptyList()
    ) = V2SalaryAdapter.Result(
        regularMs = 0L,
        overtimeTiers = emptyList(),
        totalWorkedMs = 0L,
        regularGross = gross,
        overtimeGross = 0.0,
        premiumsGross = 0.0,
        monthlyEstimatedGross = gross,
        monthlyGrossReliable = reliable,
        nightMs = 0L,
        saturdayMs = 0L,
        sundayMs = 0L,
        complementaryMinutes = 0,
        completedSessions = 0,
        warnings = warnings,
        paidTimeReliable = paidTimeReliable
    )

    private fun companyPayroll(
        mutualEmployeeAmount: Double? = 100.0,
        employerProtectionTaxableAmount: Double? = 0.0,
        employeeProvidentNonDeductibleAmount: Double? = 0.0,
        incomeTaxRate: Double? = 0.05,
        warnings: List<String> = emptyList()
    ) = CompanyPayrollOverridesV2.Snapshot(
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
        mutualEmployeeAmount = mutualEmployeeAmount,
        providentEmployeeAmount = 0.0,
        transportEmployeeAmount = 0.0,
        employerProtectionTaxableAmount = employerProtectionTaxableAmount,
        employeeProvidentNonDeductibleAmount = employeeProvidentNonDeductibleAmount,
        incomeTaxRate = incomeTaxRate,
        professionalStatus = "NON_CADRE",
        protectionCategory = PlasturgieProtectionCategoryV2.classify(
            null,
            LocalDate.of(2026, 1, 31),
            null
        ),
        warnings = warnings,
        alsaceMoselleLocalRegime = false,
        employerProtectionCsgCrdsBaseAmount = 0.0,
        verifiedProtectionCategory = ProtectionCategoryV2.noConventionOverride(),
        benefitsInKindGross = 0.0,
        benefitsInKindReliable = true
    )

    @Test
    fun usesFinalAdapterGrossAndDoesNotDoubleSubtractCompanyDeductions() {
        val salary = salary(gross = 2500.0)
        val company = companyPayroll(mutualEmployeeAmount = 100.0)
        val expected = EmployeeNetProjectionV2.calculate(
            gross = salary.monthlyEstimatedGross,
            year = 2026,
            company = company,
            complementaryMinutes = salary.complementaryMinutes,
            upstreamGrossReliable = salary.monthlyGrossReliable
        )

        val actual = V2SalaryNetBridgeV2.project(salary, 2026, company)

        assertTrue(actual.netBeforeIncomeTaxComplete)
        assertEquals(expected.netBeforeIncomeTax, actual.netBeforeIncomeTax)
        assertEquals(expected.netTaxable, actual.netTaxable)
        assertEquals(expected.incomeTax, actual.incomeTax)
        assertEquals(expected.netAfterIncomeTax, actual.netAfterIncomeTax)
        assertEquals(expected.payroll, actual.payroll)
        assertEquals(100.0, actual.mutualEmployeeAmount!!, 0.0)
        assertEquals(0.0, actual.providentEmployeeAmount!!, 0.0)
        assertEquals(2500.0, actual.salary.monthlyEstimatedGross, 0.0)
    }

    @Test
    fun unreliablePaidTimeNeverPublishesEmployeeNet() {
        val actual = V2SalaryNetBridgeV2.project(
            salary = salary(gross = 2500.0, reliable = true, paidTimeReliable = false),
            year = 2026,
            companyPayroll = companyPayroll()
        )

        assertFalse(actual.netBeforeIncomeTaxComplete)
        assertNull(actual.netBeforeIncomeTax)
        assertNull(actual.netTaxable)
        assertNull(actual.incomeTax)
        assertNull(actual.netAfterIncomeTax)
    }

    @Test
    fun missingIncomeTaxRateKeepsBeforeTaxNetWithoutInventingAfterTaxNet() {
        val actual = V2SalaryNetBridgeV2.project(
            salary = salary(),
            year = 2026,
            companyPayroll = companyPayroll(incomeTaxRate = null)
        )

        assertTrue(actual.netBeforeIncomeTaxComplete)
        assertTrue(actual.netBeforeIncomeTax != null)
        assertTrue(actual.netTaxable != null)
        assertNull(actual.incomeTax)
        assertNull(actual.netAfterIncomeTax)
    }

    @Test
    fun missingTaxSpecificInputsKeepBeforeTaxNetButHideFiscalAmounts() {
        val actual = V2SalaryNetBridgeV2.project(
            salary = salary(),
            year = 2026,
            companyPayroll = companyPayroll(employerProtectionTaxableAmount = null)
        )

        assertTrue(actual.netBeforeIncomeTaxComplete)
        assertTrue(actual.netBeforeIncomeTax != null)
        assertNull(actual.netTaxable)
        assertNull(actual.incomeTax)
        assertNull(actual.netAfterIncomeTax)
    }

    @Test
    fun unreliableFinalGrossNeverPublishesEmployeeNet() {
        val actual = V2SalaryNetBridgeV2.project(
            salary = salary(gross = 2500.0, reliable = false),
            year = 2026,
            companyPayroll = companyPayroll()
        )

        assertFalse(actual.netBeforeIncomeTaxComplete)
        assertNull(actual.netBeforeIncomeTax)
        assertNull(actual.netTaxable)
        assertNull(actual.incomeTax)
        assertNull(actual.netAfterIncomeTax)
        assertTrue(actual.warnings.any { it.contains("temps/primes", ignoreCase = true) })
    }

    @Test
    fun bridgeKeepsSalaryAndPayrollWarningsVisible() {
        val actual = V2SalaryNetBridgeV2.project(
            salary = salary(warnings = listOf("Avertissement brut")),
            year = 2026,
            companyPayroll = companyPayroll(warnings = listOf("Avertissement paramètres paie"))
        )

        assertTrue(actual.warnings.contains("Avertissement brut"))
        assertTrue(actual.warnings.contains("Avertissement paramètres paie"))
    }
}
