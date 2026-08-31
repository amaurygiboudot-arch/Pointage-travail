package com.amaury.pointage.v2.engine

/** Couche 5/6 — assemblage canonique brut -> retenues connues -> net estimé. */
object NetSalaryEngineV2 {
    data class Result(val gross:Double,val statutory:Double,val complementaryRetirement:Double,val companyEmployeeDeductions:Double,val netBeforeIncomeTax:Double,val incomeTax:Double?,val netAfterIncomeTax:Double?,val complete:Boolean,val warnings:List<String>)

    fun calculate(gross:Double,year:Int,company:CompanyPayrollOverridesV2.Snapshot):Result {
        val statutory=SocialContributionCatalogV2.estimateEmployeeDeductions(gross,year)
        val retirement=ComplementaryRetirementCatalogV2.estimate(gross,year)
        val companyKnown=listOf(company.mutualEmployeeAmount,company.providentEmployeeAmount,company.transportEmployeeAmount).filterNotNull().sum()
        val beforeTax=(gross-statutory.employeeDeductions-retirement.employeeDeductions-companyKnown).coerceAtLeast(0.0)
        val tax=company.incomeTaxRate?.let{beforeTax*it}
        val warnings=statutory.warnings+retirement.warnings+company.warnings
        return Result(gross,statutory.employeeDeductions,retirement.employeeDeductions,companyKnown,beforeTax,tax,tax?.let{(beforeTax-it).coerceAtLeast(0.0)},warnings.isEmpty(),warnings)
    }
}
