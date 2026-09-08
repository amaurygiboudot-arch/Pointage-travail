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
        /** Part patronale légale déjà intégrée dans le socle Urssaf. */
        val statutoryEmployerContributions: Double = 0.0,
        /** Sous-total des seules cotisations patronales actuellement connues du moteur, avant réductions. */
        val knownEmployerContributions: Double = 0.0,
        /** Reste faux tant que le socle patronal Urssaf de base n'est pas intégré exhaustivement. */
        val employerCostComplete: Boolean = false,
        /** Avertissements propres au coût employeur, sans dégrader la fiabilité du net salarié. */
        val employerCostWarnings: List<String> = emptyList(),
        val employerUnemploymentContribution: Double? = null,
        val employerAgsContribution: Double? = null,
        val employerFnalContribution: Double? = null,
        val employerTrainingContribution: Double? = null,
        val employerHealthContribution: Double? = null,
        val employerFamilyContribution: Double? = null,
        val employerApprenticeshipPrincipalContribution: Double? = null,
        /** Provision économique mensuelle du solde, distincte de son échéance annuelle réelle. */
        val employerApprenticeshipBalanceAccrual: Double? = null,
        /** Réductions/exonérations patronales mensuelles confirmées par une source vérifiable. */
        val confirmedEmployerReductions: Double? = null,
        /** Sous-total connu après réductions confirmées ; null si l'ajustement ne peut pas être fiabilisé. */
        val knownEmployerContributionsAfterReductions: Double? = null
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
        val retirement = ComplementaryRetirementCatalogV2.estimateGeneric(
            gross = contributionGross,
            year = year,
            professionalStatus = company.professionalStatus,
            ceiling = ceiling,
            protectionCategory = company.verifiedProtectionCategory
        )
        val statusContributions = ProfessionalStatusContributionCatalogV2.estimateGeneric(
            gross = contributionGross,
            year = year,
            professionalStatus = company.professionalStatus,
            ceiling = ceiling,
            protectionCategory = company.verifiedProtectionCategory
        )

        // Phase de migration : tant qu'aucune couverture KALI n'est acquise, le repli Plasturgie
        // historique reste disponible. Dès qu'une couverture KALI fiable existe pour ce profil,
        // elle prend définitivement la priorité : si une preuve courante (ex. catégorie ANI) devient
        // insuffisante, le calcul se bloque au lieu de revenir silencieusement à l'ancien barème.
        val verifiedProvidentCoverageClaims = company.verifiedProvidentCoverage.reliable &&
            company.verifiedProvidentCoverage.record?.authorities?.contains(ConventionMatterCoverageV2.Authority.KALI) == true &&
            company.verifiedProvidentCoverage.state in setOf(
                ConventionMatterCoverageV2.State.CONFIRMED_RULES,
                ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE
            )
        val verifiedProvidentCategoryReady = company.verifiedProtectionCategory.confirmed
        val verifiedProvidentRulesPath = verifiedProvidentCoverageClaims && verifiedProvidentCategoryReady &&
            company.verifiedProvidentCoverage.state == ConventionMatterCoverageV2.State.CONFIRMED_RULES
        val verifiedProvidentNoRulePath = verifiedProvidentCoverageClaims && verifiedProvidentCategoryReady &&
            company.verifiedProvidentCoverage.state == ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE
        val verifiedProvidentPath = verifiedProvidentCoverageClaims
        val verifiedProvident = when {
            !verifiedProvidentCoverageClaims -> null
            !verifiedProvidentCategoryReady -> blockedVerifiedProvident(
                "Prévoyance conventionnelle : couverture KALI présente mais catégorie ANI actuelle non confirmée ; aucun ancien barème n'est réutilisé."
            )
            verifiedProvidentRulesPath -> ConventionProvidentContributionV2.calculate(
                rules = company.verifiedProvidentRules,
                idcc = company.idcc,
                referenceDate = company.referenceDate,
                classification = company.verifiedProvidentClassification,
                professionalStatus = company.professionalStatus,
                protectionCategory = company.verifiedProtectionCategory,
                seniorityMonths = company.verifiedProvidentSeniorityMonths,
                gross = contributionGross,
                applicableMonthlyCeiling = ceiling.applicableMonthly
            )
            verifiedProvidentNoRulePath -> confirmedNoProvidentContribution(company.idcc)
            else -> blockedVerifiedProvident(
                "Prévoyance conventionnelle : couverture KALI non exploitable pour le profil courant."
            )
        }
        val legacyConventionProvident = ConventionProvidentCatalogV2.estimate(
            gross = contributionGross,
            year = year,
            idcc = company.idcc,
            protectionCategory = company.protectionCategory,
            seniorityMonths = company.seniorityMonths,
            ceiling = ceiling
        )

        val outsideAni = company.protectionCategory.category == PlasturgieProtectionCategoryV2.Category.OUTSIDE_2_1_2_2 ||
            company.protectionCategory.category == PlasturgieProtectionCategoryV2.Category.EXTENSION_ELIGIBLE
        val legacyConventionProvidentKnown = year == 2026 && company.idcc == "292" &&
            company.protectionCategory.confirmed && outsideAni && company.seniorityMonths != null
        val verifiedConventionProvidentKnown = verifiedProvidentPath && verifiedProvident?.reliable == true

        val calculatedProvidentEmployee: Double? = when {
            verifiedProvidentPath && verifiedProvident?.reliable == true -> verifiedProvident.employeeAmount
            verifiedProvidentPath -> null
            legacyConventionProvidentKnown -> legacyConventionProvident.employeeDeductions
            else -> null
        }
        val calculatedProvidentEmployer: Double? = when {
            verifiedProvidentPath && verifiedProvident?.reliable == true -> verifiedProvident.employerAmount
            verifiedProvidentPath -> null
            legacyConventionProvidentKnown -> legacyConventionProvident.employerContributions
            else -> null
        }
        val activeProvidentWarnings = if (verifiedProvidentPath) {
            verifiedProvident?.warnings.orEmpty()
        } else {
            legacyConventionProvident.warnings
        }

        val atMp = EmployerAtMpContributionV2.calculate(contributionGross, company.atMpEmployerRate)
        val mobility = EmployerMobilityContributionV2.calculate(contributionGross, company.employerMobilityRate)
        val unemploymentAgs = EmployerUnemploymentAgsV2.calculate(
            grossSocial = contributionGross,
            fourTimesApplicableCeiling = ceiling.fourTimesApplicable,
            unemploymentRate = company.employerUnemploymentRate,
            agsRate = company.employerAgsRate
        )
        val workforce = EmployerWorkforceContributionsV2.calculate(
            grossSocial = contributionGross,
            applicableMonthlyCeiling = ceiling.applicableMonthly,
            year = year,
            band = company.employerWorkforceBand
        )
        val healthFamily = EmployerHealthFamilyV2.calculate(
            grossSocial = contributionGross,
            healthRate = company.employerHealthRate,
            familyRate = company.employerFamilyRate
        )
        val apprenticeship = EmployerApprenticeshipTaxV2.calculate(
            grossSocial = contributionGross,
            principalRate = company.employerApprenticeshipPrincipalRate,
            balanceRate = company.employerApprenticeshipBalanceRate
        )

        // Une retenue réellement renseignée par l'entreprise prime toujours sur le minimum
        // conventionnel calculé. Le minimum n'est donc jamais ajouté une seconde fois.
        val effectiveProvident = company.providentEmployeeAmount ?: calculatedProvidentEmployee
        val companyKnown = listOfNotNull(
            company.mutualEmployeeAmount,
            effectiveProvident,
            company.transportEmployeeAmount
        ).sum()

        // Toutes les contributions patronales restent hors net salarié.
        // L'avantage en nature augmente les assiettes, mais n'est pas versé en espèces.
        val beforeTax = (cashGross - statutory.employeeDeductions - retirement.employeeDeductions - companyKnown)
            .coerceAtLeast(0.0)

        val nonDeductibleCsgCrds = statutory.lines
            .filter { it.id == "csg_taxable" || it.id == "crds" }
            .sumOf { it.employeeAmount }

        val providentDataComplete = company.providentEmployeeAmount != null ||
            verifiedConventionProvidentKnown ||
            (!verifiedProvidentPath && legacyConventionProvidentKnown)
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
            addAll(retirement.warnings)
            addAll(statusContributions.warnings)
            addAll(activeProvidentWarnings)
            addAll(atMp.warnings)
            if (!hasMobilityWarning) addAll(mobility.warnings)
            addAll(company.warnings.filterNot {
                (it.startsWith("Prévoyance salariale entreprise") && providentDataComplete) ||
                    (it.startsWith("AT/MP employeur") && atMp.complete)
            })
            if (company.providentEmployeeAmount != null && calculatedProvidentEmployee != null &&
                calculatedProvidentEmployee > 0.0 &&
                company.providentEmployeeAmount + 0.01 < calculatedProvidentEmployee) {
                add("Prévoyance salariale renseignée inférieure au minimum conventionnel calculé : vérifier le bulletin ou le régime d’entreprise.")
            }
            if (!taxableCompanyDataComplete) add("Net imposable/PAS : assiette fiscale incomplète, aucun montant fiscal n'est inventé.")
            if (company.incomeTaxRate == null) add("PAS : taux personnel non renseigné.")
        }.distinct()

        val knownEmployerContributions = listOfNotNull(
            statutory.employerContributions,
            retirement.employerContributions,
            calculatedProvidentEmployer,
            statusContributions.employerContributions,
            atMp.employerAmount,
            mobility.employerAmount,
            unemploymentAgs.totalEmployerAmount,
            workforce.totalEmployerAmount,
            healthFamily.totalEmployerAmount,
            apprenticeship.totalEmployerAmount
        ).sum()
        val reductionAmount = company.employerReductionAmount
        val reductionsFitKnownSubtotal = reductionAmount == null || reductionAmount <= knownEmployerContributions + 0.01
        val knownAfterReductions = when {
            reductionAmount == null -> null
            reductionsFitKnownSubtotal -> (knownEmployerContributions - reductionAmount).coerceAtLeast(0.0)
            else -> null
        }
        val employerCostWarnings = buildList {
            add("Coût employeur total : d’éventuelles contributions patronales spécifiques restent à confirmer ; aucun total complet n'est affiché.")
            if (!atMp.complete) add("Coût employeur : AT/MP à confirmer pour l'établissement.")
            if (!mobility.complete) add("Coût employeur : versement mobilité à confirmer pour l'établissement et la période.")
            addAll(company.employerUnemploymentAgsWarnings)
            addAll(unemploymentAgs.warnings)
            addAll(company.employerWorkforceWarnings)
            addAll(workforce.warnings)
            addAll(company.employerHealthFamilyWarnings)
            addAll(healthFamily.warnings)
            addAll(company.employerApprenticeshipWarnings)
            addAll(apprenticeship.warnings)
            addAll(company.employerReductionWarnings)
            if (verifiedProvidentPath && verifiedProvident?.reliable != true) {
                add("Coût employeur : cotisation conventionnelle de prévoyance KALI non calculable avec les données disponibles.")
            }
            if (!reductionsFitKnownSubtotal) {
                add("Réductions/exonérations patronales : le montant confirmé dépasse les cotisations actuellement connues ; le sous-total après réductions n'est pas affiché tant que les contributions manquantes ne sont pas identifiées.")
            }
            if (retirement.warnings.isNotEmpty()) add("Coût employeur : retraite complémentaire susceptible de dispositions d'entreprise particulières à vérifier.")
        }.distinct()

        return Result(
            gross = contributionGross,
            socialSecurityCeiling = ceiling.applicableMonthly.takeIf { year == 2026 },
            socialSecurityCeilingComplete = ceiling.complete,
            statutory = statutory.employeeDeductions,
            complementaryRetirement = retirement.employeeDeductions,
            conventionProvidentEmployee = if (company.providentEmployeeAmount == null) calculatedProvidentEmployee ?: 0.0 else 0.0,
            conventionProvidentEmployer = calculatedProvidentEmployer ?: 0.0,
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
            statutoryEmployerContributions = statutory.employerContributions,
            knownEmployerContributions = knownEmployerContributions,
            employerCostComplete = false,
            employerCostWarnings = employerCostWarnings,
            employerUnemploymentContribution = unemploymentAgs.unemploymentAmount,
            employerAgsContribution = unemploymentAgs.agsAmount,
            employerFnalContribution = workforce.fnalAmount,
            employerTrainingContribution = workforce.trainingAmount,
            employerHealthContribution = healthFamily.healthAmount,
            employerFamilyContribution = healthFamily.familyAmount,
            employerApprenticeshipPrincipalContribution = apprenticeship.principalAmount,
            employerApprenticeshipBalanceAccrual = apprenticeship.balanceAccrualAmount,
            confirmedEmployerReductions = reductionAmount,
            knownEmployerContributionsAfterReductions = knownAfterReductions
        )
    }

    private fun confirmedNoProvidentContribution(idcc: String?) = ConventionProvidentContributionV2.Result(
        applicable = false,
        eligibilityConfirmed = true,
        reliable = true,
        selectedRule = null,
        selectedTier = null,
        lines = emptyList(),
        employeeAmount = 0.0,
        employerAmount = 0.0,
        warnings = listOf(
            "Prévoyance conventionnelle${idcc?.let { " IDCC $it" }.orEmpty()} : absence de cotisation explicitement confirmée par KALI pour ce profil et cette période."
        )
    )

    private fun blockedVerifiedProvident(reason: String) = ConventionProvidentContributionV2.Result(
        applicable = false,
        eligibilityConfirmed = false,
        reliable = false,
        selectedRule = null,
        selectedTier = null,
        lines = emptyList(),
        employeeAmount = null,
        employerAmount = null,
        warnings = listOf(reason)
    )
}
