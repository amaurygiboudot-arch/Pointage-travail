import Foundation

/// Cotisations patronales maladie et allocations familiales avec taux confirmés par période.
enum EmployerHealthFamilyV2 {
    struct Period: Equatable, Comparable {
        let year: Int
        let month: Int

        var isValid: Bool { year > 0 && (1...12).contains(month) }

        static func < (lhs: Period, rhs: Period) -> Bool {
            lhs.year == rhs.year ? lhs.month < rhs.month : lhs.year < rhs.year
        }

        var label: String {
            String(format: "%02d/%04d", month, year)
        }
    }

    struct Record: Equatable {
        let id: String
        let healthRate: Double
        let familyRate: Double
        let effectiveFrom: Period
        let effectiveTo: Period?
        let source: String
    }

    struct Snapshot: Equatable {
        let healthRate: Double?
        let familyRate: Double?
        let source: String?
        let reliable: Bool
        let warnings: [String]
    }

    struct Result: Equatable {
        let healthAmount: Double?
        let familyAmount: Double?
        let totalEmployerAmount: Double?
        let complete: Bool
        let warnings: [String]
    }

    static func resolve(records: [Record], period: Period) -> Snapshot {
        guard period.isValid else {
            return Snapshot(
                healthRate: nil,
                familyRate: nil,
                source: nil,
                reliable: false,
                warnings: ["Maladie/allocations familiales employeur : période invalide ; aucun taux n'est appliqué."]
            )
        }

        let malformed = records.filter { record in
            record.source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                !record.effectiveFrom.isValid ||
                (record.effectiveTo?.isValid == false) ||
                (record.effectiveTo.map { $0 < record.effectiveFrom } ?? false) ||
                !validRate(record.healthRate) ||
                !validRate(record.familyRate)
        }
        guard malformed.isEmpty else {
            return Snapshot(
                healthRate: nil,
                familyRate: nil,
                source: nil,
                reliable: false,
                warnings: ["Maladie/allocations familiales employeur : une règle est incomplète ou incohérente ; aucun taux n'est appliqué."]
            )
        }

        let active = records.filter { record in
            period >= record.effectiveFrom && (record.effectiveTo == nil || period <= record.effectiveTo!)
        }
        guard !active.isEmpty else {
            return Snapshot(
                healthRate: nil,
                familyRate: nil,
                source: nil,
                reliable: false,
                warnings: ["Maladie/allocations familiales employeur : taux confirmés à renseigner pour \(period.label)."]
            )
        }
        guard active.count == 1, let selected = active.first else {
            return Snapshot(
                healthRate: nil,
                familyRate: nil,
                source: nil,
                reliable: false,
                warnings: ["Maladie/allocations familiales employeur : plusieurs règles se chevauchent ; calcul patronal bloqué."]
            )
        }

        return Snapshot(
            healthRate: selected.healthRate,
            familyRate: selected.familyRate,
            source: selected.source,
            reliable: true,
            warnings: []
        )
    }

    static func calculate(
        grossSocial: Double,
        healthRate: Double?,
        familyRate: Double?
    ) -> Result {
        guard grossSocial.isFinite, grossSocial >= 0 else {
            return Result(
                healthAmount: nil,
                familyAmount: nil,
                totalEmployerAmount: nil,
                complete: false,
                warnings: ["Maladie/allocations familiales employeur : assiette brute invalide ; aucun montant patronal n'est calculé."]
            )
        }
        guard let healthRate, let familyRate else {
            return Result(
                healthAmount: nil,
                familyAmount: nil,
                totalEmployerAmount: nil,
                complete: false,
                warnings: ["Maladie/allocations familiales employeur : taux confirmés manquants ; coût employeur incomplet."]
            )
        }
        guard validRate(healthRate), validRate(familyRate) else {
            return Result(
                healthAmount: nil,
                familyAmount: nil,
                totalEmployerAmount: nil,
                complete: false,
                warnings: ["Maladie/allocations familiales employeur : taux invalide ; aucun montant n'est calculé."]
            )
        }

        let health = grossSocial * healthRate
        let family = grossSocial * familyRate
        return Result(
            healthAmount: health,
            familyAmount: family,
            totalEmployerAmount: health + family,
            complete: true,
            warnings: []
        )
    }

    private static func validRate(_ rate: Double) -> Bool {
        rate.isFinite && rate >= 0 && rate <= 1
    }
}
