import Foundation

struct SalaryContractSegmentPayrollCompatibilityResultV2: Equatable {
    let contract: ContractV2?
    let compatibleForSingleMonthlyCalculation: Bool
    let changedFields: Set<String>
    let warnings: [String]
}

/// Autorise un calcul mensuel unique seulement lorsque toutes les versions contractuelles datées
/// ont exactement les mêmes paramètres susceptibles d'influencer la paie. L'identifiant de version
/// et la source peuvent changer sans effet sur le montant. Toute autre différence reste bloquante.
enum SalaryContractSegmentPayrollCompatibilityV2 {
    static let equivalentVersionsWarning =
        "Contrat de paie : plusieurs versions datées couvrent le mois mais leurs paramètres de paie sont identiques ; le calcul mensuel unique peut être conservé."
    static let changedPayrollInputsWarning =
        "Contrat de paie : un paramètre de paie change en cours de mois ; HoraTrack conserve le temps payé exact par segment mais ne prorate pas le salaire sans règle de proratisation ou planning confirmé."

    static func resolve(
        _ segments: [SalaryEmploymentContractCoverageSegmentV2]
    ) -> SalaryContractSegmentPayrollCompatibilityResultV2 {
        guard let first = segments.first?.snapshot.contract else {
            return SalaryContractSegmentPayrollCompatibilityResultV2(
                contract: nil,
                compatibleForSingleMonthlyCalculation: false,
                changedFields: [],
                warnings: []
            )
        }

        var changed = Set<String>()
        for segment in segments.dropFirst() {
            collectChanges(first, segment.snapshot.contract, into: &changed)
        }
        guard changed.isEmpty else {
            return SalaryContractSegmentPayrollCompatibilityResultV2(
                contract: nil,
                compatibleForSingleMonthlyCalculation: false,
                changedFields: changed,
                warnings: [changedPayrollInputsWarning]
            )
        }

        return SalaryContractSegmentPayrollCompatibilityResultV2(
            contract: first,
            compatibleForSingleMonthlyCalculation: true,
            changedFields: [],
            warnings: segments.count > 1 ? [equivalentVersionsWarning] : []
        )
    }

    private static func collectChanges(
        _ a: ContractV2,
        _ b: ContractV2,
        into output: inout Set<String>
    ) {
        if a.employerId.trimmingCharacters(in: .whitespacesAndNewlines) != b.employerId.trimmingCharacters(in: .whitespacesAndNewlines) {
            output.insert("employerId")
        }
        if a.type != b.type { output.insert("type") }
        if a.contractualWeeklyMinutes != b.contractualWeeklyMinutes { output.insert("contractualWeeklyMinutes") }
        if a.grossHourlyRate != b.grossHourlyRate { output.insert("grossHourlyRate") }
        if a.hireDateEpochDay != b.hireDateEpochDay { output.insert("hireDateEpochDay") }
        if a.payrollCutoffDay != b.payrollCutoffDay { output.insert("payrollCutoffDay") }
        if a.forfaitHoursPeriod != b.forfaitHoursPeriod { output.insert("forfaitHoursPeriod") }
        if a.forfaitHours != b.forfaitHours { output.insert("forfaitHours") }
        if a.forfaitAnnualDays != b.forfaitAnnualDays { output.insert("forfaitAnnualDays") }
        if a.monthlyGrossSalary != b.monthlyGrossSalary { output.insert("monthlyGrossSalary") }
    }
}
