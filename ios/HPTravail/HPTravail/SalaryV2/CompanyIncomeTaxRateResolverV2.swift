import Foundation

/// Résout le taux personnel de prélèvement à la source confirmé pour un mois.
///
/// Dès qu'un taux daté existe, un mois non couvert reste bloqué : aucun ancien taux sans
/// période n'est réutilisé silencieusement.
enum CompanyIncomeTaxRateResolverV2 {
    struct Record {
        let id: String
        let ratePercent: Double
        let effectiveFrom: YearMonthV2?
        let effectiveTo: YearMonthV2?
        let source: String?
    }

    struct Snapshot {
        /// Taux décimal utilisé par le moteur, ex. 3,2 % = 0,032.
        let rate: Double?
        let ratePercent: Double?
        let source: String?
        let hasDatedRecords: Bool
        let reliable: Bool
        let warnings: [String]
    }

    static func resolve(records: [Record], period: YearMonthV2) -> Snapshot {
        guard !records.isEmpty else {
            return Snapshot(
                rate: nil,
                ratePercent: nil,
                source: nil,
                hasDatedRecords: false,
                reliable: true,
                warnings: []
            )
        }

        guard records.allSatisfy(valid) else {
            return blocked("PAS : taux, période ou source datée invalide ; calcul après impôt bloqué.")
        }

        let applicable = records.filter { record in
            guard let start = record.effectiveFrom else { return false }
            return period >= start && (record.effectiveTo == nil || period <= record.effectiveTo!)
        }

        switch applicable.count {
        case 0:
            return blocked("PAS : aucun taux personnel confirmé pour \(period.year)-\(String(format: "%02d", period.month)).")
        case 1:
            let record = applicable[0]
            let source = record.source!.trimmingCharacters(in: .whitespacesAndNewlines)
            return Snapshot(
                rate: record.ratePercent / 100,
                ratePercent: record.ratePercent,
                source: source,
                hasDatedRecords: true,
                reliable: true,
                warnings: []
            )
        default:
            return blocked("PAS : plusieurs périodes de taux se chevauchent pour le mois demandé ; calcul après impôt bloqué.")
        }
    }

    private static func blocked(_ warning: String) -> Snapshot {
        Snapshot(
            rate: nil,
            ratePercent: nil,
            source: nil,
            hasDatedRecords: true,
            reliable: false,
            warnings: [warning]
        )
    }

    private static func valid(_ record: Record) -> Bool {
        guard !record.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              record.ratePercent.isFinite,
              record.ratePercent >= 0,
              record.ratePercent <= 100,
              let start = record.effectiveFrom,
              let source = record.source,
              !source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return false
        }
        guard start.year > 0 else { return false }
        if let end = record.effectiveTo {
            guard end.year > 0, end >= start else { return false }
        }
        return true
    }
}
