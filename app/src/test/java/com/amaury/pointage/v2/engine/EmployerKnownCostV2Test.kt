package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EmployerKnownCostV2Test {
    @Test
    fun `known employer subtotal never masquerades as complete employer cost`() {
        val company = CompanyPayrollOverridesV2.Snapshot(
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
            incomeTaxRate = 0.0,
            professionalStatus = "NON_CADRE",
            protectionCategory = PlasturgieProtectionCategoryV2.classify(null, LocalDate.of(2026, 1, 31), null),
            warnings = emptyList(),
            alsaceMoselleLocalRegime = false,
            atMpEmployerRate = 0.02,
            benefitsInKindGross = 0.0,
            employerMobilityRate = 0.01
        )

        val result = NetSalaryEngineV2.calculate(2500.0, 2026, company)
        val expected = result.statutoryEmployerContributions +
            result.complementaryRetirementEmployer +
            result.conventionProvidentEmployer +
            result.employerStatusContributions +
            (result.employerAtMpContribution ?: 0.0) +
            (result.employerMobilityContribution ?: 0.0)

        assertEquals(expected, result.knownEmployerContributions, 0.001)
        assertTrue(result.knownEmployerContributions > 0.0)
        assertFalse(result.employerCostComplete)
        assertTrue(result.employerCostWarnings.any { it.contains("aucun total complet", ignoreCase = true) })
    }
}
