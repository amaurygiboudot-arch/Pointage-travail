import Foundation

struct SalarySegmentedSocialSecurityCeilingResultV2 {
    let ceiling: SocialSecurityCeilingV2.Snapshot?
    let warnings: [String]

    var reliable: Bool { ceiling?.complete == true }
}

/// Réutilise le plafond mensuel canonique seulement si toutes les versions datées
/// ont les mêmes paramètres qui influencent ce plafond. Une transition de taux
/// horaire seule ne change pas le calcul ; une transition de durée reste bloquée.
enum SalarySegmentedSocialSecurityCeilingV2 {
    static let coverageWarning =
        "Plafond SS segmenté : couverture contractuelle mensuelle non fiable ou incomplète."
    static let transitionWarning =
        "Plafond SS segmenté : paramètres contractuels du plafond différents entre versions ; proratisation à confirmer."
    static let hireDateWarning =
        "Plafond SS segmenté : date d'entrée confirmée absente ou incohérente."

    static func resolve(
        period: YearMonthV2,
        contracts: SalaryEmploymentContractPeriodResolutionV2,
        complementaryMinutes: Int?,
        unpaidAbsenceDays: Int?
    ) -> SalarySegmentedSocialSecurityCeilingResultV2 {
        guard let range = SalaryConventionCoverageResolverV2.monthEpochDayRange(period),
              contracts.sourceReliable,
              contracts.periodStartEpochDay == range.start,
              contracts.periodEndEpochDay == range.end,
              let coverage = contracts.coverage,
              coverage.fullyCovered,
              !contracts.calculationSegments.isEmpty else {
            return .init(ceiling: nil, warnings: unique(contracts.warnings + [coverageWarning]))
        }

        let versions = contracts.calculationSegments.map { $0.snapshot.contract }
        guard let first = versions.first,
              first.employerId.trimmingCharacters(in: .whitespacesAndNewlines) == contracts.companyId,
              versions.allSatisfy({ sameCeilingInputs($0, first) }) else {
            return .init(ceiling: nil, warnings: unique(contracts.warnings + [transitionWarning]))
        }
        guard let hireEpochDay = first.hireDateEpochDay,
              hireEpochDay <= contracts.periodStartEpochDay,
              let hireDate = civilDate(epochDay: hireEpochDay) else {
            return .init(ceiling: nil, warnings: unique(contracts.warnings + [hireDateWarning]))
        }

        let calculated = SocialSecurityCeilingV2.calculate(
            .init(
                period: period,
                contractType: first.type,
                contractualWeeklyMinutes: first.contractualWeeklyMinutes,
                complementaryMinutes: complementaryMinutes,
                entryDate: hireDate,
                unpaidAbsenceDays: unpaidAbsenceDays,
                forfaitAnnualDays: first.forfaitAnnualDays
            )
        )
        return .init(
            ceiling: calculated.complete ? calculated : nil,
            warnings: unique(contracts.warnings + calculated.warnings)
        )
    }

    private static func sameCeilingInputs(_ left: ContractV2, _ right: ContractV2) -> Bool {
        left.employerId.trimmingCharacters(in: .whitespacesAndNewlines) ==
            right.employerId.trimmingCharacters(in: .whitespacesAndNewlines) &&
        left.type == right.type &&
        left.contractualWeeklyMinutes == right.contractualWeeklyMinutes &&
        left.forfaitAnnualDays == right.forfaitAnnualDays &&
        left.hireDateEpochDay == right.hireDateEpochDay
    }

    private static func civilDate(epochDay: Int64) -> PayrollCivilDateV2? {
        let seconds = Double(epochDay) * 86_400.0
        guard seconds.isFinite else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let values = calendar.dateComponents(
            [.year, .month, .day], from: Date(timeIntervalSince1970: seconds)
        )
        guard let year = values.year, let month = values.month, let day = values.day else {
            return nil
        }
        return PayrollCivilDateV2(year: year, month: month, day: day)
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
