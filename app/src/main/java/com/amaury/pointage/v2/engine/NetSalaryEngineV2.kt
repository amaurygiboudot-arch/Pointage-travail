package com.amaury.pointage.v2.engine

/** Couche 5/6 — assemblage canonique brut -> retenues connues -> net imposable -> PAS -> net estimé. */
object NetSalaryEngineV2 {
    data class Result(
        val gross: Double,
        val socialSecurityCeiling: Double?,
        val socialSecurityCeilingComplete: Boolean,
        val statutory: Double,
        val complementaryRetirement: Double,
        val conventionProvidentEmployee: Double,
        val conventionProvidentEmployer: Double,
        val companyEmployeeDeductions: Double,
        val employerStatusContributions: Double,
        val employerAtMpContribution: Double?,
        val netBeforeIncomeTax: Double,
        val netTaxable: Double?,
        val incomeTax: Double?,
        val netAfterIncomeTax: Double?,
        val complete: Boolean,
        val warnings: List<String>,
        val benefitsInKindDeduction: Double = 0.0,
        val employerMobilityContribution: Double? = null,
        val complementaryRetirementEmployer: Double = 0.0,
        val statutoryEmployerContributions: Double = 0.0,
        val knownEmployerContributions: Double = 0.0,
        val employerCostComplete: Boolean = false,
        val employerCostWarnings: List<String> = emptyList(),
        val employerUnemploymentContribution: Double? = null,
        val employerAgsContribution: Double? = null,
        val employerFnalContribution: Double? = null,
        val employerTrainingContribution: Double? = null,
        val employerHealthContribution: Double? = null,
        val employerFamilyContribution: Double? = null
    )

    fun calculate(
        gross: Double,
        year: Int,
        company: CompanyPayrollOverridesV2.Snapshot,
        complementaryMinutes: Int? = null
    ): Result {
        val cashGross = gross.coerceAtLeast(0.0)
        val benefitsInKind = company.benefitsInKindGross.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val contributionGross = cashGross + benefitsInKind
        val ceiling = SocialSecurityCeilingV2.calculate(
            SocialSecurityCeilingV2.Input(
                year=year,referenceDate=company.referenceDate,contractType=company.contractType,
                contractualWeeklyMinutes=company.contractualWeeklyMinutes,complementaryMinutes=complementaryMinutes,
                entryDate=company.entryDate,unpaidAbsenceDays=company.unpaidAbsenceDays,forfaitAnnualDays=company.forfaitAnnualDays
            )
        )
        val statutory = SocialContributionCatalogV2.estimateEmployeeDeductions(contributionGross,year,ceiling,company.alsaceMoselleLocalRegime)
        val retirement = ComplementaryRetirementCatalogV2.estimate(contributionGross,year,company.professionalStatus,ceiling,company.protectionCategory)
        val statusContributions = ProfessionalStatusContributionCatalogV2.estimate(contributionGross,year,company.professionalStatus,ceiling,company.protectionCategory)
        val conventionProvident = ConventionProvidentCatalogV2.estimate(contributionGross,year,company.idcc,company.protectionCategory,company.seniorityMonths,ceiling)
        val atMp = EmployerAtMpContributionV2.calculate(contributionGross, company.atMpEmployerRate)
        val mobility = EmployerMobilityContributionV2.calculate(contributionGross, company.employerMobilityRate)
        val unemploymentAgs = EmployerUnemploymentAgsV2.calculate(contributionGross,ceiling.fourTimesApplicable,company.employerUnemploymentRate,company.employerAgsRate)
        val workforce = EmployerWorkforceContributionsV2.calculate(contributionGross,ceiling.applicableMonthly,year,company.employerWorkforceBand)
        val healthFamily = EmployerHealthFamilyV2.calculate(contributionGross,company.employerHealthRate,company.employerFamilyRate)

        val effectiveProvident = company.providentEmployeeAmount ?: conventionProvident.employeeDeductions
        val outsideAni = company.protectionCategory.category == PlasturgieProtectionCategoryV2.Category.OUTSIDE_2_1_2_2 ||
            company.protectionCategory.category == PlasturgieProtectionCategoryV2.Category.EXTENSION_ELIGIBLE
        val conventionProvidentKnown = year == 2026 && company.idcc == "292" && company.protectionCategory.confirmed && outsideAni && company.seniorityMonths != null
        val companyKnown = listOfNotNull(company.mutualEmployeeAmount,effectiveProvident,company.transportEmployeeAmount).sum()

        val beforeTax = (cashGross - statutory.employeeDeductions - retirement.employeeDeductions - companyKnown).coerceAtLeast(0.0)
        val nonDeductibleCsgCrds = statutory.lines.filter { it.id == "csg_taxable" || it.id == "crds" }.sumOf { it.employeeAmount }
        val providentDataComplete = company.providentEmployeeAmount != null || conventionProvidentKnown
        val taxableCompanyDataComplete = company.mutualEmployeeAmount != null && providentDataComplete &&
            company.transportEmployeeAmount != null && company.employerProtectionTaxableAmount != null &&
            company.employeeProvidentNonDeductibleAmount != null
        val netTaxable = if (taxableCompanyDataComplete) {
            (beforeTax + benefitsInKind + nonDeductibleCsgCrds + company.employerProtectionTaxableAmount!! + company.employeeProvidentNonDeductibleAmount!!).coerceAtLeast(0.0)
        } else null
        val tax = if (netTaxable != null && company.incomeTaxRate != null) netTaxable * company.incomeTaxRate else null

        val hasMobilityWarning = company.warnings.any { it.startsWith("Versement mobilité employeur") }
        val warnings = buildList {
            addAll(ceiling.warnings);addAll(statutory.warnings);addAll(retirement.warnings);addAll(statusContributions.warnings)
            addAll(conventionProvident.warnings);addAll(atMp.warnings)
            if (!hasMobilityWarning) addAll(mobility.warnings)
            addAll(company.warnings.filterNot {
                (it.startsWith("Prévoyance salariale entreprise") && conventionProvidentKnown) ||
                    (it.startsWith("AT/MP employeur") && atMp.complete)
            })
            if (company.providentEmployeeAmount != null && conventionProvident.employeeDeductions > 0.0 && company.providentEmployeeAmount + 0.01 < conventionProvident.employeeDeductions) {
                add("Prévoyance salariale renseignée inférieure au minimum conventionnel Plasturgie calculé : vérifier le bulletin ou le régime d’entreprise.")
            }
            if (!taxableCompanyDataComplete) add("Net imposable/PAS : assiette fiscale incomplète, aucun montant fiscal n'est inventé.")
            if (company.incomeTaxRate == null) add("PAS : taux personnel non renseigné.")
        }.distinct()

        val knownEmployerContributions = listOfNotNull(
            statutory.employerContributions,retirement.employerContributions,conventionProvident.employerContributions,
            statusContributions.employerContributions,atMp.employerAmount,mobility.employerAmount,
            unemploymentAgs.totalEmployerAmount,workforce.totalEmployerAmount,healthFamily.totalEmployerAmount
        ).sum()
        val employerCostWarnings = buildList {
            add("Coût employeur total : taxe d’apprentissage, éventuelles réductions/exonérations et autres contributions patronales restent à compléter ; aucun total complet n'est affiché.")
            if (!atMp.complete) add("Coût employeur : AT/MP à confirmer pour l'établissement.")
            if (!mobility.complete) add("Coût employeur : versement mobilité à confirmer pour l'établissement et la période.")
            addAll(company.employerUnemploymentAgsWarnings);addAll(unemploymentAgs.warnings)
            addAll(company.employerWorkforceWarnings);addAll(workforce.warnings)
            addAll(company.employerHealthFamilyWarnings);addAll(healthFamily.warnings)
            if (retirement.warnings.isNotEmpty()) add("Coût employeur : retraite complémentaire susceptible de dispositions d'entreprise particulières à vérifier.")
        }.distinct()

        return Result(
            gross=contributionGross,socialSecurityCeiling=ceiling.applicableMonthly.takeIf { year == 2026 },
            socialSecurityCeilingComplete=ceiling.complete,statutory=statutory.employeeDeductions,
            complementaryRetirement=retirement.employeeDeductions,
            conventionProvidentEmployee=if(company.providentEmployeeAmount==null) conventionProvident.employeeDeductions else 0.0,
            conventionProvidentEmployer=conventionProvident.employerContributions,companyEmployeeDeductions=companyKnown,
            employerStatusContributions=statusContributions.employerContributions,employerAtMpContribution=atMp.employerAmount,
            netBeforeIncomeTax=beforeTax,netTaxable=netTaxable,incomeTax=tax,netAfterIncomeTax=tax?.let { (beforeTax-it).coerceAtLeast(0.0) },
            complete=warnings.isEmpty(),warnings=warnings,benefitsInKindDeduction=benefitsInKind,
            employerMobilityContribution=mobility.employerAmount,complementaryRetirementEmployer=retirement.employerContributions,
            statutoryEmployerContributions=statutory.employerContributions,knownEmployerContributions=knownEmployerContributions,
            employerCostComplete=false,employerCostWarnings=employerCostWarnings,
            employerUnemploymentContribution=unemploymentAgs.unemploymentAmount,employerAgsContribution=unemploymentAgs.agsAmount,
            employerFnalContribution=workforce.fnalAmount,employerTrainingContribution=workforce.trainingAmount,
            employerHealthContribution=healthFamily.healthAmount,employerFamilyContribution=healthFamily.familyAmount
        )
    }
}
