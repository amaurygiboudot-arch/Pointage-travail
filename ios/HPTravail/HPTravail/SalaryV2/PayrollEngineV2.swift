import Foundation

enum ContractTypeV2: String, Codable, Equatable {
    case fullTime = "FULL_TIME"
    case partTime = "PART_TIME"
    case forfaitHours = "FORFAIT_HOURS"
    case forfaitDays = "FORFAIT_DAYS"
    case forfait = "FORFAIT"
    case other = "OTHER"
}

enum ForfaitHoursPeriodV2: String, Codable, Equatable {
    case week = "WEEK"
    case month = "MONTH"
    case year = "YEAR"
}

enum PeriodicityV2: String, Codable, Equatable {
    case oneOff = "ONE_OFF"
    case daily = "DAILY"
    case weekly = "WEEKLY"
    case monthly = "MONTHLY"
    case yearly = "YEARLY"
}

struct ContractV2: Equatable {
    let id: String
    let employerId: String
    let type: ContractTypeV2
    let contractualWeeklyMinutes: Int?
    let grossHourlyRate: Double?
    let hireDateEpochDay: Int64?
    let payrollCutoffDay: Int?
    let forfaitHoursPeriod: ForfaitHoursPeriodV2?
    let forfaitHours: Double?
    let forfaitAnnualDays: Double?
    let monthlyGrossSalary: Double?

    init(
        id: String,
        employerId: String,
        type: ContractTypeV2,
        contractualWeeklyMinutes: Int?,
        grossHourlyRate: Double?,
        hireDateEpochDay: Int64?,
        payrollCutoffDay: Int? = nil,
        forfaitHoursPeriod: ForfaitHoursPeriodV2? = nil,
        forfaitHours: Double? = nil,
        forfaitAnnualDays: Double? = nil,
        monthlyGrossSalary: Double? = nil
    ) {
        self.id = id
        self.employerId = employerId
        self.type = type
        self.contractualWeeklyMinutes = contractualWeeklyMinutes
        self.grossHourlyRate = grossHourlyRate
        self.hireDateEpochDay = hireDateEpochDay
        self.payrollCutoffDay = payrollCutoffDay
        self.forfaitHoursPeriod = forfaitHoursPeriod
        self.forfaitHours = forfaitHours
        self.forfaitAnnualDays = forfaitAnnualDays
        self.monthlyGrossSalary = monthlyGrossSalary
    }
}

struct PremiumV2: Equatable {
    let id: String
    let label: String
    let amount: Double
    let periodicity: PeriodicityV2
    let taxable: Bool

    init(id: String, label: String, amount: Double, periodicity: PeriodicityV2, taxable: Bool = true) {
        self.id = id
        self.label = label
        self.amount = amount
        self.periodicity = periodicity
        self.taxable = taxable
    }
}

struct BasketV2: Equatable {
    let id: String
    let label: String
    let amount: Double
    let night: Bool

    init(id: String, label: String, amount: Double, night: Bool = false) {
        self.id = id
        self.label = label
        self.amount = amount
        self.night = night
    }
}

struct DeductionV2: Equatable {
    let id: String
    let label: String
    let amount: Double
    let recurring: Bool
}

struct OvertimeTierV2: Equatable {
    let fromMinutes: Int
    let toMinutes: Int?
    let multiplier: Double
}

struct PayrollRulesV2: Equatable {
    let weeklyRegularMinutes: Int?
    let overtimeTiers: [OvertimeTierV2]
    let nightMultiplier: Double?
    let saturdayMultiplier: Double?
    let sundayMultiplier: Double?
    let publicHolidayMultiplier: Double?

    init(
        weeklyRegularMinutes: Int? = nil,
        overtimeTiers: [OvertimeTierV2] = [],
        nightMultiplier: Double? = nil,
        saturdayMultiplier: Double? = nil,
        sundayMultiplier: Double? = nil,
        publicHolidayMultiplier: Double? = nil
    ) {
        self.weeklyRegularMinutes = weeklyRegularMinutes
        self.overtimeTiers = overtimeTiers
        self.nightMultiplier = nightMultiplier
        self.saturdayMultiplier = saturdayMultiplier
        self.sundayMultiplier = sundayMultiplier
        self.publicHolidayMultiplier = publicHolidayMultiplier
    }
}

struct PayrollWeekV2: Equatable {
    let paidMinutes: Int
    let nightMinutes: Int
    let saturdayMinutes: Int
    let sundayMinutes: Int
    let publicHolidayMinutes: Int

    init(
        paidMinutes: Int,
        nightMinutes: Int = 0,
        saturdayMinutes: Int = 0,
        sundayMinutes: Int = 0,
        publicHolidayMinutes: Int = 0
    ) {
        self.paidMinutes = paidMinutes
        self.nightMinutes = nightMinutes
        self.saturdayMinutes = saturdayMinutes
        self.sundayMinutes = sundayMinutes
        self.publicHolidayMinutes = publicHolidayMinutes
    }
}

struct PayrollResultV2: Equatable {
    let regularGross: Double
    let overtimeGross: Double
    let premiumsGross: Double
    let fixedPremiumsGross: Double
    let baskets: Double
    let grossEstimate: Double
    let deductions: Double
    let netBeforeUnknownContributions: Double
    let complementaryMinutes: Int
    let grossReliable: Bool
    let traces: [String]
}

enum PayrollEngineErrorV2: Error, Equatable {
    case missingHourlyRate
    case invalidHourlyRate
    case missingWeeklyDuration
    case invalidWeeklyDuration
    case invalidOvertimeTier
    case invalidMultiplier
    case missingMonthlyGross
    case invalidMonthlyGross
    case missingForfaitHoursPeriod
    case missingForfaitHours
    case invalidForfaitHours
    case missingForfaitAnnualDays
    case invalidForfaitAnnualDays
    case incoherentForfaitType
}

/// Miroir Swift du moteur brut PayrollEngineV2 Android.
///
/// Le moteur ne déduit aucune règle juridique : il ne consomme que des durées,
/// paliers et multiplicateurs déjà confirmés par les couches amont. Pour le temps partiel,
/// tant qu'aucune stipulation conventionnelle structurée plus précise n'est fournie,
/// le barème supplétif des heures complémentaires reste estimatif et rend le brut non fiable.
enum PayrollEngineV2 {
    static func calculate(
        contract: ContractV2,
        weeks: [PayrollWeekV2],
        rules: PayrollRulesV2,
        premiums: [PremiumV2] = [],
        baskets: [BasketV2] = [],
        deductions: [DeductionV2] = []
    ) throws -> PayrollResultV2 {
        if contract.type == .forfaitDays || contract.type == .forfaitHours {
            return try calculateForfait(
                contract: contract,
                premiums: premiums,
                baskets: baskets,
                deductions: deductions
            )
        }

        guard let rate = contract.grossHourlyRate else { throw PayrollEngineErrorV2.missingHourlyRate }
        guard rate > 0, rate.isFinite else { throw PayrollEngineErrorV2.invalidHourlyRate }

        guard let regularLimit = rules.weeklyRegularMinutes ?? contract.contractualWeeklyMinutes else {
            throw PayrollEngineErrorV2.missingWeeklyDuration
        }
        guard regularLimit > 0 else { throw PayrollEngineErrorV2.invalidWeeklyDuration }

        if contract.type == .partTime {
            return try calculatePartTime(
                contract: contract,
                weeks: weeks,
                rules: rules,
                rate: rate,
                regularLimit: regularLimit,
                premiums: premiums,
                baskets: baskets,
                deductions: deductions
            )
        }

        var regularMinutes = 0
        var overtimeGross = 0.0
        var extras = 0.0
        var traces: [String] = []

        for week in weeks {
            let paid = max(0, week.paidMinutes)
            regularMinutes += min(paid, regularLimit)

            for tier in rules.overtimeTiers {
                guard tier.fromMinutes >= regularLimit else { throw PayrollEngineErrorV2.invalidOvertimeTier }
                guard tier.multiplier >= 1, tier.multiplier.isFinite else {
                    throw PayrollEngineErrorV2.invalidMultiplier
                }
                let end = tier.toMinutes ?? Int.max
                let minutes = max(0, min(paid, end) - max(regularLimit, tier.fromMinutes))
                if minutes > 0 {
                    overtimeGross += Double(minutes) / 60.0 * rate * tier.multiplier
                }
            }

            extras += try premiumExtras(for: week, rate: rate, rules: rules)
        }

        let regularGross = Double(regularMinutes) / 60.0 * rate
        let fixed = premiums.reduce(0.0) { $0 + $1.amount }
        let basketTotal = baskets.reduce(0.0) { $0 + $1.amount }
        let gross = regularGross + overtimeGross + extras + fixed
        let deductionsTotal = max(0, deductions.reduce(0.0) { $0 + $1.amount })

        traces.append("Temps payé V2 + durée contractuelle/règles confirmées")
        if rules.overtimeTiers.isEmpty {
            traces.append("Aucune majoration d'heures supplémentaires appliquée : règle non fournie")
        }
        if !baskets.isEmpty {
            traces.append("Paniers suivis séparément du brut estimé")
        }

        return PayrollResultV2(
            regularGross: regularGross,
            overtimeGross: overtimeGross,
            premiumsGross: extras,
            fixedPremiumsGross: fixed,
            baskets: basketTotal,
            grossEstimate: gross,
            deductions: deductionsTotal,
            netBeforeUnknownContributions: max(0, gross - deductionsTotal),
            complementaryMinutes: 0,
            grossReliable: true,
            traces: traces
        )
    }

    private static func calculatePartTime(
        contract: ContractV2,
        weeks: [PayrollWeekV2],
        rules: PayrollRulesV2,
        rate: Double,
        regularLimit: Int,
        premiums: [PremiumV2],
        baskets: [BasketV2],
        deductions: [DeductionV2]
    ) throws -> PayrollResultV2 {
        guard let contractualWeeklyMinutes = contract.contractualWeeklyMinutes,
              contractualWeeklyMinutes > 0 else {
            throw PayrollEngineErrorV2.missingWeeklyDuration
        }

        var complementaryMinutes = 0
        var complementaryGross = 0.0
        var extras = 0.0
        var traces: [String] = []

        for week in weeks {
            let complementary = try PartTimeComplementaryHoursV2.calculateWeek(
                contractualMinutes: contractualWeeklyMinutes,
                paidMinutes: week.paidMinutes,
                grossHourlyRate: rate
            )
            complementaryMinutes += complementary.complementaryMinutes
            complementaryGross += complementary.grossToAdd
            traces.append(contentsOf: complementary.warnings)
            extras += try premiumExtras(for: week, rate: rate, rules: rules)
        }

        let monthlyBaseMinutes = Double(contractualWeeklyMinutes) * 52.0 / 12.0
        let regularGross = monthlyBaseMinutes / 60.0 * rate
        let fixed = premiums.reduce(0.0) { $0 + $1.amount }
        let basketTotal = baskets.reduce(0.0) { $0 + $1.amount }
        let gross = regularGross + complementaryGross + extras + fixed
        let deductionsTotal = max(0, deductions.reduce(0.0) { $0 + $1.amount })
        let provisionalComplementaryRateUsed = complementaryMinutes > 0

        traces.append("Salaire de base mensualisé temps partiel : durée contractuelle × 52/12 × taux horaire.")
        if provisionalComplementaryRateUsed {
            traces.append("Temps partiel : barème supplétif des heures complémentaires appliqué (+10 % puis +25 %) ; brut à confirmer tant qu'aucune stipulation conventionnelle structurée plus précise n'est intégrée.")
        }
        if !baskets.isEmpty {
            traces.append("Paniers suivis séparément du brut estimé")
        }

        return PayrollResultV2(
            regularGross: regularGross,
            overtimeGross: complementaryGross,
            premiumsGross: extras,
            fixedPremiumsGross: fixed,
            baskets: basketTotal,
            grossEstimate: gross,
            deductions: deductionsTotal,
            netBeforeUnknownContributions: max(0, gross - deductionsTotal),
            complementaryMinutes: complementaryMinutes,
            grossReliable: !provisionalComplementaryRateUsed,
            traces: unique(traces)
        )
    }

    private static func calculateForfait(
        contract: ContractV2,
        premiums: [PremiumV2],
        baskets: [BasketV2],
        deductions: [DeductionV2]
    ) throws -> PayrollResultV2 {
        guard let monthlyGross = contract.monthlyGrossSalary else {
            throw PayrollEngineErrorV2.missingMonthlyGross
        }
        guard monthlyGross > 0, monthlyGross.isFinite else {
            throw PayrollEngineErrorV2.invalidMonthlyGross
        }

        switch contract.type {
        case .forfaitHours:
            guard contract.forfaitHoursPeriod != nil else {
                throw PayrollEngineErrorV2.missingForfaitHoursPeriod
            }
            guard let hours = contract.forfaitHours else {
                throw PayrollEngineErrorV2.missingForfaitHours
            }
            guard hours > 0, hours.isFinite else {
                throw PayrollEngineErrorV2.invalidForfaitHours
            }
        case .forfaitDays:
            guard let days = contract.forfaitAnnualDays else {
                throw PayrollEngineErrorV2.missingForfaitAnnualDays
            }
            guard days > 0, days <= 218, days.isFinite else {
                throw PayrollEngineErrorV2.invalidForfaitAnnualDays
            }
        default:
            throw PayrollEngineErrorV2.incoherentForfaitType
        }

        let fixed = premiums.reduce(0.0) { $0 + $1.amount }
        let basketTotal = baskets.reduce(0.0) { $0 + $1.amount }
        let gross = monthlyGross + fixed
        let deductionsTotal = max(0, deductions.reduce(0.0) { $0 + $1.amount })
        var traces: [String] = []

        switch contract.type {
        case .forfaitHours:
            traces.append("Forfait heures : salaire brut mensuel convenu utilisé comme base ; les heures du forfait ne sont pas reconverties artificiellement en taux horaire.")
        case .forfaitDays:
            traces.append("Forfait jours : salaire brut mensuel convenu utilisé comme base ; aucun taux horaire n'est inventé.")
        default:
            throw PayrollEngineErrorV2.incoherentForfaitType
        }
        traces.append("Les pointages servent au suivi du temps/charge et ne recalculent pas automatiquement la rémunération forfaitaire.")
        if !baskets.isEmpty {
            traces.append("Paniers suivis séparément du brut estimé")
        }

        return PayrollResultV2(
            regularGross: monthlyGross,
            overtimeGross: 0,
            premiumsGross: 0,
            fixedPremiumsGross: fixed,
            baskets: basketTotal,
            grossEstimate: gross,
            deductions: deductionsTotal,
            netBeforeUnknownContributions: max(0, gross - deductionsTotal),
            complementaryMinutes: 0,
            grossReliable: true,
            traces: traces
        )
    }

    private static func premiumExtras(
        for week: PayrollWeekV2,
        rate: Double,
        rules: PayrollRulesV2
    ) throws -> Double {
        var extras = 0.0
        if let multiplier = rules.nightMultiplier {
            try validateMultiplier(multiplier)
            if week.nightMinutes > 0 {
                extras += Double(week.nightMinutes) / 60.0 * rate * (multiplier - 1.0)
            }
        }
        if let multiplier = rules.saturdayMultiplier {
            try validateMultiplier(multiplier)
            if week.saturdayMinutes > 0 {
                extras += Double(week.saturdayMinutes) / 60.0 * rate * (multiplier - 1.0)
            }
        }
        if let multiplier = rules.sundayMultiplier {
            try validateMultiplier(multiplier)
            if week.sundayMinutes > 0 {
                extras += Double(week.sundayMinutes) / 60.0 * rate * (multiplier - 1.0)
            }
        }
        if let multiplier = rules.publicHolidayMultiplier {
            try validateMultiplier(multiplier)
            if week.publicHolidayMinutes > 0 {
                extras += Double(week.publicHolidayMinutes) / 60.0 * rate * (multiplier - 1.0)
            }
        }
        return extras
    }

    private static func validateMultiplier(_ multiplier: Double) throws {
        guard multiplier >= 1, multiplier.isFinite else {
            throw PayrollEngineErrorV2.invalidMultiplier
        }
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
