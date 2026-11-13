import Foundation

/// État d'affichage SalaireV2 partagé par l'interface iOS.
///
/// Cette couche ne calcule aucun montant métier. Elle ne fait qu'exposer les
/// références déjà admises par le contrat canonique SalaryReferenceContractV2.
/// Une source amont absente ne produit donc jamais de zéro ou d'estimation locale.
struct SalaryWorkspaceSnapshotV2: Equatable {
    let period: YearMonthV2
    let sourceReady: Bool
    let socialGross: Double?
    let netBeforeIncomeTax: Double?
    let netTaxable: Double?
    let incomeTax: Double?
    let netAfterIncomeTax: Double?
    let warnings: [String]
    var employerContributions: EmployerContributionProjectionV2.Result? = nil
    var employerWarnings: [String] = []
    var payslipDetails: SalaryCalculatedPayslipDetailsV2? = nil
    var settlementBaseReliable = false
    var monthlySettlement: SalaryMonthlyPayrollSettlementV2.Result? = nil

    var hasReliableGross: Bool { socialGross != nil }
    var hasReliableNet: Bool { netBeforeIncomeTax != nil }
}

enum SalaryWorkspaceResolverV2 {
    static let upstreamUnavailableWarning =
        "Salaire V2 iOS : les sources canoniques amont ne sont pas encore raccordées pour ce mois ; aucun montant n'est inventé."
    static let segmentedSourceUnavailableWarning =
        "Salaire V2 iOS : une transition datée exige la chaîne segmentée canonique ; aucun montant issu du calcul mensuel unique n'est affiché."
    static let segmentedPeriodMismatchWarning =
        "Salaire V2 iOS : la sortie segmentée appartient à un autre mois ; aucun montant n'est affiché."

    static func resolve(
        period: YearMonthV2,
        requiresSegmentedSource: Bool,
        segmented: SalarySegmentedCanonicalOutputV2?,
        reference: SalaryReferenceContractV2?,
        incomeTaxRate: CompanyIncomeTaxRateResolverV2.Snapshot? = nil,
        expectedCompanyId: String? = nil
    ) -> SalaryWorkspaceSnapshotV2 {
        guard requiresSegmentedSource else {
            return resolve(period: period, reference: reference, incomeTaxRate: incomeTaxRate, expectedCompanyId: expectedCompanyId)
        }
        guard let segmented else {
            return SalaryWorkspaceSnapshotV2(
                period: period,
                sourceReady: false,
                socialGross: nil,
                netBeforeIncomeTax: nil,
                netTaxable: nil,
                incomeTax: nil,
                netAfterIncomeTax: nil,
                warnings: [segmentedSourceUnavailableWarning]
            )
        }
        return resolve(period: period, segmented: segmented, expectedCompanyId: expectedCompanyId)
    }

    static func resolve(
        period: YearMonthV2,
        segmented: SalarySegmentedCanonicalOutputV2?,
        expectedCompanyId: String? = nil
    ) -> SalaryWorkspaceSnapshotV2 {
        guard let segmented else {
            return SalaryWorkspaceSnapshotV2(
                period: period,
                sourceReady: false,
                socialGross: nil,
                netBeforeIncomeTax: nil,
                netTaxable: nil,
                incomeTax: nil,
                netAfterIncomeTax: nil,
                warnings: [upstreamUnavailableWarning]
            )
        }

        guard segmented.period == period else {
            return SalaryWorkspaceSnapshotV2(
                period: period,
                sourceReady: false,
                socialGross: nil,
                netBeforeIncomeTax: nil,
                netTaxable: nil,
                incomeTax: nil,
                netAfterIncomeTax: nil,
                warnings: [segmentedPeriodMismatchWarning]
            )
        }

        let sourceReady =
            segmented.paidTimeReliable &&
            segmented.workedGrossReliable &&
            segmented.cashGrossReliable
        let socialGross = segmented.net.projection.flatMap { projection in
            projection.grossReliable && projection.contributionGross.isFinite && projection.contributionGross >= 0
                ? projection.contributionGross
                : nil
        }
        let netComplete = sourceReady && segmented.netBeforeIncomeTaxComplete

        return SalaryWorkspaceSnapshotV2(
            period: period,
            sourceReady: sourceReady,
            socialGross: sourceReady ? socialGross : nil,
            netBeforeIncomeTax: netComplete ? segmented.netBeforeIncomeTax : nil,
            netTaxable: netComplete ? segmented.netTaxable : nil,
            incomeTax: netComplete ? segmented.incomeTax : nil,
            netAfterIncomeTax: netComplete ? segmented.netAfterIncomeTax : nil,
            warnings: unique(segmented.warnings),
            employerContributions: sourceReady ? segmented.employerContributions.flatMap { employer in
                employer.matches(companyId: expectedCompanyId, year: period.year, month: period.month, gross: socialGross) ? employer : nil
            } : nil,
            employerWarnings: segmented.employerContributions?.matches(companyId: expectedCompanyId, year: period.year, month: period.month, gross: socialGross) == true && sourceReady
                ? segmented.employerWarnings
                : unique(segmented.employerWarnings + ["Cotisations patronales : contexte de l'entreprise ou du mois sélectionné non concordant ; affichage bloqué."]),
            payslipDetails: sourceReady ? SalaryCalculatedPayslipDetailsV2.segmented(segmented, companyId: expectedCompanyId) : nil,
            settlementBaseReliable: netComplete && segmented.net.projection?.settlementBaseReliable == true &&
                segmented.employerContributions?.matches(companyId: expectedCompanyId, year: period.year, month: period.month, gross: socialGross) == true
        )
    }

    static func resolve(
        period: YearMonthV2,
        reference: SalaryReferenceContractV2?,
        incomeTaxRate: CompanyIncomeTaxRateResolverV2.Snapshot? = nil,
        expectedCompanyId: String? = nil
    ) -> SalaryWorkspaceSnapshotV2 {
        guard let reference else {
            return SalaryWorkspaceSnapshotV2(
                period: period,
                sourceReady: false,
                socialGross: nil,
                netBeforeIncomeTax: nil,
                netTaxable: nil,
                incomeTax: nil,
                netAfterIncomeTax: nil,
                warnings: [upstreamUnavailableWarning]
            )
        }

        let socialGross = SalaryReferenceContractV2.socialGross(reference)
        let netBeforeIncomeTax = SalaryReferenceContractV2.beforeIncomeTax(reference)
        let netTaxable = SalaryReferenceContractV2.taxable(reference)
        let withholding = EmployeeWithholdingProjectionV2.calculate(
            netBeforeIncomeTax: netBeforeIncomeTax,
            netTaxable: netTaxable,
            incomeTaxRate: incomeTaxRate
        )
        var warnings = reference.warnings + withholding.warnings

        if socialGross == nil && !warnings.contains(where: { $0.hasPrefix("Brut social :") }) {
            warnings.append(
                "Brut social : référence non certifiable pour ce mois ; affichage bloqué."
            )
        }
        if netBeforeIncomeTax == nil && socialGross != nil && warnings.isEmpty {
            warnings.append(
                "Net avant impôt : résultat incomplet ; affichage de référence bloqué."
            )
        }

        return SalaryWorkspaceSnapshotV2(
            period: period,
            sourceReady: true,
            socialGross: socialGross,
            netBeforeIncomeTax: netBeforeIncomeTax,
            netTaxable: netTaxable,
            incomeTax: withholding.incomeTax,
            netAfterIncomeTax: withholding.netAfterIncomeTax,
            warnings: unique(warnings),
            employerContributions: reference.employerContributions.flatMap { employer in
                guard employer.matches(companyId: expectedCompanyId, year: period.year, month: period.month, gross: socialGross) else { return nil }
                return employer
            },
            employerWarnings: reference.employerContributions?.matches(companyId: expectedCompanyId, year: period.year, month: period.month, gross: socialGross) == true
                ? reference.employerWarnings
                : unique(reference.employerWarnings + ["Cotisations patronales : contexte de l'entreprise ou du mois sélectionné non concordant ; affichage bloqué."]),
            payslipDetails: reference.payslipDetails?.matches(companyId: expectedCompanyId, period: period, gross: socialGross) == true ? reference.payslipDetails : nil,
            settlementBaseReliable: reference.settlementBaseReliable && withholding.netAfterIncomeTax != nil &&
                reference.employerContributions?.matches(companyId: expectedCompanyId, year: period.year, month: period.month, gross: socialGross) == true
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}

/// Détails déjà calculés, conservés jusqu'aux deux présentations. Aucun taux ou droit nouveau.
struct SalaryCalculatedPayslipDetailsV2: Equatable {
    struct AmountLine: Equatable {
        let label: String
        let amount: Double?
        var roundingAdjustment: Bool = false
    }
    struct Contribution: Equatable {
        let label: String
        let base: Double
        let employeeRate: Double?
        let employeeAmount: Double?
        let employerRate: Double?
        let employerAmount: Double?
        let source: String
    }
    let companyId: String
    let period: YearMonthV2
    let socialGross: Double
    let grossLines: [AmountLine]
    let contributions: [Contribution]

    func matches(companyId: String?, period: YearMonthV2, gross: Double?) -> Bool {
        guard let companyId, !companyId.isEmpty, self.companyId == companyId,
              self.period == period, let gross,
              let cents = PayrollMoneyV2.cents(gross), gross >= 0 else { return false }
        return cents == PayrollMoneyV2.cents(socialGross)
    }

    static func monthly(payroll: PayrollResultV2, projection: EmployeeNetProjectionV2.Result,
                        companyId: String?, period: YearMonthV2) -> Self? {
        guard payroll.grossReliable, payroll.grossEstimate >= 0,
              PayrollMoneyV2.cents(payroll.grossEstimate) != nil,
              PayrollMoneyV2.cents(payroll.grossEstimate) == PayrollMoneyV2.cents(projection.cashGross) else { return nil }
        let totals = [payroll.regularGross, payroll.overtimeGross, payroll.premiumsGross, payroll.fixedPremiumsGross]
        guard matchesNativeTotal(totals, expected: payroll.grossEstimate) else { return nil }
        var grossLines: [AmountLine] = [
            .init(label: "Salaire de base", amount: payroll.regularGross),
            .init(label: "Heures supplémentaires / complémentaires", amount: payroll.overtimeGross),
            .init(label: "Majorations liées au travail", amount: payroll.premiumsGross)
        ]
        if payroll.confirmedPremiums.isEmpty {
            grossLines.append(.init(label: "Primes fixes — total calculé", amount: payroll.fixedPremiumsGross))
        } else {
            let premiums = payroll.confirmedPremiums
            let cents = premiums.compactMap { $0.amount >= 0 ? PayrollMoneyV2.cents($0.amount) : nil }
            guard cents.count == premiums.count, Set(premiums.map(\.id)).count == premiums.count,
                  premiums.allSatisfy({ !$0.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !$0.label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }),
                  matchesNativeTotal(premiums.map(\.amount), expected: payroll.fixedPremiumsGross) else { return nil }
            grossLines += premiums.map { .init(label: $0.label, amount: $0.amount) }
        }
        return build(projection: projection, companyId: companyId, period: period, grossLines: grossLines)
    }

    static func segmented(_ output: SalarySegmentedCanonicalOutputV2, companyId: String?) -> Self? {
        guard output.cashGrossReliable, output.workedGrossReliable,
              let projection = output.net.projection else { return nil }
        if !output.cash.confirmedComponents.isEmpty {
            let components = output.cash.confirmedComponents
            let cents = components.compactMap { $0.reliable && $0.amount >= 0 ? PayrollMoneyV2.cents($0.amount) : nil }
            guard cents.count == components.count, Set(components.map(\.id)).count == components.count,
                  let additional = output.cash.additionalCashGross,
                  matchesNativeTotal(components.map(\.amount), expected: additional) else { return nil }
        }
        var lines: [AmountLine] = [
            .init(label: "Salaire de base", amount: output.baseGross),
            .init(label: "Heures supplémentaires", amount: output.overtimeGross),
            .init(label: "Heures complémentaires", amount: output.complementaryGross),
            .init(label: "Majorations liées au travail", amount: output.premiumGross)
        ]
        lines += output.cash.confirmedComponents.map {
            .init(label: $0.label ?? ($0.id == "seniority-premium" ? "Prime d’ancienneté" : "Prime d’entreprise confirmée"),
                  amount: $0.reliable ? $0.amount : nil)
        }
        if output.cash.confirmedComponents.isEmpty {
            lines.append(.init(label: "Primes fixes — total calculé", amount: output.cash.additionalCashGross))
        }
        return build(projection: projection, companyId: companyId, period: output.period, grossLines: lines)
    }

    private static func matchesNativeTotal(_ values: [Double], expected: Double) -> Bool {
        guard values.allSatisfy({ $0 >= 0 && PayrollMoneyV2.cents($0) != nil }),
              expected >= 0, let expectedCents = PayrollMoneyV2.cents(expected) else { return false }
        let nativeSum = values.reduce(0, +)
        return nativeSum.isFinite && PayrollMoneyV2.cents(nativeSum) == expectedCents
    }

    private static func build(projection: EmployeeNetProjectionV2.Result, companyId: String?,
                              period: YearMonthV2, grossLines: [AmountLine]) -> Self? {
        guard let companyId, !companyId.isEmpty, projection.grossReliable,
              let employer = projection.employerContributions,
              employer.matches(companyId: companyId, year: period.year, month: period.month, gross: projection.contributionGross) else { return nil }
        func sumMatches(_ values: [Int64?], count: Int, expected: Double) -> Bool {
            guard expected >= 0, let expectedCents = PayrollMoneyV2.cents(expected), values.count == count else { return false }
            let cents = values.compactMap { $0 }
            return cents.count == count && cents.allSatisfy { $0 >= 0 && PayrollMoneyV2.amount($0) != nil } && PayrollMoneyV2.sum(cents) == expectedCents
        }
        let employeeReady = projection.netBeforeIncomeTaxComplete
        let employeeStatutoryReady = employeeReady && sumMatches(projection.statutoryEmployeeCents,
            count: projection.statutory.lines.count, expected: projection.statutory.employeeDeductions)
        let employeeRetirementReady = employeeReady && sumMatches(projection.retirementEmployeeCents,
            count: projection.complementaryRetirement.lines.count, expected: projection.complementaryRetirement.employeeDeductions)
        func known(_ family: EmployerContributionProjectionV2.Family, values: [Int64?], count: Int) -> Bool {
            guard let line = employer.lines.first(where: { $0.family == family }),
                  line.state == .known, let cents = line.amountCents,
                  let amount = PayrollMoneyV2.amount(cents) else { return false }
            return sumMatches(values, count: count, expected: amount)
        }
        let employerStatutoryReady = known(.statutory, values: projection.statutoryEmployerCents, count: projection.statutory.lines.count)
        let employerRetirementReady = known(.complementaryRetirement, values: projection.retirementEmployerCents, count: projection.complementaryRetirement.lines.count)
        func valid(_ value: Double) -> Double? {
            value >= 0 && PayrollMoneyV2.cents(value) != nil ? value : nil
        }
        func validLine(id: String, source: String, base: Double, employeeRate: Double, employerRate: Double) -> Bool {
            !id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            valid(base) != nil && employeeRate.isFinite && employerRate.isFinite &&
            (0...1).contains(employeeRate) && (0...1).contains(employerRate)
        }
        let statutoryLines = projection.statutory.lines
        let retirementLines = projection.complementaryRetirement.lines
        guard Set(statutoryLines.map(\.id)).count == statutoryLines.count,
              Set(retirementLines.map(\.id)).count == retirementLines.count,
              statutoryLines.allSatisfy({ validLine(id: $0.id, source: $0.source, base: $0.baseAmount, employeeRate: $0.rate, employerRate: $0.employerRate) }),
              retirementLines.allSatisfy({ validLine(id: $0.id, source: $0.source, base: $0.baseAmount, employeeRate: $0.employeeRate, employerRate: $0.employerRate) }) else { return nil }
        let statutory = statutoryLines.enumerated().map { index, line in
            Contribution(label: line.label, base: line.baseAmount,
                employeeRate: employeeStatutoryReady ? line.rate : nil,
                employeeAmount: employeeStatutoryReady ? projection.statutoryEmployeeCents[index].flatMap(PayrollMoneyV2.amount) : nil,
                employerRate: employerStatutoryReady ? line.employerRate : nil,
                employerAmount: employerStatutoryReady ? projection.statutoryEmployerCents[index].flatMap(PayrollMoneyV2.amount) : nil, source: line.source)
        }
        let retirement = retirementLines.enumerated().map { index, line in
            Contribution(label: line.label, base: line.baseAmount,
                employeeRate: employeeRetirementReady ? line.employeeRate : nil,
                employeeAmount: employeeRetirementReady ? projection.retirementEmployeeCents[index].flatMap(PayrollMoneyV2.amount) : nil,
                employerRate: employerRetirementReady ? line.employerRate : nil,
                employerAmount: employerRetirementReady ? projection.retirementEmployerCents[index].flatMap(PayrollMoneyV2.amount) : nil, source: line.source)
        }
        // Les retenues d'entreprise sont des montants confirmés ; leur base/taux ne sont pas déduits.
        let deductions = employeeReady ? projection.companyCashDeductions.deductions.map {
            AmountLine(label: $0.label, amount: valid($0.amount))
        } : []
        guard let benefits = projection.benefitsInKindGross, benefits >= 0,
              let benefitsCents = PayrollMoneyV2.cents(benefits),
              let cashCents = PayrollMoneyV2.cents(projection.cashGross),
              PayrollMoneyV2.sum([cashCents, benefitsCents]) == PayrollMoneyV2.cents(projection.contributionGross) else { return nil }
        let knownGrossAmounts = grossLines.compactMap(\.amount)
        guard knownGrossAmounts.count == grossLines.count,
              matchesNativeTotal(knownGrossAmounts, expected: projection.cashGross) else { return nil }
        var displayedGross = grossLines.map { AmountLine(label: $0.label, amount: $0.amount.flatMap(valid)) }
        let cents = displayedGross.compactMap { $0.amount.flatMap(PayrollMoneyV2.cents) }
        if cents.count == displayedGross.count,
           let displayedTotal = PayrollMoneyV2.sum(cents),
           let cashTotal = PayrollMoneyV2.cents(projection.cashGross),
           let delta = PayrollMoneyV2.sum([cashTotal, -displayedTotal]), delta != 0,
           let adjustment = PayrollMoneyV2.amount(delta) {
            displayedGross.append(.init(label: "Écart d’arrondi de présentation", amount: adjustment, roundingAdjustment: true))
        }
        return .init(companyId: companyId, period: period, socialGross: projection.contributionGross,
                     grossLines: displayedGross,
                     contributions: statutory + retirement,
                     companyDeductions: deductions, benefitsInKindGross: benefits)
    }
    var companyDeductions: [AmountLine] = []
    var benefitsInKindGross: Double? = nil
}

/// Texte identique pour l'écran et le PDF ; les valeurs inconnues restent distinctes de zéro.
enum SalaryCalculatedPayslipPresentationV2 {
    struct Row: Equatable, Identifiable {
        let id: String
        let label: String
        let value: String
    }
    struct Section: Equatable, Identifiable {
        let id: String
        let title: String
        let rows: [Row]
    }
    static func sections(snapshot: SalaryWorkspaceSnapshotV2, companyId: String?) -> [Section] {
        let detail = snapshot.payslipDetails.flatMap {
            $0.matches(companyId: companyId, period: snapshot.period, gross: snapshot.socialGross) ? $0 : nil
        }
        func rows(_ values: [SalaryCalculatedPayslipDetailsV2.AmountLine]) -> [Row] {
            values.enumerated().map { .init(id: "\($0.offset)", label: $0.element.label, value: money($0.element.amount, allowNegative: $0.element.roundingAdjustment)) }
        }
        var gross = detail?.grossLines ?? [
            .init(label: "Salaire de base", amount: nil),
            .init(label: "Heures supplémentaires / complémentaires", amount: nil),
            .init(label: "Majorations liées au travail", amount: nil),
            .init(label: "Primes fixes / ancienneté", amount: nil)
        ]
        gross.append(.init(label: "Avantages en nature soumis aux cotisations", amount: detail?.benefitsInKindGross))
        gross.append(.init(label: "Brut social estimé", amount: snapshot.socialGross))
        let contributions = detail?.contributions.enumerated().map { index, line in
            Row(id: "\(index)", label: line.label,
                value: "Base : \(money(line.base)) · Salarié : \(rate(line.employeeRate)) / \(money(line.employeeAmount)) · Employeur : \(rate(line.employerRate)) / \(money(line.employerAmount))\nSource : \(line.source)")
        } ?? [.init(id: "missing", label: "Cotisations détaillées", value: "À confirmer — sources de calcul incomplètes")]
        let monthly = SalaryMonthlyPayrollSettlementV2.project(snapshot.monthlySettlement, companyId: companyId, period: snapshot.period,
            netAfterIncomeTax: snapshot.netAfterIncomeTax, baseReliable: snapshot.sourceReady && snapshot.settlementBaseReliable)
        var settlement = rows(detail?.companyDeductions ?? [])
        settlement += [
            .init(id: "benefits", label: "Avantages en nature non versés en espèces",
                  value: detail?.benefitsInKindGross.map { $0 > 0 ? "-" + money($0) : money($0) } ?? "À confirmer"),
            .init(id: "before", label: "Net estimé avant impôt", value: money(snapshot.netBeforeIncomeTax)),
            .init(id: "taxable", label: "Net imposable estimé", value: money(snapshot.netTaxable)),
            .init(id: "pas", label: "Prélèvement à la source", value: money(snapshot.incomeTax)),
            .init(id: "after", label: "Net estimé après PAS, avant ajustements de versement", value: money(snapshot.netAfterIncomeTax)),
            .init(id: "paid", label: "Versement estimé après les ajustements confirmés", value: money(monthly.projectedPaid))
        ]
        settlement += SalaryMonthlyPayrollSettlementV2.Field.allCases.map { field in
            .init(id: field.rawValue, label: field.label + " — constaté",
                  value: money(monthly.record?[field].flatMap(SalaryMonthlyPayrollSettlementV2.cents).flatMap(PayrollMoneyV2.amount)))
        }
        if let record = monthly.record {
            settlement.append(.init(id: "settlement-source", label: "Source mensuelle confirmée", value: record.source))
            settlement.append(.init(id: "settlement-complete", label: "Ajustements exhaustifs et non déjà inclus", value: record.adjustmentsComplete ? "Confirmés pour ce mois" : "À confirmer"))
        }
        settlement.append(.init(id: "settlement-difference", label: "Écart net payé constaté − versement estimé", value: money(monthly.observedMinusProjected, allowNegative: true)))
        settlement += monthly.warnings.enumerated().map { .init(id: "settlement-warning-\($0.offset)", label: "Contrôle du versement", value: $0.element) }
        return [.init(id: "gross", title: "Rémunération brute", rows: rows(gross)),
                .init(id: "contributions", title: "Cotisations détaillées connues", rows: contributions),
                .init(id: "settlement", title: "Retenues et synthèse du net", rows: settlement)]
    }
    static func money(_ value: Double?, allowNegative: Bool = false) -> String {
        guard let value, (value >= 0 || allowNegative), let cents = PayrollMoneyV2.cents(value),
              PayrollMoneyV2.amount(cents) != nil else { return "À confirmer" }
        // Formatting a large binary Double with %.2f can change the last cent.
        let absolute = cents < 0 ? -cents : cents
        return "\(cents < 0 ? "-" : "")\(absolute / 100),\(String(format: "%02lld", absolute % 100)) €"
    }
    private static func rate(_ value: Double?) -> String {
        guard let value, value.isFinite, (0...1).contains(value) else { return "À confirmer" }
        return String(format: "%.4f %%", locale: Locale(identifier: "fr_FR"), value * 100)
    }
}
