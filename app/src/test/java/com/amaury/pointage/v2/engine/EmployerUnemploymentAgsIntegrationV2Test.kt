package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EmployerUnemploymentAgsIntegrationV2Test {
    private fun company(unemploymentRate: Double?, agsRate: Double?) = CompanyPayrollOverridesV2.Snapshot(
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
        protectionCategory = PlasturgieProtectionCategoryV2.classify(null, LocalDate.of(2026, 1, 31), null),
        warnings = emptyList(),
        alsaceMoselleLocalRegime = false,
        atMpEmployerRate = 0.0,
        benefitsInKindGross = 0.0,
        employerMobilityRate = 0.0,
        employerUnemploymentRate = unemploymentRate,
        employerAgsRate = agsRate,
        employerUnemploymentAgsSource = "Notification ou DSN",
        employerUnemploymentAgsWarnings = emptyList()
    )

    @Test
    fun `confirmed unemployment and AGS increase employer subtotal without changing employee net`() {
        val without = NetSalaryEngineV2.calculate(2500.0, 2026, company(0.0, 0.0))
        val withRates = NetSalaryEngineV2.calculate(2500.0, 2026, company(0.04, 0.0025))

        assertEquals(100.0, withRates.employerUnemploymentContribution!!, 0.001)
        assertEquals(6.25, withRates.employerAgsContribution!!, 0.001)
        assertEquals(106.25, withRates.knownEmployerContributions - without.knownEmployerContributions, 0.001)
        assertEquals(without.netBeforeIncomeTax, withRates.netBeforeIncomeTax, 0.001)
        assertEquals(without.netTaxable!!, withRates.netTaxable!!, 0.001)
        assertEquals(without.netAfterIncomeTax!!, withRates.netAfterIncomeTax!!, 0.001)
        assertTrue(withRates.employerCostWarnings.none {
            it.contains("Chômage/AGS employeur", ignoreCase = true) &&
                it.contains("taux confirmés manquants", ignoreCase = true)
        })
    }
}
