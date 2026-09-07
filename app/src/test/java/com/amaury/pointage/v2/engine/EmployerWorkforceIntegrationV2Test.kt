package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class EmployerWorkforceIntegrationV2Test {
    private fun company(band: EmployerWorkforceContributionsV2.Band?) = CompanyPayrollOverridesV2.Snapshot(
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
        mealAmount = 0.0,
        mutualEmployeeAmount = 0.0,
        providentEmployeeAmount = 0.0,
        transportEmployeeAmount = 0.0,
        employerProtectionTaxableAmount = 0.0,
        employeeProvidentNonDeductibleAmount = 0.0,
        incomeTaxRate = 0.05,
        professionalStatus = "NON_CADRE",
        protectionCategory = PlasturgieProtectionCategoryV2.classify(null, LocalDate.of(2026, 1, 31), null),
        warnings = emptyList(),
        alsaceMoselleLocalRegime = false,
        atMpEmployerRate = 0.0,
        benefitsInKindGross = 0.0,
        employerMobilityRate = 0.0,
        employerUnemploymentRate = 0.0,
        employerAgsRate = 0.0,
        employerUnemploymentAgsWarnings = emptyList(),
        employerWorkforceBand = band,
        employerWorkforceWarnings = emptyList()
    )

    @Test
    fun `workforce contributions change employer subtotal but never employee net`() {
        val under11 = NetSalaryEngineV2.calculate(2500.0, 2026, company(EmployerWorkforceContributionsV2.Band.UNDER_11))
        val atLeast50 = NetSalaryEngineV2.calculate(2500.0, 2026, company(EmployerWorkforceContributionsV2.Band.AT_LEAST_50))

        assertEquals(2.50, under11.employerFnalContribution!!, 0.001)
        assertEquals(13.75, under11.employerTrainingContribution!!, 0.001)
        assertEquals(12.50, atLeast50.employerFnalContribution!!, 0.001)
        assertEquals(25.00, atLeast50.employerTrainingContribution!!, 0.001)
        assertEquals(21.25, atLeast50.knownEmployerContributions - under11.knownEmployerContributions, 0.001)
        assertEquals(under11.netBeforeIncomeTax, atLeast50.netBeforeIncomeTax, 0.001)
        assertEquals(under11.netTaxable!!, atLeast50.netTaxable!!, 0.001)
        assertEquals(under11.netAfterIncomeTax!!, atLeast50.netAfterIncomeTax!!, 0.001)
    }
}
