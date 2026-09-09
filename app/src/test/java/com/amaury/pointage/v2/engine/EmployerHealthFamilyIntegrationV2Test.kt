package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class EmployerHealthFamilyIntegrationV2Test {
    private fun company(healthRate: Double?, familyRate: Double?) = CompanyPayrollOverridesV2.Snapshot(
        companyId="company", idcc=null, referenceDate=LocalDate.of(2026,1,31), entryDate=LocalDate.of(2020,1,1),
        seniorityMonths=72, contractType=ContractTypeV2.FULL_TIME, contractualWeeklyMinutes=35*60,
        forfaitAnnualDays=null, unpaidAbsenceDays=0, hasUnpaidAbsence=false,
        mutualEmployeeAmount=0.0, providentEmployeeAmount=0.0, transportEmployeeAmount=0.0,
        employerProtectionTaxableAmount=0.0, employeeProvidentNonDeductibleAmount=0.0,
        incomeTaxRate=0.05, professionalStatus="NON_CADRE",
        protectionCategory=PlasturgieProtectionCategoryV2.classify(null,LocalDate.of(2026,1,31),null), warnings=emptyList(),
        alsaceMoselleLocalRegime=false, atMpEmployerRate=0.0, benefitsInKindGross=0.0,
        employerMobilityRate=0.0, employerUnemploymentRate=0.0, employerAgsRate=0.0,
        employerUnemploymentAgsWarnings=emptyList(), employerWorkforceBand=EmployerWorkforceContributionsV2.Band.UNDER_11,
        employerWorkforceWarnings=emptyList(), employerHealthRate=healthRate, employerFamilyRate=familyRate,
        employerHealthFamilySource="DSN", employerHealthFamilyWarnings=emptyList()
    )

    @Test
    fun `health and family contributions increase employer subtotal without changing employee net`() {
        val zero=NetSalaryEngineV2.calculate(2500.0,2026,company(0.0,0.0))
        val general=NetSalaryEngineV2.calculate(2500.0,2026,company(0.13,0.0525))

        assertEquals(325.0,general.employerHealthContribution!!,0.001)
        assertEquals(131.25,general.employerFamilyContribution!!,0.001)
        assertEquals(456.25,general.knownEmployerContributions-zero.knownEmployerContributions,0.001)
        assertEquals(zero.netBeforeIncomeTax,general.netBeforeIncomeTax,0.001)
        assertEquals(zero.netTaxable!!,general.netTaxable!!,0.001)
        assertEquals(zero.netAfterIncomeTax!!,general.netAfterIncomeTax!!,0.001)
    }
}
