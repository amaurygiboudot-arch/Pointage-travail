import Foundation

/// Assurance chômage + AGS employeur avec taux confirmés par entreprise et période.
/// Aucun taux de droit commun n'est imposé automatiquement : bonus-malus et cas particuliers restent explicites.
enum EmployerUnemploymentAgsV2 {
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
        let unemploymentRate: Double
        let agsRate: Double
        let effectiveFrom: Period
        let effectiveTo: Period?
        let source: String
    }

    struct Snapshot: Equatable {
        let unemploymentRate: Double?
        let agsRate: Double?
        let source: String?
        let reliable: Bool
        let warnings: [String]
    }

    struct Result: Equatable {
        let baseAmount: Double?
        let unemploymentAmount: Double?
        let agsAmount: Double?
        let totalEmployerAmount: Double?
        let complete: Bool
        let warnings: [String]
    }

    static func resolve(records: [Record], period: Period) -> Snapshot {
        guard period.isValid else {
            return Snapshot(
                unemploymentRate: nil,
                agsRate: nil,
                source: nil,
                reliable: false,
                warnings: ["Chômage/AGS employeur : période invalide ; aucun taux n'est appliqué."]
            )
        }

        let malformed = records.filter { record in
            record.source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                !record.effectiveFrom.isValid ||
                (record.effectiveTo?.isValid == false) ||
                (record.effectiveTo.map { $0 < record.effectiveFrom } ?? false) ||
                !validRate(record.unemploymentRate) ||
                !validRate(record.agsRate)
        }
        guard malformed.isEmpty else {
            return Snapshot(
                unemploymentRate: nil,
                agsRate: nil,
                source: nil,
                reliable: false,
                warnings: ["Chômage/AGS employeur : une règle enregistrée est incomplète ou incohérente ; aucun taux n'est appliqué."]
            )
        }

        let active = records.filter { record in
            period >= record.effectiveFrom && (record.effectiveTo == nil || period <= record.effectiveTo!)
        }
        guard !active.isEmpty else {
            return Snapshot(
                unemploymentRate: nil,
                agsRate: nil,
                source: nil,
                reliable: false,
                warnings: ["Chômage/AGS employeur : taux confirmés à renseigner pour \(period.label)."]
            )
        }
        guard active.count == 1, let selected = active.first else {
            return Snapshot(
                unemploymentRate: nil,
                agsRate: nil,
                source: nil,
                reliable: false,
                warnings: ["Chômage/AGS employeur : plusieurs règles se chevauchent sur la période ; calcul patronal bloqué."]
            )
        }

        return Snapshot(
            unemploymentRate: selected.unemploymentRate,
            agsRate: selected.agsRate,
            source: selected.source,
            reliable: true,
            warnings: []
        )
    }

    static func calculate(
        grossSocial: Double,
        fourTimesApplicableCeiling: Double?,
        unemploymentRate: Double?,
        agsRate: Double?
    ) -> Result {
        guard grossSocial.isFinite, grossSocial >= 0 else {
            return incomplete("Chômage/AGS employeur : assiette brute invalide ; aucun montant patronal n'est calculé.")
        }
        guard let ceiling = fourTimesApplicableCeiling,
              ceiling.isFinite,
              ceiling >= 0 else {
            return incomplete("Chômage/AGS employeur : plafond social applicable indisponible ; coût employeur incomplet.")
        }
        guard let unemploymentRate, let agsRate else {
            return incomplete("Chômage/AGS employeur : taux confirmés manquants ; coût employeur incomplet.")
        }
        guard validRate(unemploymentRate), validRate(agsRate) else {
            return incomplete("Chômage/AGS employeur : taux invalide ; aucun montant patronal n'est calculé.")
        }

        let base = min(grossSocial, ceiling)
        let unemployment = base * unemploymentRate
        let ags = base * agsRate
        return Result(
            baseAmount: base,
            unemploymentAmount: unemployment,
            agsAmount: ags,
            totalEmployerAmount: unemployment + ags,
            complete: true,
            warnings: []
        )
    }

    private static func incomplete(_ warning: String) -> Result {
        Result(
            baseAmount: nil,
            unemploymentAmount: nil,
            agsAmount: nil,
            totalEmployerAmount: nil,
            complete: false,
            warnings: [warning]
        )
    }

    private static func validRate(_ rate: Double) -> Bool {
        rate.isFinite && rate >= 0 && rate <= 1
    }
}
