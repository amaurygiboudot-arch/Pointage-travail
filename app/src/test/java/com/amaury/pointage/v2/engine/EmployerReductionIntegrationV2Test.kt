package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class EmployerReductionIntegrationV2Test {
    private fun company(reduction:Double?)=CompanyPayrollOverridesV2.Snapshot(
        companyId="company",idcc=null,referenceDate=LocalDate.of(2026,9,30),entryDate=LocalDate.of(2020,1,1),
        seniorityMonths=80,contractType=ContractTypeV2.FULL_TIME,contractualWeeklyMinutes=35*60,
        forfaitAnnualDays=null,unpaidAbsenceDays=0,hasUnpaidAbsence=false,mealAmount=0.0,
        mutualEmployeeAmount=0.0,providentEmployeeAmount=0.0,transportEmployeeAmount=0.0,
        employerProtectionTaxableAmount=0.0,employeeProvidentNonDeductibleAmount=0.0,incomeTaxRate=0.05,
        professionalStatus="NON_CADRE",protectionCategory=PlasturgieProtectionCategoryV2.classify(null,LocalDate.of(2026,9,30),null),
        warnings=emptyList(),alsaceMoselleLocalRegime=false,atMpEmployerRate=0.0,benefitsInKindGross=0.0,
        employerMobilityRate=0.0,employerUnemploymentRate=0.0,employerAgsRate=0.0,employerUnemploymentAgsWarnings=emptyList(),
        employerWorkforceBand=EmployerWorkforceContributionsV2.Band.UNDER_11,employerWorkforceWarnings=emptyList(),
        employerHealthRate=0.13,employerFamilyRate=0.0525,employerHealthFamilyWarnings=emptyList(),
        employerApprenticeshipPrincipalRate=0.0059,employerApprenticeshipBalanceRate=0.0009,
        employerApprenticeshipWarnings=emptyList(),employerReductionAmount=reduction,employerReductionSource="DSN",
        employerReductionWarnings=emptyList()
    )

    @Test
    fun `confirmed employer reduction changes employer subtotal only`() {
        val zero=NetSalaryEngineV2.calculate(2500.0,2026,company(0.0))
        val reduced=NetSalaryEngineV2.calculate(2500.0,2026,company(300.0))

        assertEquals(zero.knownEmployerContributions,reduced.knownEmployerContributions,0.001)
        assertEquals(zero.knownEmployerContributions,reduced.knownEmployerContributionsAfterReductions!!+300.0,0.001)
        assertEquals(300.0,reduced.confirmedEmployerReductions!!,0.001)
        assertEquals(zero.netBeforeIncomeTax,reduced.netBeforeIncomeTax,0.001)
        assertEquals(zero.netTaxable!!,reduced.netTaxable!!,0.001)
        assertEquals(zero.netAfterIncomeTax!!,reduced.netAfterIncomeTax!!,0.001)
    }
}
