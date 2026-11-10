import Foundation

/// Assemblage canonique iOS des retenues salariales connues vers un net fiable.
///
/// Le moteur calcule les sous-totaux connus pour permettre l'audit, mais il ne publie jamais
/// un net salarié final lorsqu'une donnée qui peut changer ce net est inconnue. Une absence de
/// donnée n'est jamais assimilée à zéro.
enum EmployeeNetProjectionV2 {
    struct Input {
        let cashGross: Double
        let upstreamGrossReliable: Bool
        let benefits: CompanyBenefitInKindContractV2.Snapshot
        let year: Int
        let ceiling: SocialSecurityCeilingV2.Snapshot
        let alsaceMoselleLocalRegime: Bool?
        let professionalStatus: String?
        let protectionCategory: ProtectionCategoryV2.Result
        let companyDeductions: CompanyEmployeeDeductionResolverV2.Snapshot
        let period: CompanyEmployeeDeductionResolverV2.YearMonth
        let incomeTaxRate: CompanyIncomeTaxRateResolverV2.Snapshot?
        var companyId: String? = nil
    }

    struct Result {
        let cashGross: Double
        let contributionGross: Double
        let grossReliable: Bool
        let statutory: SocialContributionCatalogV2.Estimate
        let complementaryRetirement: ComplementaryRetirementCatalogV2.Estimate
        let companyCashDeductions: CompanyEmployeeDeductionPayrollBridgeV2.Result
        /// Montant calculable à partir des seules données connues. Il reste interne tant que
        /// `netBeforeIncomeTaxComplete` est faux.
        let knownNetBeforeIncomeTax: Double
        let netBeforeIncomeTax: Double?
        let netTaxable: Double?
        let incomeTax: Double?
        let netAfterIncomeTax: Double?
        let netBeforeIncomeTaxComplete: Bool
        let netTaxableComplete: Bool
        let warnings: [String]
        let traces: [String]
        var employerContributions: EmployerContributionProjectionV2.Result? = nil
        var benefitsInKindGross: Double? = nil
        // Exact cent values already consumed by the monetary projection; presentation never re-rounds products.
        var statutoryEmployeeCents: [Int64?] = []
        var retirementEmployeeCents: [Int64?] = []
        var statutoryEmployerCents: [Int64?] = []
        var retirementEmployerCents: [Int64?] = []
    }

    static func calculate(_ input: Input) -> Result {
        let periodMatches = input.year == input.period.year &&
            input.ceiling.period.year == input.period.year &&
            input.ceiling.period.month == input.period.month
        let cashCents = nonNegativeCents(input.cashGross)
        let benefitsCents = nonNegativeCents(input.benefits.totalGross)
        let safeCashGross = cashCents.flatMap(PayrollMoneyV2.amount) ?? 0
        let contributionCents = sumPresent([cashCents, benefitsCents])
        let contributionGross = contributionCents.flatMap(PayrollMoneyV2.amount) ?? 0
        let grossReliable = input.upstreamGrossReliable && input.benefits.reliable &&
            cashCents != nil && benefitsCents != nil && contributionCents != nil

        let employerProtectionCsgCrdsBase = confirmedSnapshotCents(
            input.companyDeductions[.employerProtectionCsgCrdsBase]
        ).flatMap(PayrollMoneyV2.amount)
        let statutory = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross: contributionGross,
            year: input.year,
            ceiling: input.ceiling,
            alsaceMoselleLocalRegime: input.alsaceMoselleLocalRegime,
            employerProtectionCsgCrdsBaseAmount: employerProtectionCsgCrdsBase
        )
        let retirement = ComplementaryRetirementCatalogV2.estimate(
            gross: contributionGross,
            year: input.year,
            professionalStatus: input.professionalStatus,
            ceiling: input.ceiling,
            protectionCategory: input.protectionCategory
        )
        let direct = CompanyEmployeeDeductionPayrollBridgeV2.resolve(
            snapshot: input.companyDeductions,
            period: input.period
        )

        // Convention de projection : chaque ligne salariée est arrondie au centime, puis
        // additionnée. Le produit est décimal ; aucun produit Double n'est arrondi après coup.
        let statutoryCents = statutory.lines.map { PayrollMoneyV2.contribution(base: $0.baseAmount, rate: $0.rate) }
        let retirementCents = retirement.lines.map { PayrollMoneyV2.contribution(base: $0.baseAmount, rate: $0.employeeRate) }
        let directCents = direct.deductions.map { nonNegativeCents($0.amount) }
        let statutoryTotal = sumPresent(statutoryCents)
        let retirementTotal = sumPresent(retirementCents)
        let directTotal = sumPresent(directCents)
        let rawBeforeCents = sumPresent([
            cashCents, statutoryTotal.map { -$0 }, retirementTotal.map { -$0 }, directTotal.map { -$0 }
        ])
        let beforeCents = rawBeforeCents.map { max(0, $0) }
        let knownBeforeTax = beforeCents.flatMap(PayrollMoneyV2.amount) ?? 0
        let monetaryComplete = contributionCents != nil && rawBeforeCents != nil

        let normalizedStatus = input.professionalStatus?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased()
        let statusComplete = normalizedStatus == "CADRE" || normalizedStatus == "NON_CADRE"
        let aniComplete = !input.protectionCategory.conventionControlsAni || input.protectionCategory.confirmed
        let supportedNationalTables = SocialSecurityCeilingV2.fullMonthly(year: input.year) != nil &&
            !SocialContributionCatalogV2.employeeRules(year: input.year).isEmpty
        let statutoryInputsComplete = input.alsaceMoselleLocalRegime != nil &&
            employerProtectionCsgCrdsBase != nil

        var blockers: [String] = []
        if !periodMatches { blockers.append("plafond SS ou année de barème hors période de paie") }
        if !supportedNationalTables { blockers.append("barèmes nationaux non intégrés pour \(input.year)") }
        if !input.upstreamGrossReliable { blockers.append("brut salarial amont incomplet") }
        if cashCents == nil { blockers.append("brut en espèces invalide ou non représentable au centime") }
        if !input.benefits.reliable || benefitsCents == nil {
            blockers.append("avantages en nature du mois non confirmés")
        }
        if !monetaryComplete { blockers.append("calcul monétaire non représentable au centime ou dépassement arithmétique") }
        if !input.ceiling.complete { blockers.append("plafond de Sécurité sociale incomplet") }
        if input.alsaceMoselleLocalRegime == nil { blockers.append("affiliation Alsace-Moselle à confirmer") }
        if employerProtectionCsgCrdsBase == nil {
            blockers.append("part employeur de protection complémentaire soumise à CSG/CRDS à confirmer")
        }
        if !direct.confirmedEmployeeDeductionsComplete {
            blockers.append("mutuelle/prévoyance/transport salariés à confirmer")
        }
        if !statusComplete { blockers.append("statut professionnel cadre/non-cadre à confirmer") }
        if !aniComplete { blockers.append("catégorie ANI conventionnelle à confirmer") }

        let beforeTaxComplete = supportedNationalTables &&
            periodMatches &&
            grossReliable &&
            monetaryComplete &&
            input.ceiling.complete &&
            statutoryInputsComplete &&
            direct.confirmedEmployeeDeductionsComplete &&
            statusComplete &&
            aniComplete

        let employerProtectionTaxable = confirmedSnapshotCents(
            input.companyDeductions[.employerProtectionTaxable]
        )
        let employeeProvidentNonDeductible = confirmedSnapshotCents(
            input.companyDeductions[.employeeProvidentNonDeductible]
        )
        let taxInputsComplete = employerProtectionTaxable != nil && employeeProvidentNonDeductible != nil
        // La seule valeur « transport salarié » ne décrit pas son régime fiscal.
        let transportTaxKnown = input.companyDeductions[.transportEmployee].amount == 0
        let nonDeductibleCsgCrds = sumPresent(zip(statutory.lines, statutoryCents).compactMap { line, cents in
            line.id == "csg_taxable" || line.id == "crds" ? .some(cents) : nil
        })
        let rawTaxableCents = sumPresent([
            rawBeforeCents, benefitsCents, nonDeductibleCsgCrds,
            employerProtectionTaxable, employeeProvidentNonDeductible
        ])
        let taxableCents: Int64? = beforeTaxComplete && taxInputsComplete && transportTaxKnown
            ? rawTaxableCents.flatMap { $0 >= 0 ? $0 : nil }
            : nil
        let taxable = taxableCents.flatMap(PayrollMoneyV2.amount)

        var warnings = input.benefits.warnings +
            input.ceiling.warnings +
            statutory.warnings +
            retirement.warnings +
            direct.warnings +
            input.protectionCategory.warnings

        if !beforeTaxComplete {
            warnings.append(
                "Net avant impôt incomplet : \(unique(blockers).joined(separator: " ; ")). Les sous-totaux connus restent calculables mais aucun net salarié final n'est affiché."
            )
        }
        if beforeTaxComplete && !taxInputsComplete {
            var taxableBlockers: [String] = []
            if employerProtectionTaxable == nil {
                taxableBlockers.append("part employeur mutuelle/prévoyance réintégrable au net imposable")
            }
            if employeeProvidentNonDeductible == nil {
                taxableBlockers.append("part salariale de prévoyance non déductible")
            }
            warnings.append(
                "Net imposable incomplet : \(taxableBlockers.joined(separator: " ; ")) à confirmer. Le net avant impôt reste utilisable."
            )
        }

        if beforeTaxComplete, let rawBeforeCents, rawBeforeCents < 0 {
            warnings.append("Solde en espèces négatif : les retenues dépassent le brut en espèces ; le montant disponible est ramené à zéro, mais une dette ou régularisation reste à confirmer.")
        }
        if beforeTaxComplete && !transportTaxKnown {
            warnings.append("Net imposable incomplet : régime fiscal de la retenue transport positive à confirmer ; aucun traitement déductible n'est présumé.")
        }
        if beforeTaxComplete && taxInputsComplete {
            if let rawTaxableCents, rawTaxableCents < 0 {
                warnings.append("Net imposable négatif : régularisation fiscale non couverte, à confirmer ; aucun zéro fiscal fiable n'est publié.")
            } else if rawTaxableCents == nil {
                warnings.append("Net imposable incomplet : calcul monétaire non représentable au centime ou dépassement arithmétique.")
            }
        }

        let withholding = EmployeeWithholdingProjectionV2.calculate(
            netBeforeIncomeTax: beforeTaxComplete ? knownBeforeTax : nil,
            netTaxable: taxable,
            incomeTaxRate: input.incomeTaxRate
        )
        warnings.append(contentsOf: withholding.warnings)

        let traces = unique(
            direct.traces + [
                "Brut social : brut en espèces + avantages en nature confirmés, normalisés au centime.",
                "Convention de projection : lignes salariales arrondies au centime HALF_UP avant totalisation ; les mêmes centimes CSG/CRDS sont réintégrés au fiscal.",
                "PAS individuel : produit décimal arrondi au centime le plus proche, demi-centime supérieur (CGI annexe III, article 39 C).",
                "Net avant impôt : brut en espèces - cotisations légales - retraite complémentaire - retenues entreprise réellement prélevées.",
                "Part salariale de prévoyance non déductible : réintégrée au net imposable sans seconde retenue sur le net en espèces."
            ]
        )

        // Same resolved gross, period and catalog objects as the employee projection.
        // Missing employer-only sources do not participate in employee-net reliability.
        let employerContext = EmployerContributionProjectionV2.Context(
            companyId: input.companyId, year: input.period.year, month: input.period.month,
            contributionGrossCents: contributionCents, reliable: grossReliable && periodMatches && input.ceiling.complete
        )
        let statutoryEmployerCents = statutory.lines.map { PayrollMoneyV2.contribution(base: $0.baseAmount, rate: $0.employerRate) }
        let retirementEmployerCents = retirement.lines.map { PayrollMoneyV2.contribution(base: $0.baseAmount, rate: $0.employerRate) }
        func employerLine(_ family: EmployerContributionProjectionV2.Family,
                          amounts: [(Double, Double, String)], cents: [Int64?], available: Bool) -> EmployerContributionProjectionV2.Line {
            guard available, !amounts.isEmpty else { return .init(family: family, state: .missing, amountCents: nil, source: nil) }
            let values = cents.compactMap { $0 }
            let total = values.count == amounts.count ? PayrollMoneyV2.sum(values) : nil
            let source = unique(amounts.map { $0.2 }).joined(separator: " ; ")
            return .init(family: family, state: total == nil ? .invalid : .known, amountCents: total, source: source)
        }
        let employer = EmployerContributionProjectionV2.calculate(context: employerContext, lines: [
            employerLine(.statutory, amounts: statutory.lines.map { ($0.baseAmount, $0.employerRate, $0.source) },
                         cents: statutoryEmployerCents, available: supportedNationalTables && input.ceiling.complete),
            employerLine(.complementaryRetirement, amounts: retirement.lines.map { ($0.baseAmount, $0.employerRate, $0.source) },
                         cents: retirementEmployerCents, available: supportedNationalTables && input.ceiling.complete)
        ], additionalWarnings: retirement.warnings.map { "Couverture patronale retraite partielle : " + $0 })

        return Result(
            cashGross: safeCashGross,
            contributionGross: contributionGross,
            grossReliable: grossReliable,
            statutory: monetaryStatutory(statutory, cents: statutoryCents, total: statutoryTotal),
            complementaryRetirement: monetaryRetirement(retirement, cents: retirementCents, total: retirementTotal),
            companyCashDeductions: monetaryDirect(direct, cents: directCents),
            knownNetBeforeIncomeTax: knownBeforeTax,
            netBeforeIncomeTax: beforeTaxComplete ? knownBeforeTax : nil,
            netTaxable: taxable,
            incomeTax: withholding.incomeTax,
            netAfterIncomeTax: withholding.netAfterIncomeTax,
            netBeforeIncomeTaxComplete: beforeTaxComplete,
            netTaxableComplete: taxable != nil,
            warnings: unique(warnings),
            traces: traces,
            employerContributions: employer,
            benefitsInKindGross: grossReliable ? benefitsCents.flatMap(PayrollMoneyV2.amount) : nil,
            statutoryEmployeeCents: statutoryCents, retirementEmployeeCents: retirementCents,
            statutoryEmployerCents: statutoryEmployerCents, retirementEmployerCents: retirementEmployerCents
        )
    }

    private static func nonNegativeCents(_ value: Double) -> Int64? {
        value >= 0 ? PayrollMoneyV2.cents(value) : nil
    }

    private static func confirmedSnapshotCents(
        _ value: CompanyEmployeeDeductionResolverV2.Value
    ) -> Int64? {
        guard value.reliable, let amount = value.amount else { return nil }
        return nonNegativeCents(amount)
    }

    private static func sumPresent(_ values: [Int64?]) -> Int64? {
        let known = values.compactMap { $0 }
        return known.count == values.count ? PayrollMoneyV2.sum(known) : nil
    }

    // Les détails exposés par la projection correspondent exactement aux retenues soustraites.
    // Les catalogues bruts et les contributions patronales gardent leur contrat existant.
    private static func monetaryStatutory(
        _ estimate: SocialContributionCatalogV2.Estimate, cents: [Int64?], total: Int64?
    ) -> SocialContributionCatalogV2.Estimate {
        guard let total, let amount = PayrollMoneyV2.amount(total),
              cents.allSatisfy({ $0 != nil }) else { return estimate }
        let lines = zip(estimate.lines, cents).map { line, value in
            SocialContributionCatalogV2.Line(
                id: line.id, label: line.label, baseAmount: line.baseAmount, rate: line.rate,
                employeeAmount: PayrollMoneyV2.amount(value!)!, source: line.source,
                employerRate: line.employerRate, employerAmount: line.employerAmount
            )
        }
        let before = PayrollMoneyV2.cents(estimate.gross)
            .flatMap { PayrollMoneyV2.sum([$0, -total]) }
            .flatMap { PayrollMoneyV2.amount(max(0, $0)) } ?? 0
        return .init(gross: estimate.gross, employeeDeductions: amount, netBeforeIncomeTax: before,
                     lines: lines, warnings: estimate.warnings, employerContributions: estimate.employerContributions)
    }

    private static func monetaryRetirement(
        _ estimate: ComplementaryRetirementCatalogV2.Estimate, cents: [Int64?], total: Int64?
    ) -> ComplementaryRetirementCatalogV2.Estimate {
        guard let total, let amount = PayrollMoneyV2.amount(total),
              cents.allSatisfy({ $0 != nil }) else { return estimate }
        let lines = zip(estimate.lines, cents).map { line, value in
            ComplementaryRetirementCatalogV2.Line(
                id: line.id, label: line.label, baseAmount: line.baseAmount,
                employeeRate: line.employeeRate, employeeAmount: PayrollMoneyV2.amount(value!)!,
                source: line.source, employerRate: line.employerRate, employerAmount: line.employerAmount
            )
        }
        return .init(lines: lines, employeeDeductions: amount, warnings: estimate.warnings,
                     employerContributions: estimate.employerContributions)
    }

    private static func monetaryDirect(
        _ result: CompanyEmployeeDeductionPayrollBridgeV2.Result, cents: [Int64?]
    ) -> CompanyEmployeeDeductionPayrollBridgeV2.Result {
        guard cents.allSatisfy({ $0 != nil }) else { return result }
        return .init(
            deductions: zip(result.deductions, cents).map { deduction, value in
                .init(id: deduction.id, label: deduction.label, amount: PayrollMoneyV2.amount(value!)!,
                      recurring: deduction.recurring)
            },
            confirmedEmployeeDeductionsComplete: result.confirmedEmployeeDeductionsComplete,
            warnings: result.warnings, traces: result.traces
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
