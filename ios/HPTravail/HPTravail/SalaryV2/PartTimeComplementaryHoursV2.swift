import Foundation

struct ConfirmedComplementaryScheduleV2: Equatable {
    let sourceId: String
    let contractualMinutes: Int
    let effectiveFromEpochDay: Int64
    let effectiveToEpochDay: Int64?
    let tiers: [OvertimeTierV2]

    func applies(referenceEpochDay: Int64?, minutes: Int) -> Bool {
        guard let date = referenceEpochDay else { return false }
        return !sourceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            minutes == contractualMinutes && date >= effectiveFromEpochDay &&
            (effectiveToEpochDay == nil || date <= effectiveToEpochDay!)
    }
}

struct PartTimeComplementaryTierV2: Equatable {
    let label: String
    let minutes: Int
    let multiplier: Double
}

struct PartTimeComplementaryResultV2: Equatable {
    let complementaryMinutes: Int
    let grossToAdd: Double
    let tiers: [PartTimeComplementaryTierV2]
    let warnings: [String]
    var confirmedScheduleUsed: Bool = false
}

enum PartTimeComplementaryHoursV2 {
    static func calculateWeek(
        contractualMinutes: Int,
        paidMinutes: Int,
        grossHourlyRate: Double,
        legalWeeklyMinutes: Int = 35 * 60,
        confirmedSchedule: ConfirmedComplementaryScheduleV2? = nil,
        referenceEpochDay: Int64? = nil
    ) throws -> PartTimeComplementaryResultV2 {
        guard contractualMinutes > 0 else { throw PayrollEngineErrorV2.invalidWeeklyDuration }
        guard paidMinutes >= 0 else { throw PayrollEngineErrorV2.invalidPaidMinutes }
        guard grossHourlyRate > 0, grossHourlyRate.isFinite else { throw PayrollEngineErrorV2.invalidHourlyRate }
        guard legalWeeklyMinutes > 0 else { throw PayrollEngineErrorV2.invalidWeeklyDuration }

        let paid = paidMinutes
        let extra = max(0, paid - contractualMinutes)
        guard extra > 0 else {
            return PartTimeComplementaryResultV2(
                complementaryMinutes: 0,
                grossToAdd: 0,
                tiers: [],
                warnings: []
            )
        }

        if let schedule = confirmedSchedule,
           schedule.applies(referenceEpochDay: referenceEpochDay, minutes: contractualMinutes),
           OvertimeCoverageV2.isFullyCovered(regularLimitMinutes: contractualMinutes, paidMinutes: paid, tiers: schedule.tiers) {
            let tiers = schedule.tiers.sorted { $0.fromMinutes < $1.fromMinutes }.compactMap { tier -> PartTimeComplementaryTierV2? in
                let minutes = max(0, min(paid, tier.toMinutes ?? Int.max) - tier.fromMinutes)
                return minutes == 0 ? nil : PartTimeComplementaryTierV2(
                    label: "Heures complémentaires — \(schedule.sourceId)", minutes: minutes, multiplier: tier.multiplier)
            }
            let gross = tiers.reduce(0.0) { $0 + Double($1.minutes) / 60 * grossHourlyRate * $1.multiplier }
            guard gross.isFinite else { throw PayrollEngineErrorV2.invalidMoneyAmount }
            let warnings = paid >= legalWeeklyMinutes ? ["Temps partiel : durée légale hebdomadaire atteinte ; situation à vérifier malgré le barème confirmé."] : []
            return PartTimeComplementaryResultV2(complementaryMinutes: extra, grossToAdd: gross,
                tiers: tiers, warnings: warnings, confirmedScheduleUsed: warnings.isEmpty)
        }

        let tenthLimit = max(0, Int(Double(contractualMinutes) / 10.0))
        let thirdLimit = max(tenthLimit, Int(Double(contractualMinutes) / 3.0))

        let firstMinutes = min(extra, tenthLimit)
        let secondMinutes = max(0, min(extra, thirdLimit) - firstMinutes)
        let beyondThirdMinutes = max(0, extra - firstMinutes - secondMinutes)
        let ratePerMinute = grossHourlyRate / 60.0

        var tiers: [PartTimeComplementaryTierV2] = []
        if firstMinutes > 0 {
            tiers.append(.init(label: "Heures complémentaires +10 %", minutes: firstMinutes, multiplier: 1.10))
        }
        if secondMinutes > 0 {
            tiers.append(.init(label: "Heures complémentaires +25 %", minutes: secondMinutes, multiplier: 1.25))
        }
        if beyondThirdMinutes > 0 {
            tiers.append(.init(
                label: "Heures complémentaires au-delà du tiers — majoration à vérifier",
                minutes: beyondThirdMinutes,
                multiplier: 1.0
            ))
        }

        var warnings: [String] = []
        if extra > tenthLimit {
            warnings.append("Temps partiel : le volume d'heures complémentaires dépasse 1/10 de la durée contractuelle ; vérifier qu'un accord autorise une limite supérieure.")
        }
        if beyondThirdMinutes > 0 {
            warnings.append("Temps partiel : dépassement supérieur au tiers de la durée contractuelle. Les heures sont conservées au taux de base dans l'estimation, mais leur majoration et la régularité de la situation doivent être vérifiées.")
        }
        if paid >= legalWeeklyMinutes {
            warnings.append("Temps partiel : la durée réellement accomplie atteint ou dépasse la durée légale hebdomadaire ; situation à vérifier, aucune requalification n'est inventée par HoraTrack.")
        }

        let gross = Double(firstMinutes) * ratePerMinute * 1.10
            + Double(secondMinutes) * ratePerMinute * 1.25
            + Double(beyondThirdMinutes) * ratePerMinute

        return PartTimeComplementaryResultV2(
            complementaryMinutes: extra,
            grossToAdd: gross,
            tiers: tiers,
            warnings: warnings
        )
    }
}
