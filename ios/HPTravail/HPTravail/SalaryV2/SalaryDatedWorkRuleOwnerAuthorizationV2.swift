import Foundation

/// Un compte personnel gère son propre profil salarié. Les entreprises ne sont
/// pas des preuves d'appartenance à un utilisateur sans confirmation de contrat.
enum SalaryDatedWorkRuleOwnerAuthorizationV2 {
    static func ownerForSelf(
        authenticatedUid: String?,
        employerId: String,
        contractVersionId: String
    ) -> SalaryWorkRuleOwnerV2? {
        guard let uid = authenticatedUid?.trimmingCharacters(in: .whitespacesAndNewlines),
              !uid.isEmpty, !uid.unicodeScalars.contains(where: { $0.value == 0 }) else {
            return nil
        }
        let owner = SalaryWorkRuleOwnerV2(
            accountId: uid, employeeId: uid,
            employerId: employerId.trimmingCharacters(in: .whitespacesAndNewlines),
            contractVersionId: contractVersionId.trimmingCharacters(in: .whitespacesAndNewlines)
        )
        return owner.isValid ? owner : nil
    }

    static func authorizes(_ owner: SalaryWorkRuleOwnerV2, authenticatedUid: String?) -> Bool {
        guard let uid = authenticatedUid?.trimmingCharacters(in: .whitespacesAndNewlines) else {
            return false
        }
        return owner.isValid && !uid.isEmpty && uid == owner.accountId && uid == owner.employeeId
    }
}

enum SalaryDatedWorkRuleContractScopeV2 {
    static func containedInContract(
        _ record: SalaryDatedWorkRuleV2,
        contract: SalaryEmploymentContractSnapshotV2
    ) -> Bool {
        guard SalaryDatedWorkRuleApplicabilityV2.validRecord(record),
              record.owner.employerId == contract.contract.employerId,
              record.owner.contractVersionId == contract.versionId,
              record.effectiveFromEpochDay >= contract.effectiveFromEpochDay else {
            return false
        }
        guard let lastDay = contract.effectiveToEpochDay else { return true }
        guard lastDay < Int64.max, let recordEnd = record.effectiveToEpochDay else { return false }
        return recordEnd <= lastDay + 1
    }

    /// Une provenance de contrat daté peut confirmer le référentiel de pointage,
    /// PAS le taux d'une prime, pause payée ou disposition légale.
    static func verifiedApplicability(
        _ record: SalaryDatedWorkRuleV2,
        contract: SalaryEmploymentContractSnapshotV2
    ) -> Bool {
        guard containedInContract(record, contract: contract) else { return false }
        if record.confirmation == .toConfirm { return true }
        return record.topic == .timeAccounting &&
            contract.checkedAtMs > 0 &&
            record.sourceId == contract.sourceId &&
            record.ruleReference == "contract:\(contract.versionId)" &&
            record.checkedAtMs == contract.checkedAtMs &&
            !record.explicitlyNotApplicable
    }
}
