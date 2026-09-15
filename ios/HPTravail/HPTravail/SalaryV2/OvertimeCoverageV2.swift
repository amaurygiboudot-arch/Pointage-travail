import Foundation

/// Vérifie qu'aucune minute au-delà du seuil hebdomadaire ne repose sur un palier absent ou ambigu.
///
/// Cette couche ne déduit aucun taux et ne dépend ni du métier, ni de l'entreprise, ni de l'appareil.
/// Elle vérifie uniquement que les paliers confirmés couvrent sans trou ni chevauchement toute la
/// tranche comprise entre le seuil régulier et le temps payé.
enum OvertimeCoverageV2 {
    static func isFullyCovered(
        regularLimitMinutes: Int,
        paidMinutes: Int,
        tiers: [OvertimeTierV2]
    ) -> Bool {
        guard regularLimitMinutes > 0 else { return false }
        let paid = max(0, paidMinutes)
        if paid <= regularLimitMinutes { return true }

        var cursor = regularLimitMinutes
        for tier in tiers.sorted(by: { $0.fromMinutes < $1.fromMinutes }) {
            if tier.fromMinutes < regularLimitMinutes { return false }
            let rawEnd = tier.toMinutes ?? Int.max
            if rawEnd <= tier.fromMinutes { return false }

            let tierStart = tier.fromMinutes
            let tierEnd = min(paid, rawEnd)
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
