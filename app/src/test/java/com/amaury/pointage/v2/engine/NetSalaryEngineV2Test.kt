package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate

class NetSalaryEngineV2Test {
    private fun snapshot(
        employerProtection: Double?,
        employeeNonDeductible: Double?
    ) = CompanyPayrollOverridesV2.Snapshot(
        companyId="company",
        idcc=null,
        entryDate=LocalDate.of(2020,1,1),
        seniorityMonths=72,
        mealAmount=0.0,
        mutualEmployeeAmount=0.0,
        providentEmployeeAmount=0.0,
        transportEmployeeAmount=0.0,
        employerProtectionTaxableAmount=employerProtection,
        employeeProvidentNonDeductibleAmount=employeeNonDeductible,
        incomeTaxRate=0.05,
        professionalStatus="NON_CADRE",
        warnings=emptyList()
    )

    @Test
    fun taxableNetStaysUnknownWhenEmployerProtectionIsUnknown() {
        val result=NetSalaryEngineV2.calculate(2500.0,2026,snapshot(null,0.0))
        assertNull(result.netTaxable)
        assertNull(result.incomeTax)
        assertNull(result.netAfterIncomeTax)
    }

    @Test
    fun employerAndEmployeeNonDeductibleProtectionAreReintegrated() {
        val base=NetSalaryEngineV2.calculate(2500.0,2026,snapshot(0.0,0.0))
        val enriched=NetSalaryEngineV2.calculate(2500.0,2026,snapshot(52.0,3.0))

        assertNotNull(base.netTaxable)
        assertNotNull(enriched.netTaxable)
        assertEquals(55.0,enriched.netTaxable!!-base.netTaxable!!,0.001)
        assertEquals(enriched.netTaxable!!*0.05,enriched.incomeTax!!,0.001)
    }
}
