package com.amaury.pointage.v2.engine

/** Couche 5/6 — assemblage canonique brut -> retenues connues -> net imposable -> PAS -> net estimé. */
object NetSalaryEngineV2 {
    data class Result(
        val gross: Double,
        val statutory: Double,
        val complementaryRetirement: Double,
        val companyEmployeeDeductions: Double,
        val employerStatusContributions: Double,
        val netBeforeIncomeTax: Double,
        val netTaxable: Double?,
        val incomeTax: Double?,
        val netAfterIncomeTax: Double?,
        val complete: Boolean,
        val warnings: List<String>
    )

    fun calculate(gross: Double, year: Int, company: CompanyPayrollOverridesV2.Snapshot): Result {
        val statutory = SocialContributionCatalogV2.estimateEmployeeDeductions(gross, year)
        val retirement = ComplementaryRetirementCatalogV2.estimate(gross, year, company.professionalStatus)
        val statusContributions = ProfessionalStatusContributionCatalogV2.estimate(gross, year, company.professionalStatus)
        val companyKnown = listOf(
            company.mutualEmployeeAmount,
            company.providentEmployeeAmount,
            company.transportEmployeeAmount
        ).filterNotNull().sum()

        val beforeTax = (gross - statutory.employeeDeductions - retirement.employeeDeductions - companyKnown)
            .coerceAtLeast(0.0)

        val nonDeductibleCsgCrds = statutory.lines
            .filter { it.id == "csg_taxable" || it.id == "crds" }
            .sumOf { it.employeeAmount }

        val taxableCompanyDataComplete = company.mutualEmployeeAmount != null &&
            company.providentEmployeeAmount != null &&
            company.transportEmployeeAmount != null

        val netTaxable = if (taxableCompanyDataComplete) {
            (beforeTax + nonDeductibleCsgCrds).coerceAtLeast(0.0)
        } else null

        val tax = if (netTaxable != null && company.incomeTaxRate != null) {
            netTaxable * company.incomeTaxRate
        } else null

        val warnings = buildList {
            addAll(statutory.warnings)
            addAll(retirement.warnings)
            addAll(statusContributions.warnings)
            addAll(company.warnings)
            if (!taxableCompanyDataComplete) add("Net imposable/PAS : données entreprise incomplètes, aucun montant fiscal n'est inventé.")
            if (company.incomeTaxRate == null) add("PAS : taux personnel non renseigné.")
        }.distinct()

        return Result(
            gross = gross,
            statutory = statutory.employeeDeductions,
            complementaryRetirement = retirement.employeeDeductions,
            companyEmployeeDeductions = companyKnown,
            employerStatusContributions = statusContributions.employerContributions,
            netBeforeIncomeTax = beforeTax,
            netTaxable = netTaxable,
            incomeTax = tax,
            netAfterIncomeTax = tax?.let { (beforeTax - it).coerceAtLeast(0.0) },
            complete = warnings.isEmpty(),
            warnings = warnings
        )
    }
}
