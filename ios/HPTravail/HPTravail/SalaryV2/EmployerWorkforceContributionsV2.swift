import Foundation

/// FNAL et formation professionnelle 2026 selon la tranche d'effectif social confirmée.
enum EmployerWorkforceContributionsV2 {
    enum Band: Equatable {
        case under11
        case from11To49
        case atLeast50
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
    }

    struct Snapshot: Equatable {
        let band: Band?
        let source: String?
        let reliable: Bool
        let warnings: [String]
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
            return Snapshot(
                band: nil,
                source: nil,
                reliable: false,
                warnings: ["Effectif employeur : période invalide ; FNAL/formation non calculés."]
            )
        }

        let malformed = records.filter { record in
            record.source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                !record.effectiveFrom.isValid ||
                (record.effectiveTo?.isValid == false) ||
                (record.effectiveTo.map { $0 < record.effectiveFrom } ?? false)
        }
        guard malformed.isEmpty else {
            return Snapshot(
                band: nil,
                source: nil,
                reliable: false,
                warnings: ["Effectif employeur : une règle enregistrée est incomplète ou incohérente ; FNAL/formation non calculés."]
            )
        }

        let active = records.filter { record in
            period >= record.effectiveFrom && (record.effectiveTo == nil || period <= record.effectiveTo!)
        }
        guard !active.isEmpty else {
            return Snapshot(
                band: nil,
                source: nil,
                reliable: false,
                warnings: ["Effectif employeur : tranche <11 / 11–49 / ≥50 à confirmer pour \(period.label) ; FNAL/formation incomplets."]
            )
        }
        guard active.count == 1, let selected = active.first else {
            return Snapshot(
                band: nil,
                source: nil,
                reliable: false,
                warnings: ["Effectif employeur : plusieurs tranches se chevauchent sur la période ; FNAL/formation bloqués."]
            )
        }

        return Snapshot(
            band: selected.band,
            source: selected.source,
            reliable: true,
            warnings: []
        )
    }

    static func calculate(
        grossSocial: Double,
        applicableMonthlyCeiling: Double?,
        year: Int,
        band: Band?
    ) -> Result {
        guard year == 2026 else {
            return incomplete("FNAL/formation : barème non intégré pour \(year).")
        }
        guard let band else {
            return incomplete("FNAL/formation : tranche d'effectif à confirmer ; coût employeur incomplet.")
        }
        guard let ceiling = applicableMonthlyCeiling,
              ceiling.isFinite,
              ceiling >= 0 else {
            return incomplete("FNAL/formation : plafond social applicable indisponible.")
        }
        guard grossSocial.isFinite, grossSocial >= 0 else {
            return incomplete("FNAL/formation : assiette brute sociale invalide ; aucun montant patronal n'est calculé.")
        }

        let fnalBase: Double
        let fnalRate: Double
        let trainingRate: Double
        switch band {
        case .under11:
            fnalBase = min(grossSocial, ceiling)
            fnalRate = 0.0010
            trainingRate = 0.0055
        case .from11To49:
            fnalBase = min(grossSocial, ceiling)
            fnalRate = 0.0010
            trainingRate = 0.0100
        case .atLeast50:
            fnalBase = grossSocial
            fnalRate = 0.0050
            trainingRate = 0.0100
        }

        let fnal = fnalBase * fnalRate
        let training = grossSocial * trainingRate
        return Result(
            fnalAmount: fnal,
            trainingAmount: training,
            totalEmployerAmount: fnal + training,
            complete: true,
            warnings: []
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
}
