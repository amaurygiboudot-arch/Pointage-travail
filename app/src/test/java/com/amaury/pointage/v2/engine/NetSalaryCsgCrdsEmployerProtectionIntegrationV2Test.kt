package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class NetSalaryCsgCrdsEmployerProtectionIntegrationV2Test {
    private fun company(csgCrdsEmployerProtection: Double?) = CompanyPayrollOverridesV2.Snapshot(
        companyId = "company",
        idcc = null,
        referenceDate = LocalDate.of(2026, 9, 30),
        entryDate = LocalDate.of(2020, 1, 1),
        seniorityMonths = 80,
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
        incomeTaxRate = 0.0,
        professionalStatus = "NON_CADRE",
        protectionCategory = PlasturgieProtectionCategoryV2.classify(null, LocalDate.of(2026, 9, 30), null),
        warnings = emptyList(),
        alsaceMoselleLocalRegime = false,
        employerMobilityRate = 0.0,
        employerProtectionCsgCrdsBaseAmount = csgCrdsEmployerProtection
    )

    @Test
    fun `confirmed employer protection reaches CSG CRDS through net engine`() {
        val zero = NetSalaryEngineV2.calculate(3000.0, 2026, company(0.0))
        val fifty = NetSalaryEngineV2.calculate(3000.0, 2026, company(50.0))

        assertEquals(4.85, fifty.statutory - zero.statutory, 0.001)
        assertEquals(4.85, zero.netBeforeIncomeTax - fifty.netBeforeIncomeTax, 0.001)
    }
}
