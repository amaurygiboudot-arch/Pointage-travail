import Foundation

/// Canonicalisation IDCC dédiée à l'ancienneté, alignée sur Android.
/// Les variantes 00292 / 0292 / 292 désignent donc le même IDCC 292.
enum SalaryConventionSeniorityIdccV2 {
    static func normalize(_ value: String) -> String {
        let digits = value.filter { $0.isNumber }
        let canonical = digits.drop(while: { $0 == "0" })
        return String(canonical)
    }
}

/// Prime d'ancienneté conventionnelle iOS, miroir du moteur Android.
/// Calcul civil pur : aucune dépendance au fuseau, au métier ou à l'interface.
enum SalaryConventionSeniorityPremiumV2 {
    enum Basis: String, Codable {
        case actualMonthlyBase = "ACTUAL_MONTHLY_BASE"
        case conventionalMinimumMonthly = "CONVENTIONAL_MINIMUM_MONTHLY"
        case fixedMonthly = "FIXED_MONTHLY"
    }

    struct Step: Codable, Equatable {
        let years: Int
        let rate: Double?
        let fixedMonthlyAmount: Double?
    }

    struct Rule: Codable, Equatable {
        let idcc: String
        let ruleId: String
        let effectiveFrom: PayrollCivilDateV2
        let effectiveTo: PayrollCivilDateV2?
        let classification: ConventionClassificationV2
        let basis: Basis
        let steps: [Step]
        let includeConfirmedMonthlySupplement: Bool
        let source: String
        let extensionStatus: ConventionExtensionStatusV2
        let extensionEffectiveFrom: PayrollCivilDateV2?

        func structurallyValid() -> Bool {
            guard !SalaryConventionSeniorityIdccV2.normalize(idcc).isEmpty,
                  !ruleId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  !source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  !steps.isEmpty else { return false }
            if let end = effectiveTo, end < effectiveFrom { return false }
            if extensionEffectiveFrom != nil && extensionStatus != .extended { return false }
            if steps.contains(where: { $0.years <= 0 }) { return false }
            if Set(steps.map(\.years)).count != steps.count { return false }

            return steps.allSatisfy { step in
                switch basis {
                case .actualMonthlyBase, .conventionalMinimumMonthly:
                    guard let rate = step.rate else { return false }
                    return rate.isFinite && rate >= 0 && step.fixedMonthlyAmount == nil
                case .fixedMonthly:
                    guard let amount = step.fixedMonthlyAmount else { return false }
                    return amount.isFinite && amount >= 0 && step.rate == nil
                }
            }
        }

        func active(on date: PayrollCivilDateV2) -> Bool {
            date >= effectiveFrom && (effectiveTo == nil || date <= effectiveTo!)
        }

        func applicableToCompany(
            companyApplicabilityConfirmed: Bool,
            date: PayrollCivilDateV2
        ) -> Bool {
            switch extensionStatus {
            case .extended:
                return companyApplicabilityConfirmed || date >= (extensionEffectiveFrom ?? effectiveFrom)
            case .notExtended:
                return companyApplicabilityConfirmed
            case .unknown:
                return false
            }
        }
    }
    struct Result {
        let applicable: Bool
        let reliable: Bool
        let selectedRule: Rule?
        let stepYears: Int?
        let rate: Double?
        let monthlyAmount: Double?
        let warnings: [String]
    }

    static func calculate(
        rules: [Rule],
        idcc: String,
        classification: ConventionClassificationV2,
        referenceDate: PayrollCivilDateV2,
        confirmedSeniorityDate: PayrollCivilDateV2?,
        actualMonthlyBaseGross: Double?,
        conventionalMinimumMonthlyGross: Double?,
        confirmedMonthlySupplement: Double? = 0,
        companyApplicabilityConfirmed: Bool = false
    ) -> Result {
        let normalized = SalaryConventionSeniorityIdccV2.normalize(idcc)
        let knownMatching = rules
            .filter { $0.structurallyValid() }
            .filter { SalaryConventionSeniorityIdccV2.normalize($0.idcc) == normalized }
            .filter { $0.active(on: referenceDate) }
            .filter { classification.matches($0.classification) }

        guard !knownMatching.isEmpty else {
            return .init(
                applicable: false, reliable: false, selectedRule: nil,
                stepYears: nil, rate: nil, monthlyAmount: nil, warnings: []
            )
        }
        let candidates = knownMatching.filter {
            $0.applicableToCompany(
                companyApplicabilityConfirmed: companyApplicabilityConfirmed,
                date: referenceDate
            )
        }
        guard !candidates.isEmpty else {
            let latest = knownMatching.max { $0.effectiveFrom < $1.effectiveFrom }!
            let reason: String
            switch latest.extensionStatus {
            case .notExtended:
                reason = "règle non étendue et applicabilité à l'entreprise non confirmée"
            case .unknown:
                reason = "statut d'extension non confirmé"
            case .extended:
                if let date = latest.extensionEffectiveFrom {
                    reason = "extension applicable à toutes les entreprises à compter du \(civilText(date))"
                } else {
                    reason = "applicabilité à l'entreprise non démontrée"
                }
            }
            return .init(
                applicable: true, reliable: false, selectedRule: latest,
                stepYears: nil, rate: nil, monthlyAmount: nil,
                warnings: [
                    "Prime d'ancienneté IDCC \(normalized) : \(reason) ; aucun montant n'est appliqué automatiquement. Source : \(latest.source)."
                ]
            )
        }
        let latestDate = candidates.map(\.effectiveFrom).max()!
        let latest = candidates.filter { $0.effectiveFrom == latestDate }
        let specificity = latest.map { $0.classification.specificity }.max()!
        let best = latest.filter { $0.classification.specificity == specificity }
        guard best.count == 1 else {
            return .init(
                applicable: true, reliable: false, selectedRule: nil,
                stepYears: nil, rate: nil, monthlyAmount: nil,
                warnings: [
                    "Prime d'ancienneté IDCC \(normalized) : plusieurs règles applicables se chevauchent avec la même précision ; calcul automatique bloqué."
                ]
            )
        }

        let rule = best[0]
        guard let seniorityDate = confirmedSeniorityDate else {
            return review(
                rule,
                "Prime d'ancienneté IDCC \(normalized) : date d'ancienneté conventionnelle confirmée manquante."
            )
        }
        guard seniorityDate <= referenceDate else {
            return review(
                rule,
                "Prime d'ancienneté IDCC \(normalized) : date d'ancienneté postérieure à la période de paie."
            )
        }
        guard let monthEndDay = PayrollCivilDateV2.daysInMonth(
            year: referenceDate.year,
            month: referenceDate.month
        ),
        let monthStart = PayrollCivilDateV2(
            year: referenceDate.year, month: referenceDate.month, day: 1
        ),
        let monthEnd = PayrollCivilDateV2(
            year: referenceDate.year, month: referenceDate.month, day: monthEndDay
        ) else {
            return review(rule, "Prime d'ancienneté IDCC \(normalized) : période civile invalide.")
        }

        let startStep = stepAt(rule: rule, seniorityDate: seniorityDate, date: monthStart)
        let endStep = stepAt(rule: rule, seniorityDate: seniorityDate, date: monthEnd)
        guard startStep?.years == endStep?.years else {
            return .init(
                applicable: true,
                reliable: false,
                selectedRule: rule,
                stepYears: endStep?.years,
                rate: endStep?.rate,
                monthlyAmount: nil,
                warnings: [
                    "Prime d'ancienneté IDCC \(normalized) : un palier change pendant le mois ; proratisation à contrôler. Source : \(rule.source)."
                ]
            )
        }

        guard let step = endStep else {
            return .init(
                applicable: true, reliable: true, selectedRule: rule,
                stepYears: nil, rate: 0, monthlyAmount: 0, warnings: []
            )
        }
        let supplement: Double
        if rule.includeConfirmedMonthlySupplement {
            guard let value = confirmedMonthlySupplement,
                  value.isFinite, value >= 0 else {
                return review(
                    rule,
                    "Prime d'ancienneté IDCC \(normalized) : complément mensuel de base à confirmer (0 s'il n'existe pas)."
                )
            }
            supplement = value
        } else {
            supplement = 0
        }

        let amount: Double
        switch rule.basis {
        case .actualMonthlyBase:
            guard let base = actualMonthlyBaseGross,
                  base.isFinite, base >= 0,
                  let rate = step.rate else {
                return review(
                    rule,
                    "Prime d'ancienneté IDCC \(normalized) : salaire mensuel de base fiable indisponible."
                )
            }
            amount = (base + supplement) * rate

        case .conventionalMinimumMonthly:
            guard let base = conventionalMinimumMonthlyGross,
                  base.isFinite, base >= 0,
                  let rate = step.rate else {
                return review(
                    rule,
                    "Prime d'ancienneté IDCC \(normalized) : minimum conventionnel mensuel fiable indisponible."
                )
            }
            amount = (base + supplement) * rate

        case .fixedMonthly:
            guard let fixed = step.fixedMonthlyAmount else {
                return review(rule, "Prime d'ancienneté IDCC \(normalized) : montant fixe invalide.")
            }
            amount = fixed
        }
        guard amount.isFinite, amount >= 0 else {
            return review(rule, "Prime d'ancienneté IDCC \(normalized) : montant non représentable.")
        }
        return .init(
            applicable: true,
            reliable: true,
            selectedRule: rule,
            stepYears: step.years,
            rate: step.rate,
            monthlyAmount: amount,
            warnings: [
                "Prime d'ancienneté IDCC \(normalized) : palier \(step.years) ans appliqué. Source : \(rule.source)."
            ]
        )
    }

    private static func stepAt(
        rule: Rule,
        seniorityDate: PayrollCivilDateV2,
        date: PayrollCivilDateV2
    ) -> Step? {
        guard date >= seniorityDate else { return nil }
        let years = fullYears(from: seniorityDate, to: date)
        return rule.steps.filter { years >= $0.years }.max { $0.years < $1.years }
    }

    private static func fullYears(
        from start: PayrollCivilDateV2,
        to end: PayrollCivilDateV2
    ) -> Int {
        var years = end.year - start.year
        if (end.month, end.day) < (start.month, start.day) { years -= 1 }
        return max(0, years)
    }
    private static func review(_ rule: Rule, _ warning: String) -> Result {
        .init(
            applicable: true,
            reliable: false,
            selectedRule: rule,
            stepYears: nil,
            rate: nil,
            monthlyAmount: nil,
            warnings: ["\(warning) Source : \(rule.source)."]
        )
    }

    private static func civilText(_ date: PayrollCivilDateV2) -> String {
        String(format: "%04d-%02d-%02d", date.year, date.month, date.day)
    }
}