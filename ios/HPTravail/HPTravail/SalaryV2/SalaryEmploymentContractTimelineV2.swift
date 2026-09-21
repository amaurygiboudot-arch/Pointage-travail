import Foundation

/// Éditeur déterministe de la chronologie des contrats salariés confirmés.
///
/// La date d'effet d'un contrat ou d'un avenant est une donnée confirmée distincte de la date
/// d'embauche. Elle n'est jamais déduite de la date courante ni du mois de paie consulté.
enum SalaryEmploymentContractTimelineV2 {
    static func upsertEffectiveVersion(
        existing: [SalaryEmploymentContractSnapshotV2],
        contract: ContractV2,
        effectiveFromEpochDay: Int64,
        sourceId: String,
        checkedAtMs: Int64,
        note: String? = nil
    ) -> [SalaryEmploymentContractSnapshotV2]? {
        // Ne jamais transformer implicitement un historique corrompu en chronologie valide.
        guard SalaryEmploymentContractHistoryV2(existing) != nil else { return nil }

        let employerId = contract.employerId.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedContract = ContractV2(
            id: contract.id.trimmingCharacters(in: .whitespacesAndNewlines),
            employerId: employerId,
            type: contract.type,
            contractualWeeklyMinutes: contract.contractualWeeklyMinutes,
            grossHourlyRate: contract.grossHourlyRate,
            hireDateEpochDay: contract.hireDateEpochDay,
            payrollCutoffDay: contract.payrollCutoffDay,
            forfaitHoursPeriod: contract.forfaitHoursPeriod,
            forfaitHours: contract.forfaitHours,
            forfaitAnnualDays: contract.forfaitAnnualDays,
            monthlyGrossSalary: contract.monthlyGrossSalary
        )
        let source = sourceId.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedNote = note?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank
        guard !employerId.isEmpty, !source.isEmpty, checkedAtMs >= 0 else { return nil }

        let sameEmployer = existing
            .filter { normalizeCompanyId($0.contract.employerId) == employerId }
            .sorted { $0.effectiveFromEpochDay < $1.effectiveFromEpochDay }
        let sameStart = sameEmployer.first { $0.effectiveFromEpochDay == effectiveFromEpochDay }
        let next = sameEmployer.first { $0.effectiveFromEpochDay > effectiveFromEpochDay }

        let candidateEnd: Int64?
        if let existingEnd = sameStart?.effectiveToEpochDay {
            candidateEnd = existingEnd
        } else if let next {
            guard let end = dayBefore(next.effectiveFromEpochDay) else { return nil }
            candidateEnd = end
        } else {
            candidateEnd = nil
        }

        let versionId = sameStart?.versionId.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank
            ?? "effective-\(effectiveFromEpochDay)"
        let candidate = SalaryEmploymentContractSnapshotV2(
            versionId: versionId,
            sourceId: source,
            effectiveFromEpochDay: effectiveFromEpochDay,
            effectiveToEpochDay: candidateEnd,
            contract: normalizedContract,
            checkedAtMs: checkedAtMs,
            note: normalizedNote
        )

        var updated: [SalaryEmploymentContractSnapshotV2] = []
        for snapshot in existing {
            guard normalizeCompanyId(snapshot.contract.employerId) == employerId else {
                updated.append(snapshot)
                continue
            }
            if snapshot.effectiveFromEpochDay == effectiveFromEpochDay {
                continue
            }
            if snapshot.effectiveFromEpochDay < effectiveFromEpochDay,
               snapshot.effectiveToEpochDay == nil || snapshot.effectiveToEpochDay! >= effectiveFromEpochDay {
                guard let previousEnd = dayBefore(effectiveFromEpochDay) else { return nil }
                updated.append(
                    SalaryEmploymentContractSnapshotV2(
                        versionId: snapshot.versionId,
                        sourceId: snapshot.sourceId,
                        effectiveFromEpochDay: snapshot.effectiveFromEpochDay,
                        effectiveToEpochDay: previousEnd,
                        contract: snapshot.contract,
                        checkedAtMs: snapshot.checkedAtMs,
                        note: snapshot.note
                    )
                )
            } else {
                updated.append(snapshot)
            }
        }
        updated.append(candidate)

        guard SalaryEmploymentContractHistoryV2(updated) != nil else { return nil }
        return updated.sorted {
            let leftCompany = normalizeCompanyId($0.contract.employerId)
            let rightCompany = normalizeCompanyId($1.contract.employerId)
            if leftCompany != rightCompany { return leftCompany < rightCompany }
            if $0.effectiveFromEpochDay != $1.effectiveFromEpochDay {
                return $0.effectiveFromEpochDay < $1.effectiveFromEpochDay
            }
            return $0.versionId < $1.versionId
        }
    }

    private static func dayBefore(_ epochDay: Int64) -> Int64? {
        guard epochDay != Int64.min else { return nil }
        return epochDay - 1
    }

    private static func normalizeCompanyId(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

private extension String {
    var nilIfBlank: String? { isEmpty ? nil : self }
}
