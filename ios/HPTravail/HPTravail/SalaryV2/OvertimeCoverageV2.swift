import Foundation

/// Vérifie qu'aucune minute au-delà du seuil hebdomadaire ne repose sur un palier absent ou ambigu.
///
/// Cette couche ne déduit aucun taux et ne dépend ni du métier, ni de l'entreprise, ni de l'appareil.
/// Elle distingue la cohérence structurelle des paliers de leur couverture effective :
/// un trou conserve les tranches connues pour une estimation partielle, tandis qu'un chevauchement
/// ou un palier invalide neutralise l'ensemble des paliers pour éviter tout double comptage.
enum OvertimeCoverageV2 {
    static func isStructurallyValid(
        regularLimitMinutes: Int,
        tiers: [OvertimeTierV2]
    ) -> Bool {
        guard regularLimitMinutes > 0 else { return false }
        var previousEnd: Int?
        for tier in tiers.sorted(by: { $0.fromMinutes < $1.fromMinutes }) {
            if tier.fromMinutes < regularLimitMinutes { return false }
            if !tier.multiplier.isFinite || tier.multiplier < 1 { return false }
            let rawEnd = tier.toMinutes ?? Int.max
            if rawEnd <= tier.fromMinutes { return false }
            if let previousEnd, tier.fromMinutes < previousEnd { return false }
            previousEnd = rawEnd
        }
        return true
    }

    static func calculationSafeTiers(
        regularLimitMinutes: Int,
        tiers: [OvertimeTierV2]
    ) -> [OvertimeTierV2] {
        guard isStructurallyValid(regularLimitMinutes: regularLimitMinutes, tiers: tiers) else {
            return []
        }
        return tiers.sorted(by: { $0.fromMinutes < $1.fromMinutes })
    }

    static func isFullyCovered(
        regularLimitMinutes: Int,
        paidMinutes: Int,
        tiers: [OvertimeTierV2]
    ) -> Bool {
        guard regularLimitMinutes > 0 else { return false }
        let paid = max(0, paidMinutes)
        if paid <= regularLimitMinutes { return true }
        guard isStructurallyValid(regularLimitMinutes: regularLimitMinutes, tiers: tiers) else {
            return false
        }

        var cursor = regularLimitMinutes
        for tier in tiers.sorted(by: { $0.fromMinutes < $1.fromMinutes }) {
            let tierStart = tier.fromMinutes
            let tierEnd = min(paid, tier.toMinutes ?? Int.max)
            if tierEnd <= regularLimitMinutes { continue }
            if tierStart >= paid { break }
            if tierStart != cursor { return false }
            cursor = tierEnd
        }
        return cursor >= paid
    }

    static func areWeeksFullyCovered(
        regularLimitMinutes: Int,
        paidWeeks: [Int],
        tiers: [OvertimeTierV2]
    ) -> Bool {
        paidWeeks.allSatisfy {
            isFullyCovered(
                regularLimitMinutes: regularLimitMinutes,
                paidMinutes: $0,
                tiers: tiers
            )
        }
    }
}
