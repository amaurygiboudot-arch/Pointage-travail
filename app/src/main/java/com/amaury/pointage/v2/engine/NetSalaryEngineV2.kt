package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.V2ConventionProvidentContributionBridge

/** Couche 5/6 — assemblage canonique brut -> retenues connues -> net imposable -> PAS -> net estimé. */
object NetSalaryEngineV2 {
    /**
     * Les champs patronaux individuels historiques sont des diagnostics de calcul internes,
     * parfois incomplets : ne pas les afficher ni les sommer pour fabriquer un coût.
     * Toute restitution patronale utilise employerProjection et ses états/provenances.
     */
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
        val knownEmployerContributions: Double? = null,
        /** Reste faux tant que l'applicabilité exhaustive des contributions propres à l'entreprise n'est pas prouvée. */
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
        val knownEmployerContributionsAfterReductions: Double? = null,
        /** Brut social utilisable après confirmation des avantages en nature du mois. */
        val grossReliable: Boolean = false,
        /** Toutes les opérations monétaires salariales sont représentables au centime. */
        val employeeAmountsReliable: Boolean = false,
        /** Projection partielle issue des mêmes entrées résolues que le net. */
        val employerProjection: EmployerContributionProjectionV2.Result? = null,
        /** Original lines from this calculation; presentation must retain the reliability guards. */
        val contributionDetails: List<CalculatedContributionLineV2> = emptyList(),
        val employeeDeductionDetails: List<CalculatedEmployeeDeductionV2> = emptyList()
    )

    fun calculate(
        gross: Double,
        year: Int,
        company: CompanyPayrollOverridesV2.Snapshot,
        complementaryMinutes: Int? = null
    ): Result {
        val cashCents = nonNegativeCents(gross)
        val benefitsCents = nonNegativeCents(company.benefitsInKindGross)
        val contributionGrossCents = if (cashCents != null && benefitsCents != null) {
            PayrollMoneyV2.sum(listOf(cashCents, benefitsCents))
        } else null
        val benefitsInKind = benefitsCents?.let(PayrollMoneyV2::amount) ?: 0.0
        val contributionGross = contributionGrossCents?.let(PayrollMoneyV2::amount) ?: 0.0
        val grossReliable = company.benefitsInKindReliable &&
            cashCents != null && benefitsCents != null && contributionGrossCents != null
        val protectionCsgBase = company.employerProtectionCsgCrdsBaseAmount
            ?.let(::nonNegativeCents)?.let(PayrollMoneyV2::amount)
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
            alsaceMoselleLocalRegime = company.alsaceMoselleLocalRegime,
            employerProtectionCsgCrdsBaseAmount = protectionCsgBase
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
        // historique reste disponible. Dès qu'un chemin KALI fiable a été acquis pour ce profil,
        // il reste prioritaire même si le refresh courant devient INCOMPLETE : dans ce cas le calcul
        // se bloque, mais l'ancien barème ne ressuscite jamais. Un store KALI corrompu bloque aussi
        // explicitement ce repli, car son contenu perdu peut précisément contenir la règle applicable.
        val providentCoverage = company.verifiedProvidentCoverage
        val providentStoreReliable = company.verifiedProvidentStoreReliable
        val currentKaliCoverageClaims = providentCoverage.reliable &&
            providentCoverage.record?.authorities?.contains(ConventionMatterCoverageV2.Authority.KALI) == true &&
            providentCoverage.state in setOf(
                ConventionMatterCoverageV2.State.CONFIRMED_RULES,
                ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE
            )
        val acquiredKaliProvidentPath =
            providentCoverage.record?.hasAcquired(ConventionMatterCoverageV2.Authority.KALI) == true
        val verifiedProvidentCategoryReady = company.verifiedProtectionCategory.confirmed
        val verifiedProvidentRulesPath = currentKaliCoverageClaims && verifiedProvidentCategoryReady &&
            providentCoverage.state == ConventionMatterCoverageV2.State.CONFIRMED_RULES
        val verifiedProvidentNoRulePath = currentKaliCoverageClaims && verifiedProvidentCategoryReady &&
            providentCoverage.state == ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE
        val verifiedProvidentPath = !providentStoreReliable || acquiredKaliProvidentPath || currentKaliCoverageClaims
        val verifiedProvident = when {
            !verifiedProvidentPath -> null
            !providentStoreReliable -> blockedVerifiedProvident(
                "Prévoyance conventionnelle : stockage KALI local incohérent ; calcul bloqué et aucun ancien barème n'est réutilisé."
            )
            !currentKaliCoverageClaims -> blockedVerifiedProvident(
                "Prévoyance conventionnelle : chemin KALI déjà acquis mais dernier audit incomplet ; calcul bloqué et aucun ancien barème n'est réutilisé."
            )
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

        // Dès qu'un chemin KALI a été acquis, la règle calculable doit passer par l'arbitrage
        // ACCO/KALI. Un store ACCO vide n'est jamais interprété comme une absence d'accord : seule
        // une preuve explicite de connaissance de source peut autoriser la branche seule. Si le
        // store KALI est corrompu, l'arbitrage entier reste bloqué : ACCO ne peut pas devenir fiable
        // par simple disparition accidentelle de la branche.
        val arbitratedProvident = if (verifiedProvidentPath) {
            val profile = company.verifiedProvidentLegalProfile
            when {
                !providentStoreReliable -> ProvidentContributionPayrollResolutionV2.Result(
                    employeeAmount = null,
                    employerAmount = null,
                    reliable = false,
                    selectedSource = null,
                    warnings = (listOf(
                        "Prévoyance ACCO/KALI : stockage KALI local incohérent ; arbitrage bloqué jusqu'à réparation des preuves de branche."
                    ) + company.verifiedProvidentStoreWarnings).distinct()
                )
                profile == null || verifiedProvident == null -> {
                    ProvidentContributionPayrollResolutionV2.Result(
                        employeeAmount = null,
                        employerAmount = null,
                        reliable = false,
                        selectedSource = null,
                        warnings = listOf(
                            "Prévoyance ACCO/KALI : profil juridique local indisponible ; calcul collectif bloqué."
                        )
                    )
                }
                else -> ProvidentContributionPayrollResolutionV2.resolve(
                    profile = profile,
                    referenceDate = company.referenceDate,
                    branch = V2ConventionProvidentContributionBridge.Snapshot(
                        result = verifiedProvident,
                        coverage = providentCoverage
                    ),
                    companyRules = company.verifiedCompanyProvidentRules,
                    gross = contributionGross,
                    companyGuaranteesEquivalent = company.verifiedCompanyProvidentGuaranteesEquivalent,
                    sourceKnowledge = company.verifiedProvidentSourceKnowledge
                )
            }
        } else null

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

        val calculatedProvidentEmployee: Double? = when {
            verifiedProvidentPath && arbitratedProvident?.reliable == true -> arbitratedProvident.employeeAmount
            verifiedProvidentPath -> null
            legacyConventionProvidentKnown -> legacyConventionProvident.employeeDeductions
            else -> null
        }
        val calculatedProvidentEmployer: Double? = when {
            verifiedProvidentPath && arbitratedProvident?.reliable == true -> arbitratedProvident.employerAmount
            verifiedProvidentPath -> null
            legacyConventionProvidentKnown -> legacyConventionProvident.employerContributions
            else -> null
        }
        val activeProvidentWarnings = if (verifiedProvidentPath) {
            (
                verifiedProvident?.warnings.orEmpty() +
                    arbitratedProvident?.warnings.orEmpty() +
                    company.verifiedProvidentStoreWarnings
                ).distinct()
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
            band = company.employerWorkforceBand,
            fnalTreatment = company.employerFnalTreatment,
            trainingTreatment = company.employerTrainingTreatment
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

        // Une retenue réellement renseignée par l'entreprise prime toujours sur la règle collective
        // calculée. Le moteur juridique sert à contrôler/compléter, jamais à doubler un prélèvement
        // déjà connu sur le bulletin.
        val effectiveProvident = company.providentEmployeeAmount ?: calculatedProvidentEmployee
        val directAmounts = listOf(
            company.mutualEmployeeAmount,
            effectiveProvident,
            company.transportEmployeeAmount
        )
        val directAmountsValid = directAmounts.all { it == null || nonNegativeCents(it) != null }
        val companyKnownCents = if (directAmountsValid) {
            PayrollMoneyV2.sum(directAmounts.mapNotNull { it?.let(::nonNegativeCents) })
        } else null
        val statutoryLineCents = statutory.lines.map { PayrollMoneyV2.contribution(it.baseAmount, it.rate) }
        val retirementLineCents = retirement.lines.map { PayrollMoneyV2.contribution(it.baseAmount, it.employeeRate) }
        val statutoryCents = sumKnownLines(statutoryLineCents)
        val retirementCents = sumKnownLines(retirementLineCents)
        val companyKnown = companyKnownCents?.let(PayrollMoneyV2::amount) ?: 0.0

        // Toutes les contributions patronales restent hors net salarié.
        // L'avantage en nature augmente les assiettes, mais n'est pas versé en espèces.
        val rawBeforeCents = if (cashCents != null && statutoryCents != null &&
            retirementCents != null && companyKnownCents != null
        ) PayrollMoneyV2.sum(listOf(cashCents, -statutoryCents, -retirementCents, -companyKnownCents)) else null
        val employeeAmountsReliable = contributionGrossCents != null && rawBeforeCents != null &&
            (company.employerProtectionCsgCrdsBaseAmount == null || protectionCsgBase != null)
        val beforeCents = rawBeforeCents?.coerceAtLeast(0L)
        val beforeTax = beforeCents?.let(PayrollMoneyV2::amount) ?: 0.0

        // Réintégrer exactement les centimes déjà retranchés, pas un second calcul brut.
        val nonDeductibleCsgCrds = sumKnownLines(statutory.lines.mapIndexedNotNull { index, line ->
            if (line.id == "csg_taxable" || line.id == "crds") index else null
        }.map { statutoryLineCents[it] })

        // Un barème collectif prouve une obligation juridique, pas l'absence d'une retenue réelle
        // différente sur le bulletin. Tant que le montant réellement prélevé par l'entreprise n'est
        // pas connu, la partie fiscale reste volontairement incomplète.
        val providentDataComplete = company.providentEmployeeAmount?.let(::nonNegativeCents) != null
        val employerTaxableCents = company.employerProtectionTaxableAmount?.let(::nonNegativeCents)
        val employeeNonDeductibleCents = company.employeeProvidentNonDeductibleAmount?.let(::nonNegativeCents)
        val transportCents = company.transportEmployeeAmount?.let(::nonNegativeCents)
        val taxableCompanyDataComplete = company.mutualEmployeeAmount?.let(::nonNegativeCents) != null &&
            providentDataComplete &&
            transportCents != null &&
            employerTaxableCents != null && employeeNonDeductibleCents != null

        // Même non versé en espèces, l'avantage en nature reste une rémunération imposable.
        val rawTaxableCents = if (employeeAmountsReliable && taxableCompanyDataComplete &&
            rawBeforeCents != null && benefitsCents != null && nonDeductibleCsgCrds != null
        ) {
            PayrollMoneyV2.sum(listOf(rawBeforeCents, benefitsCents, nonDeductibleCsgCrds,
                employerTaxableCents!!, employeeNonDeductibleCents!!))
        } else null
        // Une retenue transport positive ne caractérise pas sa déductibilité fiscale.
        val transportTaxTreatmentKnown = transportCents != null && company.transportEmployeeAmount == 0.0
        val taxableCents = rawTaxableCents?.takeIf { it >= 0L && transportTaxTreatmentKnown }
        val netTaxable = taxableCents?.let(PayrollMoneyV2::amount)
        val validTaxRate = company.incomeTaxRate?.takeIf { it.isFinite() && it in 0.0..1.0 }
        val theoreticalTaxCents = if (taxableCents != null && validTaxRate != null) {
            PayrollMoneyV2.tax(taxableCents, validTaxRate)
        } else null
        // Ne pas choisir implicitement la tolérance de non-prélèvement de l'employeur.
        val taxCents = theoreticalTaxCents?.takeIf { beforeCents != null && it <= beforeCents }
        val tax = taxCents?.let(PayrollMoneyV2::amount)
        val afterCents = if (beforeCents != null && taxCents != null) {
            PayrollMoneyV2.sum(listOf(beforeCents, -taxCents))
        } else null

        val employerOnlyCompanyWarnings = company.warnings.filter {
            it.startsWith("AT/MP employeur") || it.startsWith("Versement mobilité employeur")
        }
        val warnings = buildList {
            if (!grossReliable) {
                add("Brut social : salaire ou avantages en nature du mois non confirmables ; seuls les éléments connus sont calculés, aucun total fiable n'est disponible.")
            }
            if (!employeeAmountsReliable) add("Net salarié : entrée ou calcul monétaire invalide ou non représentable au centime ; aucun net fiable n'est disponible.")
            if (rawBeforeCents != null && rawBeforeCents < 0L) add("Solde en espèces négatif avant écrêtement : le montant payable affiché est nul ; une dette ou régularisation reste à confirmer.")
            addAll(ceiling.warnings)
            addAll(statutory.warnings)
            addAll(retirement.warnings)
            addAll(activeProvidentWarnings)
            addAll(company.warnings.filterNot {
                it.startsWith("Prévoyance salariale entreprise") ||
                    it.startsWith("AT/MP employeur") ||
                    it.startsWith("Versement mobilité employeur")
            })
            if (company.providentEmployeeAmount == null) {
                add("Prévoyance salariale entreprise : montant réel à confirmer ; une règle collective calculable ne prouve pas à elle seule la retenue réellement pratiquée sur le bulletin.")
            }
            if (company.providentEmployeeAmount != null && calculatedProvidentEmployee != null &&
                calculatedProvidentEmployee > 0.0 &&
                company.providentEmployeeAmount + 0.01 < calculatedProvidentEmployee) {
                add("Prévoyance salariale renseignée inférieure à la règle collective calculée : vérifier le bulletin ou le régime d’entreprise.")
            }
            if (!taxableCompanyDataComplete) add("Net imposable/PAS : assiette fiscale incomplète, aucun montant fiscal n'est inventé.")
            if (transportCents != null && company.transportEmployeeAmount!! > 0.0) add("Net imposable/PAS : régime fiscal de la retenue transport à confirmer.")
            if (rawTaxableCents != null && rawTaxableCents < 0L) add("Net imposable/PAS : rémunération nette fiscale négative ; régularisation non couverte, aucun zéro fiscal n'est inventé.")
            if (employeeAmountsReliable && taxableCompanyDataComplete && rawTaxableCents == null) add("Net imposable/PAS : calcul fiscal non représentable au centime.")
            if (validTaxRate == null) add("PAS : taux personnel non renseigné ou invalide.")
            if (theoreticalTaxCents != null && beforeCents != null && theoreticalTaxCents > beforeCents) add("PAS supérieur aux espèces disponibles : traitement employeur à confirmer ; PAS et net après impôt non affichés.")
            if (taxableCents != null && validTaxRate != null && theoreticalTaxCents == null) add("PAS : calcul non représentable au centime.")
            if (taxCents != null && afterCents == null) add("Net après impôt : calcul non représentable au centime.")
        }.distinct()

        fun sumContributions(values: List<Long?>): Long? =
            if (values.any { it == null }) null else PayrollMoneyV2.sum(values.filterNotNull())
        fun familyLine(
            family: EmployerContributionProjectionV2.Family, cents: Long?, source: String?,
            available: Boolean, invalid: Boolean = false
        ) = EmployerContributionProjectionV2.line(family, cents, source, available, invalid)
        fun invalidRate(rate: Double?): Boolean = rate != null && (!rate.isFinite() || rate !in 0.0..1.0)
        fun amountCents(amount: Double?): Long? = amount?.takeIf { it >= 0.0 }?.let(PayrollMoneyV2::cents)
        val knownStatus = company.professionalStatus?.trim()?.uppercase() in setOf("CADRE", "NON_CADRE")
        val aniKnown = !company.verifiedProtectionCategory.conventionControlsAni || company.verifiedProtectionCategory.confirmed
        val statutoryEmployerLineCents = statutory.lines.map {
            PayrollMoneyV2.contribution(it.baseAmount, it.employerRate)
        }
        val retirementEmployerLineCents = retirement.lines.map {
            PayrollMoneyV2.contribution(it.baseAmount, it.employerRate)
        }
        val statutoryEmployerCents = sumContributions(statutoryEmployerLineCents)
        val retirementEmployerCents = sumContributions(retirementEmployerLineCents)
        // Retain the exact checked cents used by the projection, not raw binary catalog amounts.
        val atMpEmployerCents = company.atMpEmployerRate?.let { PayrollMoneyV2.contribution(contributionGross, it) }
        val mobilityEmployerCents = company.employerMobilityRate?.let { PayrollMoneyV2.contribution(contributionGross, it) }
        val unemploymentEmployerCents = unemploymentAgs.baseAmount?.let { base -> company.employerUnemploymentRate?.let { PayrollMoneyV2.contribution(base, it) } }
        val agsEmployerCents = unemploymentAgs.baseAmount?.let { base -> company.employerAgsRate?.let { PayrollMoneyV2.contribution(base, it) } }
        val apprenticeshipPrincipalCents = company.employerApprenticeshipPrincipalRate?.let { PayrollMoneyV2.contribution(contributionGross, it) }
        val apprenticeshipBalanceCents = company.employerApprenticeshipBalanceRate?.let { PayrollMoneyV2.contribution(contributionGross, it) }
        val statusEmployerCents = sumContributions(statusContributions.lines.map {
            PayrollMoneyV2.contribution(it.baseAmount, it.employerRate)
        })
        val employerProjection = EmployerContributionProjectionV2.assemble(
            context = EmployerContributionProjectionV2.Context(
                companyId = company.companyId, year = year, month = company.referenceDate.monthValue,
                contributionGrossCents = contributionGrossCents,
                reliable = grossReliable && year == company.referenceDate.year
            ),
            lines = listOf(
                familyLine(EmployerContributionProjectionV2.Family.STATUTORY, statutoryEmployerCents,
                    statutory.lines.map { it.source }.distinct().joinToString(" | "),
                    statutory.lines.isNotEmpty() && ceiling.complete),
                familyLine(EmployerContributionProjectionV2.Family.COMPLEMENTARY_RETIREMENT, retirementEmployerCents,
                    retirement.lines.map { it.source }.distinct().joinToString(" | "),
                    retirement.lines.isNotEmpty() && ceiling.complete),
                familyLine(EmployerContributionProjectionV2.Family.CONVENTION_PROVIDENT, amountCents(calculatedProvidentEmployer),
                    if (verifiedProvidentPath) arbitratedProvident?.selectedSource?.let { "Arbitrage prévoyance : $it" }
                    else legacyConventionProvident.lines.map { it.source }.distinct().joinToString(" | "),
                    calculatedProvidentEmployer != null),
                familyLine(EmployerContributionProjectionV2.Family.STATUS, statusEmployerCents,
                    statusContributions.lines.map { it.source }.distinct().joinToString(" | ")
                        .ifBlank { "Statut professionnel et catégorie ANI confirmés ; catalogue existant $year" },
                    year == 2026 && ceiling.complete && knownStatus && aniKnown),
                familyLine(EmployerContributionProjectionV2.Family.AT_MP,
                    atMpEmployerCents,
                    company.atMpEmployerSource, atMp.complete, invalidRate(company.atMpEmployerRate)),
                familyLine(EmployerContributionProjectionV2.Family.MOBILITY,
                    mobilityEmployerCents,
                    company.employerMobilitySource, mobility.complete, invalidRate(company.employerMobilityRate)),
                familyLine(EmployerContributionProjectionV2.Family.UNEMPLOYMENT_AGS,
                    sumContributions(listOf(unemploymentEmployerCents, agsEmployerCents)),
                    company.employerUnemploymentAgsSource, unemploymentAgs.complete,
                    invalidRate(company.employerUnemploymentRate) || invalidRate(company.employerAgsRate)),
                familyLine(EmployerContributionProjectionV2.Family.WORKFORCE,
                    sumContributions(listOf(amountCents(workforce.fnalAmount), amountCents(workforce.trainingAmount))),
                    company.employerWorkforceSource, workforce.complete),
                familyLine(EmployerContributionProjectionV2.Family.HEALTH_FAMILY, amountCents(healthFamily.totalEmployerAmount),
                    company.employerHealthFamilySource, healthFamily.complete,
                    invalidRate(company.employerHealthRate) || invalidRate(company.employerFamilyRate) ||
                        (company.employerHealthRate != null && company.employerFamilyRate != null && !healthFamily.complete)),
                familyLine(EmployerContributionProjectionV2.Family.APPRENTICESHIP,
                    sumContributions(listOf(apprenticeshipPrincipalCents, apprenticeshipBalanceCents)),
                    company.employerApprenticeshipSource, apprenticeship.complete,
                    invalidRate(company.employerApprenticeshipPrincipalRate) || invalidRate(company.employerApprenticeshipBalanceRate))
            ),
            reductions = EmployerContributionProjectionV2.reduction(company.employerReductionAmount, company.employerReductionSource),
            additionalWarnings = retirement.warnings.map { "Retraite patronale — couverture partielle : $it" }
        )
        val knownEmployerContributions = employerProjection.knownSubtotalBeforeReductions
        val reductionAmount = employerProjection.reductions.amountCents?.let(PayrollMoneyV2::amount)
        val reductionsFitKnownSubtotal = reductionAmount == null || (knownEmployerContributions != null &&
            employerProjection.reductions.amountCents!! <= employerProjection.knownSubtotalBeforeReductionsCents!!)
        val knownAfterReductions = employerProjection.knownSubtotalAfterReductions
        val employerCostWarnings = buildList {
            addAll(employerProjection.warnings)
            add("Coût employeur total : d’éventuelles contributions patronales spécifiques restent à confirmer ; aucun total complet n'est affiché.")
            add("Périmètre du coût salarial estimé : brut social + contributions patronales − réductions ; il ne représente pas toutes les dépenses de l'entreprise. Les sous-totaux affichés portent uniquement sur les contributions connues.")
            add("Couverture patronale à prouver pour l'entreprise et le mois : part patronale de mutuelle, régimes supplémentaires d'entreprise et contributions spécifiques ; une absence de ligne calculée ne prouve pas leur non-applicabilité.")
            if (!grossReliable) add("Coût employeur : brut social et avantages en nature du mois à confirmer.")
            if (reductionAmount == null) add("Coût employeur : total des réductions/exonérations du mois à confirmer, y compris leur absence éventuelle ; aucun zéro n'est supposé.")
            addAll(statusContributions.warnings)
            addAll(atMp.warnings)
            addAll(mobility.warnings)
            addAll(employerOnlyCompanyWarnings)
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
            if (verifiedProvidentPath && arbitratedProvident?.reliable != true) {
                add("Coût employeur : cotisation collective de prévoyance ACCO/KALI non calculable avec les preuves disponibles.")
            }
            if (!reductionsFitKnownSubtotal) {
                add("Réductions/exonérations patronales : le montant confirmé dépasse les cotisations actuellement connues ; le sous-total après réductions n'est pas affiché tant que les contributions manquantes ne sont pas identifiées.")
            }
            if (retirement.warnings.isNotEmpty()) add("Coût employeur : retraite complémentaire susceptible de dispositions d'entreprise particulières à vérifier.")
        }.distinct()

        return Result(
            gross = contributionGross,
            grossReliable = grossReliable,
            socialSecurityCeiling = ceiling.applicableMonthly.takeIf { year == 2026 },
            socialSecurityCeilingComplete = ceiling.complete,
            statutory = statutoryCents?.let(PayrollMoneyV2::amount) ?: 0.0,
            complementaryRetirement = retirementCents?.let(PayrollMoneyV2::amount) ?: 0.0,
            conventionProvidentEmployee = if (company.providentEmployeeAmount == null) calculatedProvidentEmployee ?: 0.0 else 0.0,
            conventionProvidentEmployer = calculatedProvidentEmployer ?: 0.0,
            companyEmployeeDeductions = companyKnown,
            employerStatusContributions = statusContributions.employerContributions,
            employerAtMpContribution = atMp.employerAmount,
            netBeforeIncomeTax = beforeTax,
            netTaxable = netTaxable,
            incomeTax = tax,
            netAfterIncomeTax = afterCents?.let(PayrollMoneyV2::amount),
            complete = warnings.isEmpty(),
            warnings = warnings,
            benefitsInKindDeduction = benefitsInKind,
            employerMobilityContribution = mobility.employerAmount,
            complementaryRetirementEmployer = retirementEmployerCents?.let(PayrollMoneyV2::amount) ?: retirement.employerContributions,
            statutoryEmployerContributions = statutoryEmployerCents?.let(PayrollMoneyV2::amount) ?: statutory.employerContributions,
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
            knownEmployerContributionsAfterReductions = knownAfterReductions,
            employeeAmountsReliable = employeeAmountsReliable,
            employerProjection = employerProjection,
            employeeDeductionDetails = listOf(
                CalculatedEmployeeDeductionV2("mutual", "Mutuelle — part salariale", company.mutualEmployeeAmount),
                CalculatedEmployeeDeductionV2("provident", "Prévoyance — part salariale appliquée", effectiveProvident),
                CalculatedEmployeeDeductionV2("transport-deduction", "Transport — retenue salariale", company.transportEmployeeAmount)
            ),
            contributionDetails = statutory.lines.mapIndexed { index, line ->
                CalculatedContributionLineV2("statutory:${line.id}", line.label,
                    EmployerContributionProjectionV2.Family.STATUTORY, line.baseAmount,
                    line.rate, statutoryLineCents[index]?.let(PayrollMoneyV2::amount), line.employerRate,
                    statutoryEmployerLineCents[index]?.let(PayrollMoneyV2::amount), line.source)
            } + retirement.lines.mapIndexed { index, line ->
                CalculatedContributionLineV2("retirement:${line.id}", line.label,
                    EmployerContributionProjectionV2.Family.COMPLEMENTARY_RETIREMENT, line.baseAmount,
                    line.employeeRate, retirementLineCents[index]?.let(PayrollMoneyV2::amount), line.employerRate,
                    retirementEmployerLineCents[index]?.let(PayrollMoneyV2::amount), line.source)
            } + listOfNotNull(
                company.atMpEmployerSource?.let { CalculatedContributionLineV2("at-mp", "Accidents du travail / maladies professionnelles", EmployerContributionProjectionV2.Family.AT_MP,
                    contributionGross, null, null, company.atMpEmployerRate, atMpEmployerCents?.let(PayrollMoneyV2::amount), it) },
                company.employerMobilitySource?.let { CalculatedContributionLineV2("mobility", "Versement mobilité", EmployerContributionProjectionV2.Family.MOBILITY,
                    contributionGross, null, null, company.employerMobilityRate, mobilityEmployerCents?.let(PayrollMoneyV2::amount), it) },
                company.employerUnemploymentAgsSource?.let { source -> unemploymentAgs.baseAmount?.let { base ->
                    CalculatedContributionLineV2("unemployment", "Assurance chômage", EmployerContributionProjectionV2.Family.UNEMPLOYMENT_AGS,
                        base, null, null, company.employerUnemploymentRate, unemploymentEmployerCents?.let(PayrollMoneyV2::amount), source) } },
                company.employerUnemploymentAgsSource?.let { source -> unemploymentAgs.baseAmount?.let { base ->
                    CalculatedContributionLineV2("ags", "AGS", EmployerContributionProjectionV2.Family.UNEMPLOYMENT_AGS,
                        base, null, null, company.employerAgsRate, agsEmployerCents?.let(PayrollMoneyV2::amount), source) } },
                company.employerHealthFamilySource?.let { CalculatedContributionLineV2("employer-health", "Maladie — employeur", EmployerContributionProjectionV2.Family.HEALTH_FAMILY,
                    contributionGross, null, null, company.employerHealthRate, healthFamily.healthAmount, it) },
                company.employerHealthFamilySource?.let { CalculatedContributionLineV2("family", "Famille", EmployerContributionProjectionV2.Family.HEALTH_FAMILY,
                    contributionGross, null, null, company.employerFamilyRate, healthFamily.familyAmount, it) },
                company.employerApprenticeshipSource?.let { CalculatedContributionLineV2("apprenticeship-principal", "Apprentissage — part principale", EmployerContributionProjectionV2.Family.APPRENTICESHIP,
                    contributionGross, null, null, company.employerApprenticeshipPrincipalRate, apprenticeshipPrincipalCents?.let(PayrollMoneyV2::amount), it) },
                company.employerApprenticeshipSource?.let { CalculatedContributionLineV2("apprenticeship-balance", "Apprentissage — provision mensuelle du solde", EmployerContributionProjectionV2.Family.APPRENTICESHIP,
                    contributionGross, null, null, company.employerApprenticeshipBalanceRate, apprenticeshipBalanceCents?.let(PayrollMoneyV2::amount), it) }
            )
        )
    }

    private fun nonNegativeCents(value: Double): Long? =
        value.takeIf { it.isFinite() && it >= 0.0 }?.let(PayrollMoneyV2::cents)

    private fun sumKnownLines(values: List<Long?>): Long? =
        if (values.any { it == null }) null else PayrollMoneyV2.sum(values.filterNotNull())

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
