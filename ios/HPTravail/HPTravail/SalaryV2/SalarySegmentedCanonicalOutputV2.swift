import Foundation

struct SalarySegmentedCanonicalOutputV2 {
    let worked: SalarySegmentedWorkedGrossProductionResultV2
    let cash: SalarySegmentedCashGrossAssemblyResultV2
    let net: SalarySegmentedCashGrossNetProjectionResultV2
    let paidMinutes: Int?
    let nightMinutes: Int?
    let saturdayMinutes: Int?
    let sundayMinutes: Int?
    let publicHolidayMinutes: Int?
    let baseGross: Double?
    let overtimeGross: Double?
    let complementaryGross: Double?
    let premiumGross: Double?
    let workedGross: Double?
    let cashGross: Double?
    let netBeforeIncomeTax: Double?
    let netTaxable: Double?
    let incomeTax: Double?
    let netAfterIncomeTax: Double?
    let paidTimeReliable: Bool
    let premiumTimeReliable: Bool
    let workedGrossReliable: Bool
    let cashGrossReliable: Bool
    let netBeforeIncomeTaxComplete: Bool
    let warnings: [String]
}

/// Vue canonique commune des résultats déjà produits par B21/B20/cash/net.
/// Aucun droit, taux ni montant n'est recalculé ici.
enum SalarySegmentedCanonicalOutputAssemblerV2 {
    static let chainWarning =
        "Sortie Salaire segmentée : les résultats B20, cash gross et net ne proviennent pas de la même chaîne."
    static let timeWarning =
        "Sortie Salaire segmentée : le temps payé ne peut pas être agrégé de façon fiable."
    static let premiumTimeWarning =
        "Sortie Salaire segmentée : la ventilation nuit/samedi/dimanche/jour férié n'est pas fiable."
    static let variableWarning =
        "Sortie Salaire segmentée : la ventilation des variables de brut n'est pas fiable."

    static func assemble(
        worked: SalarySegmentedWorkedGrossProductionResultV2,
        cash: SalarySegmentedCashGrossAssemblyResultV2,
        net: SalarySegmentedCashGrossNetProjectionResultV2
    ) -> SalarySegmentedCanonicalOutputV2 {
        var warnings = unique(worked.warnings + cash.warnings + net.warnings)

        let chainConsistent =
            cashEqual(net.cash, cash) &&
            sameMoney(cash.workedGross, worked.workedGross)
        if !chainConsistent { warnings.append(chainWarning) }

        let weeks = worked.evidence.slices.flatMap { $0.weeks }
        let keys = weeks.map { "\($0.yearForWeekOfYear)-\($0.weekOfYear)" }
        let uniqueWeeks = Set(keys).count == keys.count
        let timeInputsValid =
            worked.evidence.reliable &&
            uniqueWeeks &&
            weeks.allSatisfy { $0.week.paidMinutes >= 0 }

        let paidMinutes = timeInputsValid ? sumMinutes(weeks.map { $0.week.paidMinutes }) : nil
        let paidTimeReliable = paidMinutes != nil
        if !paidTimeReliable { warnings.append(timeWarning) }

        let premiumInputsValid =
            paidTimeReliable &&
            worked.evidence.slices.allSatisfy { $0.evidence.premiumTimeBreakdownReliable } &&
            weeks.allSatisfy {
                $0.week.nightMinutes >= 0 &&
                $0.week.saturdayMinutes >= 0 &&
                $0.week.sundayMinutes >= 0 &&
                $0.week.publicHolidayMinutes >= 0
            }

        let nightMinutes = premiumInputsValid ? sumMinutes(weeks.map { $0.week.nightMinutes }) : nil
        let saturdayMinutes = premiumInputsValid ? sumMinutes(weeks.map { $0.week.saturdayMinutes }) : nil
        let sundayMinutes = premiumInputsValid ? sumMinutes(weeks.map { $0.week.sundayMinutes }) : nil
        let publicHolidayMinutes = premiumInputsValid ? sumMinutes(weeks.map { $0.week.publicHolidayMinutes }) : nil
        let premiumTimeReliable =
            premiumInputsValid &&
            nightMinutes != nil &&
            saturdayMinutes != nil &&
            sundayMinutes != nil &&
            publicHolidayMinutes != nil
        if !premiumTimeReliable { warnings.append(premiumTimeWarning) }

        let breakdownReliable =
            worked.variables.reliable &&
            worked.variables.breakdowns.allSatisfy {
                finiteNonNegative($0.overtimeGross) &&
                finiteNonNegative($0.complementaryGross) &&
                finiteNonNegative($0.premiumGross)
            }
        let overtimeGross = breakdownReliable ? sumMoney(worked.variables.breakdowns.map { $0.overtimeGross }) : nil
        let complementaryGross = breakdownReliable ? sumMoney(worked.variables.breakdowns.map { $0.complementaryGross }) : nil
        let premiumGross = breakdownReliable ? sumMoney(worked.variables.breakdowns.map { $0.premiumGross }) : nil
        if !breakdownReliable || overtimeGross == nil || complementaryGross == nil || premiumGross == nil {
            warnings.append(variableWarning)
        }

        let baseGross = worked.base.reliable ? worked.base.baseGross.flatMap {
            finiteNonNegative($0) ? $0 : nil
        } : nil
        let reliableWorkedGross =
            chainConsistent && worked.reliable && worked.assembly.reliable
                ? worked.workedGross.flatMap { finiteNonNegative($0) ? $0 : nil }
                : nil
        let reliableCashGross =
            chainConsistent && cash.reliable
                ? cash.cashGross.flatMap { finiteNonNegative($0) ? $0 : nil }
                : nil

        let projection = net.projection
        let netComplete =
            chainConsistent &&
            net.netBeforeIncomeTaxComplete &&
            projection?.netBeforeIncomeTax != nil

        return .init(
            worked: worked,
            cash: cash,
            net: net,
            paidMinutes: paidMinutes,
            nightMinutes: nightMinutes,
            saturdayMinutes: saturdayMinutes,
            sundayMinutes: sundayMinutes,
            publicHolidayMinutes: publicHolidayMinutes,
            baseGross: baseGross,
            overtimeGross: overtimeGross,
            complementaryGross: complementaryGross,
            premiumGross: premiumGross,
            workedGross: reliableWorkedGross,
            cashGross: reliableCashGross,
            netBeforeIncomeTax: netComplete ? projection?.netBeforeIncomeTax : nil,
            netTaxable: netComplete ? projection?.netTaxable : nil,
            incomeTax: netComplete ? projection?.incomeTax : nil,
            netAfterIncomeTax: netComplete ? projection?.netAfterIncomeTax : nil,
            paidTimeReliable: paidTimeReliable,
            premiumTimeReliable: premiumTimeReliable,
            workedGrossReliable: reliableWorkedGross != nil,
            cashGrossReliable: reliableCashGross != nil,
            netBeforeIncomeTaxComplete: netComplete,
            warnings: unique(warnings)
        )
    }

    private static func sumMinutes(_ values: [Int]) -> Int? {
        var total = 0
        for value in values {
            let addition = total.addingReportingOverflow(value)
            guard !addition.overflow else { return nil }
            total = addition.partialValue
        }
        return total
    }

    private static func sumMoney(_ values: [Double]) -> Double? {
        var total = 0.0
        for value in values {
            guard finiteNonNegative(value) else { return nil }
            total += value
            guard total.isFinite, total >= 0 else { return nil }
        }
        return total
    }

    private static func sameMoney(_ left: Double?, _ right: Double?) -> Bool {
        guard let left, let right,
              finiteNonNegative(left), finiteNonNegative(right) else { return false }
        return abs(left - right) <= 0.005
    }

    private static func cashEqual(
        _ lhs: SalarySegmentedCashGrossAssemblyResultV2,
        _ rhs: SalarySegmentedCashGrossAssemblyResultV2
    ) -> Bool {
        lhs.reliable == rhs.reliable &&
        sameOptionalMoney(lhs.workedGross, rhs.workedGross) &&
        sameOptionalMoney(lhs.additionalCashGross, rhs.additionalCashGross) &&
        sameOptionalMoney(lhs.cashGross, rhs.cashGross)
    }

    private static func sameOptionalMoney(_ left: Double?, _ right: Double?) -> Bool {
        switch (left, right) {
        case (nil, nil): return true
        case let (left?, right?): return abs(left - right) <= 0.005
        default: return false
        }
    }

    private static func finiteNonNegative(_ value: Double) -> Bool {
        value.isFinite && value >= 0
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
