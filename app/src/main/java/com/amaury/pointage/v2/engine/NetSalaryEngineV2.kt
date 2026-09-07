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
        /** Sous-total patronal sûr ; ce n'est pas encore le coût employeur complet. */
        val employerStatutoryKnownContributions: Double = 0.0
    )

    fun calculate(
        gross: Double,
        year: Int,
        company: CompanyPayrollOverridesV2.Snapshot,
        complementaryMinutes: Int? = null
    ): Result {
        val cashGross = gross.coerceAtLeast(0.0)
        val benefitsInKind = company.benefitsInKindGross
            .takeIf { it.isFinite() && it >= 0.0 }
            ?: 0.0
        val contributionGross = cashGross + benefitsInKind
        val ceiling = SocialSecurityCeilingV2.calculate(
            SocialSecurityCeilingV2.Input(
                year = year,
                referenceDate = company.referenceDate,
                contractType = company.contractType,
                contractualWeeklyMinutes = company.contractualWeeklyMinutes,
                complementaryMinutes = complementaryMinutes,
                entryDate = company.entryDate,
                unpaidAbsenceDays = company.unpaidAbsenceDays,
                forfaitAnnualDays = company.forfaitAnnualDays
            )
        )
        val statutory = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross = contributionGross,
            year = year,
            ceiling = ceiling,
            alsaceMoselleLocalRegime = company.alsaceMoselleLocalRegime
        )
        val employerStatutory = EmployerStatutoryContributionCatalogV2.estimate(
            gross = contributionGross,
            year = year,
            ceiling = ceiling
        )
        val retirement = ComplementaryRetirementCatalogV2.estimate(
            gross = contributionGross,
            year = year,
            professionalStatus = company.professionalStatus,
            ceiling = ceiling,
            protectionCategory = company.protectionCategory
        )
        val statusContributions = ProfessionalStatusContributionCatalogV2.estimate(
            gross = contributionGross,
            year = year,
            professionalStatus = company.professionalStatus,
            ceiling = ceiling,
            protectionCategory = company.protectionCategory
        )
        val conventionProvident = ConventionProvidentCatalogV2.estimate(
            gross = contributionGross,
            year = year,
            idcc = company.idcc,
            protectionCategory = company.protectionCategory,
            seniorityMonths = company.seniorityMonths,
            ceiling = ceiling
        )
        val atMp = EmployerAtMpContributionV2.calculate(contributionGross, company.atMpEmployerRate)
        val mobility = EmployerMobilityContributionV2.calculate(contributionGross, company.employerMobilityRate)

        // Une retenue réellement renseignée par l'entreprise prime sur le minimum conventionnel calculé.
        // Le minimum n'est donc jamais ajouté une seconde fois.
        val effectiveProvident = company.providentEmployeeAmount ?: conventionProvident.employeeDeductions
        val outsideAni = company.protectionCategory.category == PlasturgieProtectionCategoryV2.Category.OUTSIDE_2_1_2_2 ||
            company.protectionCategory.category == PlasturgieProtectionCategoryV2.Category.EXTENSION_ELIGIBLE
        val conventionProvidentKnown = year == 2026 && company.idcc == "292" &&
            company.protectionCategory.confirmed && outsideAni && company.seniorityMonths != null
        val companyKnown = listOfNotNull(
            company.mutualEmployeeAmount,
            effectiveProvident,
            company.transportEmployeeAmount
        ).sum()

        // Toutes les lignes patronales restent hors du net salarié. L'avantage en nature augmente
        // les assiettes ci-dessus, mais n'est pas versé en espèces : on part uniquement du brut cash.
        val beforeTax = (cashGross - statutory.employeeDeductions - retirement.employeeDeductions - companyKnown)
            .coerceAtLeast(0.0)

        val nonDeductibleCsgCrds = statutory.lines
            .filter { it.id == "csg_taxable" || it.id == "crds" }
            .sumOf { it.employeeAmount }

        val providentDataComplete = company.providentEmployeeAmount != null || conventionProvidentKnown
        val taxableCompanyDataComplete = company.mutualEmployeeAmount != null &&
            providentDataComplete &&
            company.transportEmployeeAmount != null &&
            company.employerProtectionTaxableAmount != null &&
            company.employeeProvidentNonDeductibleAmount != null

        // Même non versé en espèces, l'avantage en nature reste une rémunération imposable.
        val netTaxable = if (taxableCompanyDataComplete) {
            (
                beforeTax +
                    benefitsInKind +
                    nonDeductibleCsgCrds +
                    company.employerProtectionTaxableAmount!! +
                    company.employeeProvidentNonDeductibleAmount!!
                ).coerceAtLeast(0.0)
        } else null

        val tax = if (netTaxable != null && company.incomeTaxRate != null) {
            netTaxable * company.incomeTaxRate
        } else null

        val hasMobilityWarning = company.warnings.any { it.startsWith("Versement mobilité employeur") }
        val warnings = buildList {
            addAll(ceiling.warnings)
            addAll(statutory.warnings)
            addAll(employerStatutory.warnings)
            addAll(retirement.warnings)
            addAll(statusContributions.warnings)
            addAll(conventionProvident.warnings)
            addAll(atMp.warnings)
            if (!hasMobilityWarning) addAll(mobility.warnings)
            addAll(company.warnings.filterNot {
                (it.startsWith("Prévoyance salariale entreprise") && conventionProvidentKnown) ||
                    (it.startsWith("AT/MP employeur") && atMp.complete)
            })
            if (company.providentEmployeeAmount != null && conventionProvident.employeeDeductions > 0.0 &&
                company.providentEmployeeAmount + 0.01 < conventionProvident.employeeDeductions) {
                add("Prévoyance salariale renseignée inférieure au minimum conventionnel Plasturgie calculé : vérifier le bulletin ou le régime d’entreprise.")
            }
            if (!taxableCompanyDataComplete) add("Net imposable/PAS : assiette fiscale incomplète, aucun montant fiscal n'est inventé.")
            if (company.incomeTaxRate == null) add("PAS : taux personnel non renseigné.")
        }.distinct()

        return Result(
            gross = contributionGross,
            socialSecurityCeiling = ceiling.applicableMonthly.takeIf { year == 2026 },
            socialSecurityCeilingComplete = ceiling.complete,
            statutory = statutory.employeeDeductions,
            complementaryRetirement = retirement.employeeDeductions,
            conventionProvidentEmployee = if (company.providentEmployeeAmount == null) conventionProvident.employeeDeductions else 0.0,
            conventionProvidentEmployer = conventionProvident.employerContributions,
            companyEmployeeDeductions = companyKnown,
            employerStatusContributions = statusContributions.employerContributions,
            employerAtMpContribution = atMp.employerAmount,
            netBeforeIncomeTax = beforeTax,
            netTaxable = netTaxable,
            incomeTax = tax,
            netAfterIncomeTax = tax?.let { (beforeTax - it).coerceAtLeast(0.0) },
            complete = warnings.isEmpty(),
            warnings = warnings,
            benefitsInKindDeduction = benefitsInKind,
            employerMobilityContribution = mobility.employerAmount,
            complementaryRetirementEmployer = retirement.employerContributions,
            employerStatutoryKnownContributions = employerStatutory.knownEmployerContributions
        )
    }
}
