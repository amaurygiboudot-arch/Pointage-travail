import Foundation

/// Contexte légal du coefficient maximal RGDU 2026.
///
/// D.241-7 CSS rattache Tδ au taux de contribution logement réellement dû
/// (L.813-5 1° ou 2° CCH), jamais au seul effectif brut. Si la somme des taux
/// éligibles effectivement à la charge de l'employeur est inférieure au maximum
/// standard, Tδ est réduit afin de ne pas dépasser cette somme.
enum EmployerGeneralReductionRateContext2026V2 {
    private static let tMin = 0.0200
    private static let tDeltaL813_5_1 = 0.3781
    private static let tDeltaL813_5_2 = 0.3821

    enum HousingContributionRegime: Equatable {
        /// CCH L.813-5 1° : 0,1 % sur assiette plafonnée.
        case l813_5_1
        /// CCH L.813-5 2° : 0,5 % sur totalité de l'assiette.
        case l813_5_2
    }

    struct Record: Equatable {
        let id: String
        let housingContributionRegime: HousingContributionRegime
        /// Somme décimale des taux RGDU éligibles effectivement à la charge de l'employeur.
        let eligibleEmployerRateSum: Double
        let effectiveFrom: YearMonthV2
        let effectiveTo: YearMonthV2?
        let source: String
    }

    struct Snapshot: Equatable {
        let tDelta: Double?
        let maximumCoefficient: Double?
        let source: String?
        let reliable: Bool
        let warnings: [String]
    }

    static func resolve(records: [Record], period: YearMonthV2) -> Snapshot {
        guard period.year == 2026 else {
            return blocked("RGDU : contexte de coefficient 2026 indisponible pour \(period.year).")
        }

        let malformed = records.filter { record in
            record.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                record.source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                (record.effectiveTo.map { $0 < record.effectiveFrom } ?? false) ||
                !validEligibleRateSum(record.eligibleEmployerRateSum)
        }
        guard malformed.isEmpty else {
            return blocked("RGDU 2026 : règle de coefficient incomplète ou incohérente ; aucun Tδ n'est supposé.")
        }

        let active = records.filter { record in
            period >= record.effectiveFrom && (record.effectiveTo == nil || period <= record.effectiveTo!)
        }
        guard !active.isEmpty else {
            return blocked(
                "RGDU 2026 : régime de contribution logement et somme des taux éligibles à confirmer pour \(String(format: "%02d/%04d", period.month, period.year))."
            )
        }
        guard active.count == 1, let selected = active.first else {
            return blocked("RGDU 2026 : plusieurs règles de coefficient se chevauchent ; calcul automatique bloqué.")
        }

        let standardDelta: Double
        switch selected.housingContributionRegime {
        case .l813_5_1:
            standardDelta = tDeltaL813_5_1
        case .l813_5_2:
            standardDelta = tDeltaL813_5_2
        }
        let standardMaximum = tMin + standardDelta
        let effectiveMaximum = min(standardMaximum, selected.eligibleEmployerRateSum)
        let effectiveDelta = effectiveMaximum - tMin

        guard effectiveDelta.isFinite, effectiveDelta >= 0 else {
            return blocked("RGDU 2026 : somme des taux éligibles incompatible avec Tmin ; calcul automatique bloqué.")
        }

        return Snapshot(
            tDelta: effectiveDelta,
            maximumCoefficient: effectiveMaximum,
            source: selected.source.trimmingCharacters(in: .whitespacesAndNewlines),
            reliable: true,
            warnings: []
        )
    }

    static func isUsable(_ snapshot: Snapshot?) -> Bool {
        guard let snapshot,
              snapshot.reliable,
              let source = snapshot.source,
              !source.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let delta = snapshot.tDelta,
              let maximum = snapshot.maximumCoefficient,
              delta.isFinite,
              delta >= 0,
              maximum.isFinite,
              maximum >= tMin,
              maximum <= 1 else { return false }

        return abs((tMin + delta) - maximum) < 0.000_000_1
    }

    private static func validEligibleRateSum(_ value: Double) -> Bool {
        value.isFinite && value >= tMin && value <= 1
    }

    private static func blocked(_ warning: String) -> Snapshot {
        Snapshot(
            tDelta: nil,
            maximumCoefficient: nil,
            source: nil,
            reliable: false,
            warnings: [warning]
        )
    }
}
