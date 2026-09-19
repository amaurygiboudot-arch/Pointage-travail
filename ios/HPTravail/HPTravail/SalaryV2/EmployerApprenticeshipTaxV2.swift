import Foundation

/// Taxe d'apprentissage employeur rattachée au coût mensuel de la rémunération.
/// La part principale et le solde sont séparés et les taux doivent être confirmés par entreprise/période.
enum EmployerApprenticeshipTaxV2 {
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
        let principalRate: Double
        let balanceRate: Double
        let effectiveFrom: Period
        let effectiveTo: Period?
        let source: String
    }

    struct Snapshot: Equatable {
        let principalRate: Double?
        let balanceRate: Double?
        let source: String?
        let reliable: Bool
        let warnings: [String]
    }

    struct Result: Equatable {
        let principalAmount: Double?
        let balanceAccrualAmount: Double?
        let totalEmployerAmount: Double?
        let complete: Bool
        let warnings: [String]
    }

    static func resolve(records: [Record], period: Period) -> Snapshot {
        guard period.isValid else {
            return unresolved("Taxe d’apprentissage : période invalide ; aucun montant patronal n'est appliqué.")
        }

        let malformed = records.filter { record in
            record.source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                !record.effectiveFrom.isValid ||
                (record.effectiveTo?.isValid == false) ||
                (record.effectiveTo.map { $0 < record.effectiveFrom } ?? false) ||
                !validRate(record.principalRate) ||
                !validRate(record.balanceRate)
        }
        guard malformed.isEmpty else {
            return unresolved("Taxe d’apprentissage : une règle est incomplète ou incohérente ; aucun montant patronal n'est appliqué.")
        }

        let active = records.filter { record in
            period >= record.effectiveFrom && (record.effectiveTo == nil || period <= record.effectiveTo!)
        }
        guard !active.isEmpty else {
            return unresolved("Taxe d’apprentissage : taux/applicabilité à confirmer pour \(period.label).")
        }
        guard active.count == 1, let selected = active.first else {
            return unresolved("Taxe d’apprentissage : plusieurs règles se chevauchent ; calcul patronal bloqué.")
        }

        return Snapshot(
            principalRate: selected.principalRate,
            balanceRate: selected.balanceRate,
            source: selected.source,
            reliable: true,
            warnings: []
        )
    }

    static func calculate(
        grossSocial: Double,
        principalRate: Double?,
        balanceRate: Double?
    ) -> Result {
        guard let principalRate, let balanceRate else {
            return incomplete("Taxe d’apprentissage : taux confirmés manquants ; coût employeur incomplet.")
        }
        guard validRate(principalRate), validRate(balanceRate) else {
            return incomplete("Taxe d’apprentissage : taux invalide ; aucun montant n'est calculé.")
        }
        guard grossSocial.isFinite, grossSocial >= 0 else {
            return incomplete("Taxe d’apprentissage : assiette brute sociale invalide ; aucun montant patronal n'est calculé.")
        }

        let principal = grossSocial * principalRate
        let balance = grossSocial * balanceRate
        return Result(
            principalAmount: principal,
            balanceAccrualAmount: balance,
            totalEmployerAmount: principal + balance,
            complete: true,
            warnings: []
        )
    }

    private static func unresolved(_ warning: String) -> Snapshot {
        Snapshot(
            principalRate: nil,
            balanceRate: nil,
            source: nil,
            reliable: false,
            warnings: [warning]
        )
    }

    private static func incomplete(_ warning: String) -> Result {
        Result(
            principalAmount: nil,
            balanceAccrualAmount: nil,
            totalEmployerAmount: nil,
            complete: false,
            warnings: [warning]
        )
    }

    private static func validRate(_ rate: Double) -> Bool {
        rate.isFinite && rate >= 0 && rate <= 1
    }
}
