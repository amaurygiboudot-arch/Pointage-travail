import Foundation

/// Faits annuels nécessaires avant d'autoriser le calcul RGDU annuel standard.
///
/// Aucune stabilité annuelle n'est déduite des seuls paramètres courants : l'absence
/// de fait explicite reste inconnue et bloque le calcul automatique.
enum EmployerGeneralReductionAnnualContextV2 {
    struct Record: Equatable {
        let id: String
        let year: Int
        let fullCalendarYearPresent: Bool
        let standardCommonLawCaseConfirmed: Bool
        let homogeneousAnnualParametersConfirmed: Bool?
        let source: String
        let confirmedFnalTreatment: EmployerWorkforceContributionsV2.FnalTreatment?
        let confirmedContractType: ContractTypeV2?
        let confirmedContractualWeeklyMinutes: Int?

        init(
            id: String,
            year: Int,
            fullCalendarYearPresent: Bool,
            standardCommonLawCaseConfirmed: Bool,
            homogeneousAnnualParametersConfirmed: Bool? = nil,
            source: String,
            confirmedFnalTreatment: EmployerWorkforceContributionsV2.FnalTreatment? = nil,
            confirmedContractType: ContractTypeV2? = nil,
            confirmedContractualWeeklyMinutes: Int? = nil
        ) {
            self.id = id
            self.year = year
            self.fullCalendarYearPresent = fullCalendarYearPresent
            self.standardCommonLawCaseConfirmed = standardCommonLawCaseConfirmed
            self.homogeneousAnnualParametersConfirmed = homogeneousAnnualParametersConfirmed
            self.source = source
            self.confirmedFnalTreatment = confirmedFnalTreatment
            self.confirmedContractType = confirmedContractType
            self.confirmedContractualWeeklyMinutes = confirmedContractualWeeklyMinutes
        }
    }

    struct Snapshot: Equatable {
        let fullCalendarYearPresent: Bool?
        let standardCommonLawCaseConfirmed: Bool?
        let homogeneousAnnualParametersConfirmed: Bool?
        let source: String?
        let reliable: Bool
        let warnings: [String]
        let confirmedFnalTreatment: EmployerWorkforceContributionsV2.FnalTreatment?
        let confirmedContractType: ContractTypeV2?
        let confirmedContractualWeeklyMinutes: Int?
    }

    static func resolve(records: [Record], year: Int) -> Snapshot {
        let malformed = records.filter {
            $0.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            $0.source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            $0.year <= 0 ||
            ($0.confirmedContractualWeeklyMinutes != nil && $0.confirmedContractualWeeklyMinutes! <= 0)
        }
        guard malformed.isEmpty else {
            return blocked("RGDU annuelle : un contexte enregistré est incomplet ou incohérent ; calcul automatique bloqué.")
        }

        let active = records.filter { $0.year == year }
        guard !active.isEmpty else {
            return blocked("RGDU annuelle : contexte factuel à confirmer pour \(year) (année complète, droit commun et stabilité des paramètres).")
        }
        guard active.count == 1, let selected = active.first else {
            return blocked("RGDU annuelle : plusieurs contextes factuels existent pour \(year) ; calcul automatique bloqué.")
        }

        var warnings: [String] = []
        if selected.homogeneousAnnualParametersConfirmed == nil {
            warnings.append("RGDU annuelle : stabilité des paramètres à confirmer pour \(year).")
        }
        if selected.homogeneousAnnualParametersConfirmed == true {
            if selected.confirmedFnalTreatment == nil {
                warnings.append("RGDU annuelle : régime FNAL/logement annuel exact à confirmer pour \(year).")
            }
            if selected.confirmedContractType == nil {
                warnings.append("RGDU annuelle : type de contrat annuel exact à confirmer pour \(year).")
            }
            if selected.confirmedContractualWeeklyMinutes == nil {
                warnings.append("RGDU annuelle : durée contractuelle hebdomadaire annuelle exacte à confirmer pour \(year).")
            }
        }

        return Snapshot(
            fullCalendarYearPresent: selected.fullCalendarYearPresent,
            standardCommonLawCaseConfirmed: selected.standardCommonLawCaseConfirmed,
            homogeneousAnnualParametersConfirmed: selected.homogeneousAnnualParametersConfirmed,
            source: selected.source,
            reliable: true,
            warnings: unique(warnings),
            confirmedFnalTreatment: selected.confirmedFnalTreatment,
            confirmedContractType: selected.confirmedContractType,
            confirmedContractualWeeklyMinutes: selected.confirmedContractualWeeklyMinutes
        )
    }

    private static func blocked(_ warning: String) -> Snapshot {
        Snapshot(
            fullCalendarYearPresent: nil,
            standardCommonLawCaseConfirmed: nil,
            homogeneousAnnualParametersConfirmed: nil,
            source: nil,
            reliable: false,
            warnings: [warning],
            confirmedFnalTreatment: nil,
            confirmedContractType: nil,
            confirmedContractualWeeklyMinutes: nil
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
