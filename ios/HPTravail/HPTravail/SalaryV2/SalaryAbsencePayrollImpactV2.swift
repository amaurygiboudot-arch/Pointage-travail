import Foundation

enum SalaryAbsenceDecisionStatusV2: String, Equatable {
    case confirmed = "CONFIRMED"
    case toConfirm = "TO_CONFIRM"
}

enum SalaryAbsenceTreatmentV2: String, Equatable {
    case fullyMaintained = "FULLY_MAINTAINED"
    case partiallyMaintained = "PARTIALLY_MAINTAINED"
    case unpaid = "UNPAID"
    case toConfirm = "TO_CONFIRM"
}

/// Fait d'absence minimal consommable par la paie iOS.
///
/// `end` est exclusif. Une absence non rémunérée ne peut réduire le plafond SS que si
/// `fullDay` est vrai et si la source entière des absences est fiable.
struct SalaryAbsenceFactV2: Equatable {
    let id: String
    let employerId: String?
    let type: String
    let start: Date
    let end: Date
    let salaryTreatment: SalaryAbsenceTreatmentV2
    let fullDay: Bool
    let status: SalaryAbsenceDecisionStatusV2

    init(
        id: String,
        employerId: String?,
        type: String,
        start: Date,
        end: Date,
        salaryTreatment: SalaryAbsenceTreatmentV2 = .toConfirm,
        fullDay: Bool = false,
        status: SalaryAbsenceDecisionStatusV2 = .confirmed
    ) {
        self.id = id
        self.employerId = employerId
        self.type = type
        self.start = start
        self.end = end
        self.salaryTreatment = salaryTreatment
        self.fullDay = fullDay
        self.status = status
    }
}

struct SalaryAbsencePayrollImpactSnapshotV2: Equatable {
    /// nil = nombre de jours réducteurs du plafond SS non certifiable.
    let unpaidFullCalendarDays: Int?
    let hasUnpaidAbsence: Bool
    let hasCompensatedAbsence: Bool
    let requiresPayrollReview: Bool
    let warnings: [String]
}

/// Port iOS fail-closed du contrat Android `AbsencePayrollImpactV2`.
///
/// Seules les journées complètes, confirmées et explicitement non rémunérées sont comptées.
/// Une journée qui contient aussi un pointage de la même entreprise est exclue du prorata.
/// Les absences maintenues/indemnisées restent à contrôler sans inventer IJSS ou maintien.
enum SalaryAbsencePayrollImpactV2 {
    static let typeUnpaid = "ABSENCE_NON_REMUNEREE"
    static let typeSickness = "ARRET_MALADIE"
    static let typePaidLeave = "CONGE_PAYE"
    static let typeWorkAccident = "ACCIDENT_TRAVAIL"
    static let typeCommutingAccident = "ACCIDENT_TRAJET"
    static let typeOccupationalDisease = "MALADIE_PROFESSIONNELLE"
    static let typeParental = "MATERNITE_PATERNITE"
    static let typeOther = "AUTRE"

    static let unreliableAbsenceSourceWarning =
        "Absences : stockage local illisible, absent ou incohérent ; aucune absence n'est supposée inexistante et le calcul de paie reste à confirmer."
    static let unreliableRuntimeWarning =
        "Pointages V2 : historique local non fiable ; aucun jour travaillé n'est supposé absent et le calcul de paie reste à confirmer."

    static func forMonth(
        absences: [SalaryAbsenceFactV2],
        period: YearMonthV2,
        acceptedEmployerIds: Set<String>,
        workSessions: [SalarySessionFactV2],
        absenceSourceReliable: Bool,
        workSourceReliable: Bool,
        calendar inputCalendar: Calendar = .current
    ) -> SalaryAbsencePayrollImpactSnapshotV2 {
        guard absenceSourceReliable else {
            return blocked([unreliableAbsenceSourceWarning])
        }
        guard workSourceReliable else {
            return blocked([unreliableRuntimeWarning])
        }

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = inputCalendar.timeZone
        calendar.locale = inputCalendar.locale

        guard let month = monthInterval(period, calendar: calendar) else {
            return blocked(["Absences : période civile invalide ; impact paie non calculable."])
        }

        let workedDays = workedCalendarDays(
            workSessions,
            acceptedEmployerIds: acceptedEmployerIds,
            month: month,
            calendar: calendar
        )

        var unpaidDays = Set<PayrollCivilDateV2>()
        var unpaidDaysReliable = true
        var hasUnpaid = false
        var hasCompensated = false
        var requiresReview = false
        var warnings: [String] = []

        for absence in absences {
            if !acceptedEmployerIds.isEmpty,
               !acceptedEmployerIds.contains(normalizedEmployerId(absence.employerId) ?? "") {
                continue
            }

            guard absence.start.timeIntervalSince1970.isFinite,
                  absence.end.timeIntervalSince1970.isFinite,
                  absence.end > absence.start else {
                unpaidDaysReliable = false
                requiresReview = true
                warnings.append(
                    "Absence avec période invalide : le nombre de jours réducteurs du plafond SS est inconnu."
                )
                continue
            }

            guard absence.start < month.end, absence.end > month.start else {
                continue
            }

            guard absence.status == .confirmed else {
                unpaidDaysReliable = false
                requiresReview = true
                warnings.append(
                    "Absence à confirmer sur cette période : aucun impact automatique sur la paie."
                )
                continue
            }

            switch absence.salaryTreatment {
            case .toConfirm:
                unpaidDaysReliable = false
                requiresReview = true
                warnings.append(
                    "\(label(absence.type)) : maintien de salaire à confirmer avant le calcul précis."
                )
                continue

            case .fullyMaintained, .partiallyMaintained:
                if absence.type != typeUnpaid {
                    hasCompensated = true
                    requiresReview = true
                    warnings.append(compensatedWarning(absence))
                }
                continue

            case .unpaid:
                break
            }

            if absence.type == typePaidLeave {
                unpaidDaysReliable = false
                requiresReview = true
                warnings.append(
                    "Congé payé déclaré sans maintien employeur : traitement incohérent à confirmer. Aucun jour n'est retiré automatiquement du plafond SS."
                )
                continue
            }

            hasUnpaid = true
            requiresReview = true

            if medicallyCompensatedTypes.contains(absence.type) {
                warnings.append(
                    "\(label(absence.type)) sans maintien employeur : IJSS/indemnisation éventuelle à intégrer avant de calculer le net exact."
                )
            }

            guard absence.fullDay else {
                warnings.append(
                    "Absence non rémunérée partielle : le plafond SS n'est pas réduit automatiquement."
                )
                continue
            }

            var day = calendar.startOfDay(for: max(absence.start, month.start))
            let lastExclusive = min(absence.end, month.end)
            while day < lastExclusive {
                guard let civil = civilDate(day, calendar: calendar),
                      let next = calendar.date(byAdding: .day, value: 1, to: day),
                      next > day else {
                    unpaidDaysReliable = false
                    requiresReview = true
                    warnings.append(
                        "Absence : calendrier local impossible à parcourir de façon fiable."
                    )
                    break
                }

                if workedDays.contains(civil) {
                    warnings.append(
                        "Absence complète et pointage le \(formatted(civil)) : journée exclue du prorata automatique."
                    )
                } else {
                    unpaidDays.insert(civil)
                }
                day = next
            }
        }

        if !unpaidDays.isEmpty, unpaidDaysReliable {
            warnings.append(
                "\(unpaidDays.count) jour(s) d'absence non rémunérée complète pris en compte pour le plafond SS."
            )
        }
        if !unpaidDaysReliable {
            warnings.append(
                "Plafond SS : nombre de jours d'absence non rémunérée non certifiable ; aucune réduction d'absence ne doit être déduite d'un faux zéro."
            )
        }

        return SalaryAbsencePayrollImpactSnapshotV2(
            unpaidFullCalendarDays: unpaidDaysReliable ? unpaidDays.count : nil,
            hasUnpaidAbsence: hasUnpaid,
            hasCompensatedAbsence: hasCompensated,
            requiresPayrollReview: requiresReview,
            warnings: unique(warnings)
        )
    }

    static func label(_ type: String) -> String {
        switch type {
        case typeUnpaid: return "Absence non rémunérée"
        case typeSickness: return "Arrêt maladie"
        case typePaidLeave: return "Congé payé"
        case typeWorkAccident: return "Accident du travail"
        case typeCommutingAccident: return "Accident de trajet"
        case typeOccupationalDisease: return "Maladie professionnelle"
        case typeParental: return "Maternité / paternité"
        default: return "Absence"
        }
    }

    private static let medicallyCompensatedTypes: Set<String> = [
        typeSickness,
        typeWorkAccident,
        typeCommutingAccident,
        typeOccupationalDisease,
        typeParental
    ]

    private static func compensatedWarning(_ absence: SalaryAbsenceFactV2) -> String {
        let level = absence.salaryTreatment == .partiallyMaintained
            ? "maintien partiel"
            : "maintien"
        switch absence.type {
        case typeSickness:
            return "Arrêt maladie avec \(level) : IJSS, carence, subrogation et règle de maintien restent à vérifier avant le calcul précis."
        case typePaidLeave:
            return "Congé payé : l'indemnité doit être contrôlée selon la méthode applicable ; aucun montant n'est inventé."
        case typeWorkAccident:
            return "Accident du travail avec \(level) : indemnisation et maintien applicables restent à vérifier."
        case typeCommutingAccident:
            return "Accident de trajet avec \(level) : indemnisation et maintien applicables restent à vérifier séparément de l'accident du travail."
        case typeOccupationalDisease:
            return "Maladie professionnelle avec \(level) : indemnisation et maintien applicables restent à vérifier séparément de la maladie ordinaire."
        case typeParental:
            return "Maternité / paternité avec \(level) : indemnisation et éventuel maintien employeur restent à vérifier."
        default:
            return "Absence avec \(level) : traitement de paie à vérifier avant le calcul précis."
        }
    }

    private static func workedCalendarDays(
        _ sessions: [SalarySessionFactV2],
        acceptedEmployerIds: Set<String>,
        month: DateInterval,
        calendar: Calendar
    ) -> Set<PayrollCivilDateV2> {
        var result = Set<PayrollCivilDateV2>()

        for session in sessions {
            if !acceptedEmployerIds.isEmpty,
               !acceptedEmployerIds.contains(normalizedEmployerId(session.employerId) ?? "") {
                continue
            }
            guard let exit = session.exit,
                  exit > session.entry,
                  session.entry < month.end,
                  exit > month.start else {
                continue
            }

            let clippedStart = max(session.entry, month.start)
            let clippedEnd = min(exit, month.end)
            guard clippedEnd > clippedStart else { continue }

            var day = calendar.startOfDay(for: clippedStart)
            let lastInstant = clippedEnd.addingTimeInterval(-0.001)
            let lastDay = calendar.startOfDay(for: lastInstant)

            while day <= lastDay {
                if let civil = civilDate(day, calendar: calendar) {
                    result.insert(civil)
                }
                guard let next = calendar.date(byAdding: .day, value: 1, to: day),
                      next > day else {
                    break
                }
                day = next
            }
        }
        return result
    }

    private static func monthInterval(
        _ period: YearMonthV2,
        calendar: Calendar
    ) -> DateInterval? {
        let nextYear: Int
        let nextMonth: Int
        if period.month == 12 {
            guard period.year < Int.max else { return nil }
            nextYear = period.year + 1
            nextMonth = 1
        } else {
            nextYear = period.year
            nextMonth = period.month + 1
        }

        guard let start = calendar.date(
            from: DateComponents(
                calendar: calendar,
                timeZone: calendar.timeZone,
                year: period.year,
                month: period.month,
                day: 1,
                hour: 0,
                minute: 0,
                second: 0
            )
        ),
        let end = calendar.date(
            from: DateComponents(
                calendar: calendar,
                timeZone: calendar.timeZone,
                year: nextYear,
                month: nextMonth,
                day: 1,
                hour: 0,
                minute: 0,
                second: 0
            )
        ),
        end > start else {
            return nil
        }
        return DateInterval(start: start, end: end)
    }

    private static func civilDate(
        _ date: Date,
        calendar: Calendar
    ) -> PayrollCivilDateV2? {
        let components = calendar.dateComponents([.year, .month, .day], from: date)
        guard let year = components.year,
              let month = components.month,
              let day = components.day else {
            return nil
        }
        return PayrollCivilDateV2(year: year, month: month, day: day)
    }

    private static func formatted(_ date: PayrollCivilDateV2) -> String {
        String(format: "%02d/%02d/%04d", date.day, date.month, date.year)
    }

    private static func normalizedEmployerId(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private static func blocked(_ warnings: [String]) -> SalaryAbsencePayrollImpactSnapshotV2 {
        SalaryAbsencePayrollImpactSnapshotV2(
            unpaidFullCalendarDays: nil,
            hasUnpaidAbsence: false,
            hasCompensatedAbsence: false,
            requiresPayrollReview: true,
            warnings: unique(warnings)
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
