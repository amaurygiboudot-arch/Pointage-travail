import Foundation

/// Entrée canonique d'une version contractuelle datée.
///
/// `effectiveFromEpochDay` est volontairement distinct de `contract.hireDateEpochDay`.
/// Aucune des deux dates n'est déduite de l'autre.
struct SalaryEmploymentContractVersionInputV2: Equatable {
    let contract: ContractV2
    let effectiveFromEpochDay: Int64
    let sourceId: String
    let checkedAtMs: Int64
    let note: String?
}

struct SalaryEmploymentContractVersionInputValidationV2: Equatable {
    let ready: Bool
    let warnings: [String]
}

enum SalaryEmploymentContractVersionInputValidatorV2 {
    static let invalidIdWarning =
        "Contrat : identifiant de contrat ou d'employeur manquant."
    static let invalidSourceWarning =
        "Contrat : la source de confirmation est obligatoire."
    static let effectBeforeHireWarning =
        "Contrat : la date d'effet confirmée ne peut pas précéder la date d'entrée dans l'entreprise."
    static let legacyForfaitWarning =
        "Contrat : l'ancien type forfait générique doit être précisé en forfait heures ou forfait jours."
    static let invalidTermsWarning =
        "Contrat : les paramètres contractuels confirmés sont incomplets ou incohérents."

    static func validate(
        _ input: SalaryEmploymentContractVersionInputV2
    ) -> SalaryEmploymentContractVersionInputValidationV2 {
        var warnings: [String] = []
        let contract = input.contract

        if contract.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            contract.employerId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            warnings.append(invalidIdWarning)
        }
        if input.sourceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            warnings.append(invalidSourceWarning)
        }
        if let hireDate = contract.hireDateEpochDay,
           input.effectiveFromEpochDay < hireDate {
            warnings.append(effectBeforeHireWarning)
        }
        if contract.type == .forfait {
            warnings.append(legacyForfaitWarning)
        } else if !validTerms(contract) {
            warnings.append(invalidTermsWarning)
        }

        var seen = Set<String>()
        let unique = warnings.filter { seen.insert($0).inserted }
        return SalaryEmploymentContractVersionInputValidationV2(
            ready: unique.isEmpty,
            warnings: unique
        )
    }

    private static func validTerms(_ contract: ContractV2) -> Bool {
        if let cutoff = contract.payrollCutoffDay, !(1...31).contains(cutoff) {
            return false
        }

        switch contract.type {
        case .fullTime, .partTime, .other:
            guard let weekly = contract.contractualWeeklyMinutes, weekly > 0,
                  let rate = contract.grossHourlyRate, rate.isFinite, rate > 0,
                  contract.forfaitHoursPeriod == nil,
                  contract.forfaitHours == nil,
                  contract.forfaitAnnualDays == nil,
                  contract.monthlyGrossSalary == nil else {
                return false
            }
            return true

        case .forfaitHours:
            guard contract.contractualWeeklyMinutes == nil,
                  contract.grossHourlyRate == nil,
                  let monthly = contract.monthlyGrossSalary, monthly.isFinite, monthly > 0,
                  contract.forfaitHoursPeriod != nil,
                  let hours = contract.forfaitHours, hours.isFinite, hours > 0,
                  contract.forfaitAnnualDays == nil else {
                return false
            }
            return true

        case .forfaitDays:
            guard contract.contractualWeeklyMinutes == nil,
                  contract.grossHourlyRate == nil,
                  let monthly = contract.monthlyGrossSalary, monthly.isFinite, monthly > 0,
                  contract.forfaitHoursPeriod == nil,
                  contract.forfaitHours == nil,
                  let days = contract.forfaitAnnualDays,
                  days.isFinite, days > 0, days <= 218 else {
                return false
            }
            return true

        case .forfait:
            return false
        }
    }
}

enum SalaryEmploymentContractVersionConfirmationV2 {
    struct Result: Equatable {
        let saved: Bool
        let warnings: [String]
    }

    static func confirm(
        _ input: SalaryEmploymentContractVersionInputV2,
        defaults: UserDefaults = .standard
    ) -> Result {
        let validation = SalaryEmploymentContractVersionInputValidatorV2.validate(input)
        guard validation.ready else {
            return Result(saved: false, warnings: validation.warnings)
        }

        let saved = SalaryEmploymentContractHistoryStoreV2.saveEffectiveVersion(
            contract: input.contract,
            effectiveFromEpochDay: input.effectiveFromEpochDay,
            sourceId: input.sourceId.trimmingCharacters(in: .whitespacesAndNewlines),
            checkedAtMs: input.checkedAtMs,
            note: input.note,
            defaults: defaults
        )
        return saved
            ? Result(saved: true, warnings: [])
            : Result(
                saved: false,
                warnings: [
                    "Contrat : la version datée n'a pas pu être enregistrée dans l'historique autoritatif."
                ]
            )
    }
}
