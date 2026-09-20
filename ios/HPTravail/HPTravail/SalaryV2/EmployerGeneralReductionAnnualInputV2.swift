import Foundation

/// Agrège les faits RGDU mensuels déjà fiabilisés vers l'entrée annuelle 2026.
///
/// Les douze mois doivent être présents explicitement, cohérents entre eux et couverts
/// par un contexte annuel confirmé. Une avance transmise est revérifiée contre les mêmes faits.
enum EmployerGeneralReductionAnnualInputV2 {
    struct Month: Equatable {
        let period: YearMonthV2
        let reductionRemunerationMonthly: Double?
        let additionalPaidMinutes: Double?
        let automaticRgduAdvanceAmount: Double?
        let fnalTreatment: EmployerWorkforceContributionsV2.FnalTreatment?
        let contractType: ContractTypeV2?
        let contractualWeeklyMinutes: Int?
        let fullMonthPresent: Bool?
        let standardCommonLawCaseConfirmed: Bool?
        let paidHoursComplete: Bool?
        let source: String?
        let reliable: Bool
        let warnings: [String]

        init(
            period: YearMonthV2,
            reductionRemunerationMonthly: Double?,
            additionalPaidMinutes: Double?,
            automaticRgduAdvanceAmount: Double?,
            fnalTreatment: EmployerWorkforceContributionsV2.FnalTreatment?,
            contractType: ContractTypeV2?,
            contractualWeeklyMinutes: Int?,
            fullMonthPresent: Bool?,
            standardCommonLawCaseConfirmed: Bool?,
            paidHoursComplete: Bool?,
            source: String?,
            reliable: Bool,
            warnings: [String] = []
        ) {
            self.period = period
            self.reductionRemunerationMonthly = reductionRemunerationMonthly
            self.additionalPaidMinutes = additionalPaidMinutes
            self.automaticRgduAdvanceAmount = automaticRgduAdvanceAmount
            self.fnalTreatment = fnalTreatment
            self.contractType = contractType
            self.contractualWeeklyMinutes = contractualWeeklyMinutes
            self.fullMonthPresent = fullMonthPresent
            self.standardCommonLawCaseConfirmed = standardCommonLawCaseConfirmed
            self.paidHoursComplete = paidHoursComplete
            self.source = source
            self.reliable = reliable
            self.warnings = warnings
        }
    }

    struct Result: Equatable {
        let annualInput: EmployerGeneralReductionAnnual2026V2.Input?
        let monthlyAdvances: [EmployerGeneralReductionAnnualRegularizationV2.MonthlyAdvance]
        let reliable: Bool
        let warnings: [String]
    }

    static func resolve(
        year: Int,
        months: [Month],
        annualContext: EmployerGeneralReductionAnnualContextV2.Snapshot
    ) -> Result {
        guard year == 2026 else {
            return blocked("RGDU annuelle : agrégation non intégrée pour \(year).")
        }

        var contextBlockers: [String] = []
        if !annualContext.reliable { contextBlockers.append(contentsOf: annualContext.warnings) }
        if annualContext.source?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty != false {
            contextBlockers.append("RGDU annuelle : source du contexte annuel absente ; agrégation bloquée.")
        }
        if annualContext.fullCalendarYearPresent != true {
            contextBlockers.append("RGDU annuelle : année civile complète non confirmée.")
        }
        if annualContext.standardCommonLawCaseConfirmed != true {
            contextBlockers.append("RGDU annuelle : cas de droit commun non confirmé sur toute l'année.")
        }
        if annualContext.homogeneousAnnualParametersConfirmed != true {
            contextBlockers.append("RGDU annuelle : stabilité annuelle du contrat, de la durée contractuelle et du régime FNAL/logement non confirmée.")
        }
        if annualContext.confirmedFnalTreatment == nil {
            contextBlockers.append("RGDU annuelle : régime FNAL/logement annuel exact non confirmé.")
        }
        if annualContext.confirmedContractType == nil {
            contextBlockers.append("RGDU annuelle : type de contrat annuel exact non confirmé.")
        }
        if annualContext.confirmedContractualWeeklyMinutes == nil {
            contextBlockers.append("RGDU annuelle : durée contractuelle hebdomadaire annuelle exacte non confirmée.")
        }
        contextBlockers.append(contentsOf: annualContext.warnings)
        contextBlockers = unique(contextBlockers)
        guard contextBlockers.isEmpty else { return blocked(contextBlockers) }

        let duplicatePeriods = Dictionary(grouping: months, by: \.period).values.contains { $0.count > 1 }
        guard !duplicatePeriods else {
            return blocked("RGDU annuelle : plusieurs entrées existent pour un même mois.")
        }

        let expected = Set((1...12).compactMap { YearMonthV2(year: year, month: $0) })
        let actual = Set(months.map(\.period))
        guard actual == expected else {
            let missing = expected.subtracting(actual).sorted().map(\.description).joined(separator: ", ")
            let foreign = actual.subtracting(expected).sorted().map(\.description).joined(separator: ", ")
            var warnings: [String] = []
            if !missing.isEmpty { warnings.append("RGDU annuelle : mois manquants : \(missing).") }
            if !foreign.isEmpty { warnings.append("RGDU annuelle : mois hors année attendue : \(foreign).") }
            if warnings.isEmpty { warnings.append("RGDU annuelle : les douze mois de \(year) doivent être présents explicitement.") }
            return blocked(warnings)
        }

        let ordered = months.sorted { $0.period < $1.period }
        var monthlyBlockers: [String] = []
        for month in ordered {
            let label = month.period.description
            if !month.reliable {
                monthlyBlockers.append(contentsOf: month.warnings.isEmpty
                    ? ["RGDU annuelle : faits mensuels non fiables pour \(label)."]
                    : month.warnings)
            }
            if !month.warnings.isEmpty { monthlyBlockers.append(contentsOf: month.warnings) }
            if month.source?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty != false {
                monthlyBlockers.append("RGDU annuelle : source mensuelle absente pour \(label).")
            }
            if month.fullMonthPresent != true {
                monthlyBlockers.append("RGDU annuelle : mois complet non confirmé pour \(label).")
            }
            if month.standardCommonLawCaseConfirmed != true {
                monthlyBlockers.append("RGDU annuelle : cas de droit commun non confirmé pour \(label).")
            }
            if month.paidHoursComplete != true {
                monthlyBlockers.append("RGDU annuelle : exhaustivité des heures rémunérées non confirmée pour \(label).")
            }
            if month.reductionRemunerationMonthly == nil ||
                !month.reductionRemunerationMonthly!.isFinite || month.reductionRemunerationMonthly! < 0 {
                monthlyBlockers.append("RGDU annuelle : rémunération de référence invalide ou inconnue pour \(label).")
            }
            if month.additionalPaidMinutes == nil ||
                !month.additionalPaidMinutes!.isFinite || month.additionalPaidMinutes! < 0 {
                monthlyBlockers.append("RGDU annuelle : heures supplémentaires/complémentaires inconnues ou invalides pour \(label).")
            }
            if month.automaticRgduAdvanceAmount == nil ||
                !month.automaticRgduAdvanceAmount!.isFinite || month.automaticRgduAdvanceAmount! < 0 {
                monthlyBlockers.append("RGDU annuelle : avance RGDU automatique inconnue ou invalide pour \(label).")
            }
            if month.fnalTreatment == nil {
                monthlyBlockers.append("RGDU annuelle : régime FNAL/logement inconnu pour \(label).")
            }
            if month.contractType != .fullTime && month.contractType != .partTime {
                monthlyBlockers.append("RGDU annuelle : type de contrat non couvert ou inconnu pour \(label).")
            }
            if month.contractualWeeklyMinutes == nil || month.contractualWeeklyMinutes! <= 0 {
                monthlyBlockers.append("RGDU annuelle : durée contractuelle hebdomadaire inconnue ou invalide pour \(label).")
            }
        }
        monthlyBlockers = unique(monthlyBlockers)
        guard monthlyBlockers.isEmpty else { return blocked(monthlyBlockers) }

        guard let fnalTreatment = ordered.first?.fnalTreatment,
              let contractType = ordered.first?.contractType,
              let contractualWeeklyMinutes = ordered.first?.contractualWeeklyMinutes else {
            return blocked("RGDU annuelle : paramètres mensuels structurants indisponibles.")
        }

        var homogeneityBlockers: [String] = []
        if ordered.contains(where: { $0.fnalTreatment != fnalTreatment }) {
            homogeneityBlockers.append("RGDU annuelle : le régime FNAL/logement varie au cours de l'année ; cas standard bloqué.")
        }
        if ordered.contains(where: { $0.contractType != contractType }) {
            homogeneityBlockers.append("RGDU annuelle : le type de contrat varie au cours de l'année ; cas standard bloqué.")
        }
        if ordered.contains(where: { $0.contractualWeeklyMinutes != contractualWeeklyMinutes }) {
            homogeneityBlockers.append("RGDU annuelle : la durée contractuelle hebdomadaire varie au cours de l'année ; cas standard bloqué.")
        }
        guard homogeneityBlockers.isEmpty else { return blocked(homogeneityBlockers) }

        var contextMismatch: [String] = []
        if annualContext.confirmedFnalTreatment != fnalTreatment {
            contextMismatch.append("RGDU annuelle : le régime FNAL/logement des 12 mois ne correspond pas au contexte annuel confirmé.")
        }
        if annualContext.confirmedContractType != contractType {
            contextMismatch.append("RGDU annuelle : le type de contrat des 12 mois ne correspond pas au contexte annuel confirmé.")
        }
        if annualContext.confirmedContractualWeeklyMinutes != contractualWeeklyMinutes {
            contextMismatch.append("RGDU annuelle : la durée contractuelle hebdomadaire des 12 mois ne correspond pas au contexte annuel confirmé.")
        }
        guard contextMismatch.isEmpty else { return blocked(contextMismatch) }

        for month in ordered {
            let recalculated = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
                .init(
                    year: year,
                    reductionRemunerationMonthly: month.reductionRemunerationMonthly!,
                    fnalTreatment: month.fnalTreatment,
                    contractType: month.contractType,
                    contractualWeeklyMinutes: month.contractualWeeklyMinutes,
                    additionalPaidMinutes: month.additionalPaidMinutes,
                    fullMonthPresent: month.fullMonthPresent,
                    standardCommonLawCaseConfirmed: month.standardCommonLawCaseConfirmed
                )
            )
            guard recalculated.reliable, let expectedAdvance = recalculated.amount else {
                return blocked(recalculated.warnings.isEmpty
                    ? ["RGDU annuelle : avance mensuelle impossible à vérifier pour \(month.period)."]
                    : recalculated.warnings)
            }
            if abs(expectedAdvance - month.automaticRgduAdvanceAmount!) > 0.005 {
                return blocked("RGDU annuelle : l'avance automatique enregistrée pour \(month.period) ne correspond plus aux faits mensuels ; régularisation bloquée.")
            }
        }

        let annualRemuneration = ordered.reduce(0) { $0 + $1.reductionRemunerationMonthly! }
        let annualAdditionalMinutes = ordered.reduce(0) { $0 + $1.additionalPaidMinutes! }
        guard annualRemuneration.isFinite, annualRemuneration >= 0 else {
            return blocked("RGDU annuelle : cumul de rémunération annuel invalide.")
        }
        guard annualAdditionalMinutes.isFinite, annualAdditionalMinutes >= 0 else {
            return blocked("RGDU annuelle : cumul annuel d'heures supplémentaires/complémentaires invalide.")
        }

        let annualInput = EmployerGeneralReductionAnnual2026V2.Input(
            year: year,
            annualReductionRemuneration: annualRemuneration,
            fnalTreatment: fnalTreatment,
            contractType: contractType,
            contractualWeeklyMinutes: contractualWeeklyMinutes,
            additionalPaidMinutesAnnual: annualAdditionalMinutes,
            fullCalendarYearPresent: annualContext.fullCalendarYearPresent,
            standardCommonLawCaseConfirmed: annualContext.standardCommonLawCaseConfirmed,
            homogeneousAnnualParametersConfirmed: annualContext.homogeneousAnnualParametersConfirmed
        )
        let advances = ordered.map {
            EmployerGeneralReductionAnnualRegularizationV2.MonthlyAdvance(
                month: $0.period.month,
                amount: $0.automaticRgduAdvanceAmount!
            )
        }

        return Result(annualInput: annualInput, monthlyAdvances: advances, reliable: true, warnings: [])
    }

    private static func blocked(_ warning: String) -> Result { blocked([warning]) }

    private static func blocked(_ warnings: [String]) -> Result {
        Result(
            annualInput: nil,
            monthlyAdvances: [],
            reliable: false,
            warnings: unique(warnings.isEmpty ? ["RGDU annuelle : agrégation des faits mensuels bloquée."] : warnings)
        )
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
    }
}
