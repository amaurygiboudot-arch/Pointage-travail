import Foundation

/// Versement mobilité employeur : aucune applicabilité ni aucun taux n'est déduit automatiquement.
/// Les règles doivent être confirmées pour une période et conserver une source vérifiable.
enum EmployerMobilityContributionV2 {
    enum Status: Equatable {
        case applicable
        case notApplicable
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
        let status: Status
        let rate: Double?
        let effectiveFrom: Period
        let effectiveTo: Period?
        let source: String
    }

    struct Snapshot: Equatable {
        let applicable: Bool?
        let rate: Double?
        let source: String?
        let reliable: Bool
        let warnings: [String]
    }

    struct Result: Equatable {
        let employerAmount: Double?
        let complete: Bool
        let warnings: [String]
    }

    static func resolve(records: [Record], period: Period) -> Snapshot {
        guard period.isValid else {
            return unresolved("Versement mobilité employeur : période invalide ; aucun taux n'est appliqué.")
        }

        let malformed = records.filter { record in
            record.source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                !record.effectiveFrom.isValid ||
                (record.effectiveTo?.isValid == false) ||
                (record.effectiveTo.map { $0 < record.effectiveFrom } ?? false) ||
                (record.status == .applicable && (record.rate.map { !validRate($0) } ?? true))
        }
        guard malformed.isEmpty else {
            return unresolved("Versement mobilité employeur : une règle enregistrée est incomplète ou incohérente ; aucun taux n'est appliqué.")
        }

        let active = records.filter { record in
            period >= record.effectiveFrom && (record.effectiveTo == nil || period <= record.effectiveTo!)
        }
        guard !active.isEmpty else {
            return unresolved("Versement mobilité employeur : applicabilité et taux à confirmer pour \(period.label).")
        }
        guard active.count == 1, let selected = active.first else {
            return unresolved("Versement mobilité employeur : plusieurs règles se chevauchent sur la période ; calcul patronal bloqué.")
        }

        switch selected.status {
        case .notApplicable:
            return Snapshot(
                applicable: false,
                rate: 0,
                source: selected.source,
                reliable: true,
                warnings: []
            )
        case .applicable:
            return Snapshot(
                applicable: true,
                rate: selected.rate,
                source: selected.source,
                reliable: true,
                warnings: []
            )
        }
    }

    static func calculate(grossSocial: Double, rate: Double?) -> Result {
        guard let rate else {
            return incomplete("Versement mobilité employeur : taux applicable à confirmer ; coût employeur incomplet.")
        }
        guard validRate(rate) else {
            return incomplete("Versement mobilité employeur : taux invalide ; aucun montant patronal n'est calculé.")
        }
        guard grossSocial.isFinite, grossSocial >= 0 else {
            return incomplete("Versement mobilité employeur : assiette brute sociale invalide ; aucun montant patronal n'est calculé.")
        }

        return Result(
            employerAmount: grossSocial * rate,
            complete: true,
            warnings: []
        )
    }

    private static func unresolved(_ warning: String) -> Snapshot {
        Snapshot(
            applicable: nil,
            rate: nil,
            source: nil,
            reliable: false,
            warnings: [warning]
        )
    }

    private static func incomplete(_ warning: String) -> Result {
        Result(employerAmount: nil, complete: false, warnings: [warning])
    }

    private static func validRate(_ rate: Double) -> Bool {
        rate.isFinite && rate >= 0 && rate <= 1
    }
}
