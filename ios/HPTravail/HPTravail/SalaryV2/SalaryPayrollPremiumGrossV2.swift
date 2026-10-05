import Foundation

/// Propriétaire unique iOS de la valorisation monétaire des minutes majorées déjà qualifiées.
///
/// Cette couche ne choisit ni les minutes concernées ni les multiplicateurs. Elle consomme
/// uniquement une semaine de paie et des règles déjà prouvées.
enum SalaryPayrollPremiumGrossV2 {
    static func calculate(
        week: PayrollWeekV2,
        grossHourlyRate: Double,
        rules: PayrollRulesV2
    ) throws -> Double {
        guard grossHourlyRate.isFinite, grossHourlyRate > 0 else {
            throw PayrollEngineErrorV2.invalidHourlyRate
        }

        var extras = 0.0
        if let multiplier = rules.nightMultiplier {
            try validate(multiplier)
            if week.nightMinutes > 0 {
                extras += Double(week.nightMinutes) / 60.0
                    * grossHourlyRate
                    * (multiplier - 1.0)
            }
        }
        if let multiplier = rules.saturdayMultiplier {
            try validate(multiplier)
            if week.saturdayMinutes > 0 {
                extras += Double(week.saturdayMinutes) / 60.0
                    * grossHourlyRate
                    * (multiplier - 1.0)
            }
        }
        if let multiplier = rules.sundayMultiplier {
            try validate(multiplier)
            if week.sundayMinutes > 0 {
                extras += Double(week.sundayMinutes) / 60.0
                    * grossHourlyRate
                    * (multiplier - 1.0)
            }
        }
        if let multiplier = rules.publicHolidayMultiplier {
            try validate(multiplier)
            if week.publicHolidayMinutes > 0 {
                extras += Double(week.publicHolidayMinutes) / 60.0
                    * grossHourlyRate
                    * (multiplier - 1.0)
            }
        }

        guard extras.isFinite, extras >= 0 else {
            throw PayrollEngineErrorV2.invalidMultiplier
        }
        return extras
    }

    private static func validate(_ multiplier: Double) throws {
        guard multiplier.isFinite, multiplier >= 1 else {
            throw PayrollEngineErrorV2.invalidMultiplier
        }
    }
}
