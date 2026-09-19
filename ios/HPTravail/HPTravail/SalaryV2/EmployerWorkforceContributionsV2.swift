import Foundation

/// Cotisations patronales liées à l'effectif en 2026.
///
/// La tranche d'effectif ne suffit pas à elle seule à déterminer le FNAL pour tous les employeurs :
/// certains employeurs agricoles/cooperatifs relèvent du taux plafonné de 0,10 % même avec un
/// effectif d'au moins 50 salariés. L'applicabilité de la contribution formation peut elle aussi
/// dépendre de la situation du salarié. Les deux faits restent donc explicites et fail-closed.
enum EmployerWorkforceContributionsV2 {
    enum Band: Equatable {
        case under11
        case from11To49
        case atLeast50
    }

    enum FnalTreatment: Equatable {
        case capped0Point1Percent
        case uncapped0Point5Percent
    }

    enum TrainingTreatment: Equatable {
        case standard
        case exemptConfirmed
    }

    struct Period: Equatable, Comparable {
        let year: Int
        let month: Int

        var isValid: Bool { year > 0 && (1...12).contains(month) }

        static func < (lhs: Period, rhs: Period) -> Bool {
            lhs.year == rhs.year ? lhs.month < rhs.month : lhs.year < rhs.year
        }

        var label: String { String(format: "%02d/%04d", month, year) }
    }

    struct Record: Equatable {
        let id: String
        let band: Band
        let effectiveFrom: Period
        let effectiveTo: Period?
        let source: String
        let fnalTreatment: FnalTreatment?

        init(
            id: String,
            band: Band,
            effectiveFrom: Period,
            effectiveTo: Period? = nil,
            source: String,
            fnalTreatment: FnalTreatment? = nil
        ) {
            self.id = id
            self.band = band
            self.effectiveFrom = effectiveFrom
            self.effectiveTo = effectiveTo
            self.source = source
            self.fnalTreatment = fnalTreatment
        }
    }

    struct Snapshot: Equatable {
        let band: Band?
        let source: String?
        let reliable: Bool
        let warnings: [String]
        let fnalTreatment: FnalTreatment?
    }

    struct Result: Equatable {
        let fnalAmount: Double?
        let trainingAmount: Double?
        let totalEmployerAmount: Double?
        let complete: Bool
        let warnings: [String]
    }

    static func resolve(records: [Record], period: Period) -> Snapshot {
        guard period.isValid else {
            return blockedSnapshot("Effectif employeur : période invalide ; FNAL/formation non calculés.")
        }

        let malformed = records.filter { record in
            record.source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                !record.effectiveFrom.isValid ||
                (record.effectiveTo?.isValid == false) ||
                (record.effectiveTo.map { $0 < record.effectiveFrom } ?? false)
        }
        guard malformed.isEmpty else {
            return blockedSnapshot("Effectif employeur : une règle enregistrée est incomplète ou incohérente ; FNAL/formation non calculés.")
        }

        let active = records.filter { record in
            period >= record.effectiveFrom && (record.effectiveTo == nil || period <= record.effectiveTo!)
        }
        guard !active.isEmpty else {
            return blockedSnapshot("Effectif employeur : tranche <11 / 11–49 / >=50 et régime FNAL à confirmer pour \(period.label) ; cotisations incomplètes.")
        }
        guard active.count == 1, let selected = active.first else {
            return blockedSnapshot("Effectif employeur : plusieurs règles se chevauchent sur la période ; FNAL/formation bloqués.")
        }
        guard let fnalTreatment = selected.fnalTreatment else {
            return Snapshot(
                band: selected.band,
                source: selected.source,
                reliable: false,
                warnings: ["FNAL : régime 0,10 % plafonné / 0,50 % déplafonné à confirmer ; la tranche d'effectif seule ne suffit pas pour tous les employeurs."],
                fnalTreatment: nil
            )
        }

        return Snapshot(
            band: selected.band,
            source: selected.source,
            reliable: true,
            warnings: [],
            fnalTreatment: fnalTreatment
        )
    }

    static func calculate(
        grossSocial: Double,
        applicableMonthlyCeiling: Double?,
        year: Int,
        band: Band?,
        fnalTreatment: FnalTreatment? = nil,
        trainingTreatment: TrainingTreatment? = nil
    ) -> Result {
        guard year == 2026 else {
            return incomplete("FNAL/formation : barème non intégré pour \(year).")
        }
        guard grossSocial.isFinite, grossSocial >= 0 else {
            return incomplete("FNAL/formation : assiette brute sociale invalide ; aucun montant patronal n'est calculé.")
        }

        var warnings: [String] = []

        let fnal: Double?
        switch fnalTreatment {
        case nil:
            warnings.append("FNAL : régime 0,10 % plafonné / 0,50 % déplafonné à confirmer ; aucun taux n'est déduit du seul effectif.")
            fnal = nil
        case .capped0Point1Percent:
            guard let ceiling = applicableMonthlyCeiling, ceiling.isFinite, ceiling >= 0 else {
                warnings.append("FNAL : plafond social applicable indisponible pour le régime plafonné.")
                fnal = nil
                break
            }
            fnal = min(grossSocial, ceiling) * 0.0010
        case .uncapped0Point5Percent:
            fnal = grossSocial * 0.0050
        }

        let training: Double?
        switch trainingTreatment {
        case nil:
            warnings.append("Formation professionnelle : applicabilité/exonération à confirmer pour la rémunération considérée.")
            training = nil
        case .exemptConfirmed:
            training = 0
        case .standard:
            switch band {
            case nil:
                warnings.append("Formation professionnelle : tranche d'effectif <11 / >=11 à confirmer.")
                training = nil
            case .under11:
                training = grossSocial * 0.0055
            case .from11To49, .atLeast50:
                training = grossSocial * 0.0100
            }
        }

        let complete = fnal != nil && training != nil
        return Result(
            fnalAmount: fnal,
            trainingAmount: training,
            totalEmployerAmount: complete ? fnal! + training! : nil,
            complete: complete,
            warnings: unique(warnings)
        )
    }

    private static func blockedSnapshot(_ warning: String) -> Snapshot {
        Snapshot(
            band: nil,
            source: nil,
            reliable: false,
            warnings: [warning],
            fnalTreatment: nil
        )
    }

    private static func incomplete(_ warning: String) -> Result {
        Result(
            fnalAmount: nil,
            trainingAmount: nil,
            totalEmployerAmount: nil,
            complete: false,
            warnings: [warning]
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
