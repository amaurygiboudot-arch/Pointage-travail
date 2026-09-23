import Foundation

/// Date civile pure utilisée par les calculs de paie V2.
///
/// Elle évite toute dépendance au fuseau horaire pour les proratas mensuels.
struct PayrollCivilDateV2: Codable, Equatable, Hashable, Comparable {
    let year: Int
    let month: Int
    let day: Int

    init?(year: Int, month: Int, day: Int) {
        guard let maximumDay = Self.daysInMonth(year: year, month: month),
              (1...maximumDay).contains(day) else { return nil }
        self.year = year
        self.month = month
        self.day = day
    }

    static func < (lhs: PayrollCivilDateV2, rhs: PayrollCivilDateV2) -> Bool {
        if lhs.year != rhs.year { return lhs.year < rhs.year }
        if lhs.month != rhs.month { return lhs.month < rhs.month }
        return lhs.day < rhs.day
    }

    var monthIndex: Int { year * 12 + month - 1 }

    static func daysInMonth(year: Int, month: Int) -> Int? {
        guard (1...12).contains(month) else { return nil }
        switch month {
        case 4, 6, 9, 11:
            return 30
        case 2:
            let leap = year.isMultiple(of: 400) || (year.isMultiple(of: 4) && !year.isMultiple(of: 100))
            return leap ? 29 : 28
        default:
            return 31
        }
    }
}

/// Source unique iOS du plafond de Sécurité sociale utilisé par Salaire V2.
///
/// Référence 2026 : PMSS 4 005 €. Les réductions ne sont appliquées que lorsque
/// les données nécessaires sont explicitement disponibles ; inconnu n'est jamais
/// interprété comme zéro.
enum SocialSecurityCeilingV2 {
    private static let pmss2026 = 4_005.0
    private static let legalWeeklyMinutes = 35 * 60
    private static let fullTimeAnnualDaysReference = 218.0

    struct Input: Equatable {
        let period: YearMonthV2
        let contractType: ContractTypeV2?
        let contractualWeeklyMinutes: Int?
        let complementaryMinutes: Int?
        let entryDate: PayrollCivilDateV2?
        /// nil signifie contrat considéré en cours à la fin du mois.
        let exitDate: PayrollCivilDateV2?
        /// nil signifie que la source ne permet pas de connaître le nombre sans l'inventer.
        let unpaidAbsenceDays: Int?
        let forfaitAnnualDays: Double?

        init(
            period: YearMonthV2,
            contractType: ContractTypeV2?,
            contractualWeeklyMinutes: Int?,
            complementaryMinutes: Int? = nil,
            entryDate: PayrollCivilDateV2?,
            exitDate: PayrollCivilDateV2? = nil,
            unpaidAbsenceDays: Int?,
            forfaitAnnualDays: Double? = nil
        ) {
            self.period = period
            self.contractType = contractType
            self.contractualWeeklyMinutes = contractualWeeklyMinutes
            self.complementaryMinutes = complementaryMinutes
            self.entryDate = entryDate
            self.exitDate = exitDate
            self.unpaidAbsenceDays = unpaidAbsenceDays
            self.forfaitAnnualDays = forfaitAnnualDays
        }
    }

    struct Snapshot: Equatable {
        let fullMonthly: Double
        let applicableMonthly: Double
        let fourTimesApplicable: Double
        let eightTimesApplicable: Double
        let presenceRatio: Double
        let workTimeRatio: Double
        let complete: Bool
        let warnings: [String]
    }

    static func fullMonthly(year: Int) -> Double? {
        year == 2026 ? pmss2026 : nil
    }

    static func calculate(_ input: Input) -> Snapshot {
        guard let fullMonthly = fullMonthly(year: input.period.year) else {
            return Snapshot(
                fullMonthly: 0,
                applicableMonthly: 0,
                fourTimesApplicable: 0,
                eightTimesApplicable: 0,
                presenceRatio: 0,
                workTimeRatio: 0,
                complete: false,
                warnings: ["Plafond de Sécurité sociale : barème non intégré pour \(input.period.year)."]
            )
        }

        guard let monthDays = PayrollCivilDateV2.daysInMonth(
            year: input.period.year,
            month: input.period.month
        ) else {
            return Snapshot(
                fullMonthly: fullMonthly,
                applicableMonthly: 0,
                fourTimesApplicable: 0,
                eightTimesApplicable: 0,
                presenceRatio: 0,
                workTimeRatio: 0,
                complete: false,
                warnings: ["Plafond SS : mois de paie invalide ; calcul bloqué."]
            )
        }

        let periodIndex = input.period.year * 12 + input.period.month - 1
        var warnings: [String] = []
        var complete = true

        let startDay: Int
        if let entry = input.entryDate {
            if entry.monthIndex < periodIndex {
                startDay = 1
            } else if entry.monthIndex == periodIndex {
                startDay = entry.day
            } else {
                startDay = monthDays + 1
            }
        } else {
            startDay = 1
            complete = false
            warnings.append(
                "Plafond SS : date d'entrée absente, aucune réduction pour une éventuelle entrée en cours de mois n'est inventée."
            )
        }

        let endDay: Int
        if let exit = input.exitDate {
            if exit.monthIndex < periodIndex {
                endDay = 0
            } else if exit.monthIndex == periodIndex {
                endDay = exit.day
            } else {
                endDay = monthDays
            }
        } else {
            endDay = monthDays
        }

        let employedDays = max(0, endDay - startDay + 1)
        let absenceDays: Int
        if let raw = input.unpaidAbsenceDays {
            if raw < 0 || raw > employedDays {
                complete = false
                absenceDays = 0
                warnings.append(
                    "Plafond SS : nombre de jours d'absence non rémunérée incohérent (\(raw) pour \(employedDays) jour(s) d'emploi) ; aucune réduction d'absence n'est appliquée automatiquement."
                )
            } else {
                absenceDays = raw
            }
        } else {
            complete = false
            absenceDays = 0
            warnings.append(
                "Plafond SS : jours d'absence non rémunérée non fiabilisés ; aucune réduction d'absence n'est appliquée automatiquement."
            )
        }

        let ceilingDays = max(0, employedDays - absenceDays)
        let presenceRatio = Double(ceilingDays) / Double(monthDays)
        if absenceDays > 0 {
            warnings.append("Plafond SS réduit de \(absenceDays) jour(s) d'absence non rémunérée.")
        }

        let workTimeRatio: Double
        switch input.contractType {
        case .partTime:
            guard let weekly = input.contractualWeeklyMinutes, weekly > 0 else {
                complete = false
                workTimeRatio = 1
                warnings.append("Plafond SS temps partiel : durée contractuelle absente, réduction non calculable.")
                break
            }
            if presenceRatio <= 0 {
                workTimeRatio = 0
                break
            }
            let complementary: Int
            if let raw = input.complementaryMinutes {
                if raw < 0 {
                    complete = false
                    complementary = 0
                    warnings.append(
                        "Plafond SS temps partiel : heures complémentaires incohérentes (\(raw) min) ; aucune heure complémentaire n'est appliquée automatiquement."
                    )
                } else {
                    complementary = raw
                }
            } else {
                complete = false
                complementary = 0
                warnings.append(
                    "Plafond SS temps partiel : heures complémentaires du mois inconnues, calcul conservateur sans heures complémentaires."
                )
            }
            let contractualMonthly = Double(weekly) * 52.0 / 12.0
            let legalMonthly = Double(legalWeeklyMinutes) * 52.0 / 12.0
            let contractualDuringPresence = contractualMonthly * presenceRatio
            let legalDuringPresence = legalMonthly * presenceRatio
            workTimeRatio = min(1, max(0, (contractualDuringPresence + Double(complementary)) / legalDuringPresence))

        case .forfaitDays:
            guard let days = input.forfaitAnnualDays, days.isFinite, days > 0 else {
                complete = false
                workTimeRatio = 1
                warnings.append(
                    "Plafond SS forfait jours : nombre annuel de jours absent, réduction éventuelle non calculée."
                )
                break
            }
            workTimeRatio = min(1, max(0, days / fullTimeAnnualDaysReference))

        case .forfaitHours:
            // Un forfait heures n'est pas automatiquement assimilé à un temps partiel.
            workTimeRatio = 1

        default:
            workTimeRatio = 1
        }

        let applicable = min(fullMonthly, max(0, fullMonthly * presenceRatio * workTimeRatio))
        if applicable + 0.01 < fullMonthly {
            warnings.append(
                "Plafond SS \(input.period.year) appliqué : \(euros(applicable)) € au lieu de \(euros(fullMonthly)) €."
            )
        }

        return Snapshot(
            fullMonthly: fullMonthly,
            applicableMonthly: applicable,
            fourTimesApplicable: applicable * 4,
            eightTimesApplicable: applicable * 8,
            presenceRatio: presenceRatio,
            workTimeRatio: workTimeRatio,
            complete: complete,
            warnings: unique(warnings)
        )
    }

    private static func euros(_ value: Double) -> String {
        String(format: "%.2f", locale: Locale(identifier: "fr_FR"), value)
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
