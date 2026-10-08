import CryptoKit
import Foundation

struct SalaryDatedWorkRuleReadResultV2 {
    let records: [SalaryDatedWorkRuleV2]
    let reliable: Bool
    let warnings: [String]
}

/// Persisted references only, not a parallel wage calculator.
/// A verified company and dated contract version must already exist.
enum SalaryDatedWorkRuleStoreV2 {
    static let storageWarning = "Règles individuelles : stockage incohérent ; aucune règle ne peut être certifiée."
    static let ownerWarning = "Règles individuelles : compte, salarié, entreprise ou contrat non confirmé."
    private static let storagePrefix = "dated_work_applicability_v2.owner:"
    private static let lock = NSRecursiveLock()

    private struct Envelope: Codable {
        let schema: Int
        let accountId: String
        let employeeId: String
        let employerId: String
        let contractVersionId: String
        let rules: [SalaryDatedWorkRuleV2]
    }

    static func ownerStorageKey(_ owner: SalaryWorkRuleOwnerV2) -> String {
        let raw = [owner.accountId, owner.employeeId, owner.employerId, owner.contractVersionId]
            .joined(separator: "\0")
        let digest = SHA256.hash(data: Data(raw.utf8))
        return storagePrefix + digest.map { String(format: "%02x", $0) }.joined()
    }

    static func read(
        owner: SalaryWorkRuleOwnerV2, defaults: UserDefaults = .standard
    ) -> SalaryDatedWorkRuleReadResultV2 {
        guard confirmedOwner(owner, defaults: defaults) else {
            return .init(records: [], reliable: false, warnings: [ownerWarning])
        }
        lock.lock()
        defer { lock.unlock() }
        return readLocal(owner, defaults: defaults)
    }

    @discardableResult
    static func append(
        _ record: SalaryDatedWorkRuleV2, owner: SalaryWorkRuleOwnerV2,
        defaults: UserDefaults = .standard
    ) -> Bool {
        guard record.owner == owner, confirmedOwner(owner, defaults: defaults) else { return false }
        lock.lock()
        defer { lock.unlock() }
        let current = readLocal(owner, defaults: defaults)
        guard current.reliable, !current.records.contains(where: { $0.id == record.id }) else { return false }
        return writeLocal(current.records + [record], owner: owner, defaults: defaults)
    }

    @discardableResult
    static func replaceOpenVersion(
        oldRecordId: String, with newRecord: SalaryDatedWorkRuleV2,
        owner: SalaryWorkRuleOwnerV2, defaults: UserDefaults = .standard
    ) -> Bool {
        guard !oldRecordId.isEmpty, newRecord.owner == owner,
              newRecord.confirmation == .confirmed, confirmedOwner(owner, defaults: defaults) else {
            return false
        }
        lock.lock()
        defer { lock.unlock() }
        let current = readLocal(owner, defaults: defaults)
        guard current.reliable, !current.records.contains(where: { $0.id == newRecord.id }),
              let previous = current.records.first(where: { $0.id == oldRecordId }),
              previous.owner == owner, previous.topic == newRecord.topic,
              previous.confirmation == .confirmed,
              previous.effectiveToEpochDay == nil,
              newRecord.effectiveFromEpochDay > previous.effectiveFromEpochDay else {
            return false
        }
        let updated = current.records.map { old -> SalaryDatedWorkRuleV2 in
            if old.id != oldRecordId { return old }
            return SalaryDatedWorkRuleV2(
                id: old.id, owner: old.owner, topic: old.topic,
                effectiveFromEpochDay: old.effectiveFromEpochDay,
                effectiveToEpochDay: newRecord.effectiveFromEpochDay,
                sourceId: old.sourceId, ruleReference: old.ruleReference,
                checkedAtMs: old.checkedAtMs, confirmation: old.confirmation,
                explicitlyNotApplicable: old.explicitlyNotApplicable
            )
        } + [newRecord]
        return writeLocal(updated, owner: owner, defaults: defaults)
    }

    private static func confirmedOwner(
        _ owner: SalaryWorkRuleOwnerV2, defaults: UserDefaults
    ) -> Bool {
        guard owner.isValid else { return false }
        let companies = SalaryCompanyStoreV2.readConfirmed(defaults: defaults)
        guard companies.reliable, companies.companies.contains(where: { $0.id == owner.employerId }) else {
            return false
        }
        let contracts = SalaryEmploymentContractHistoryStoreV2.readConfirmed(defaults: defaults)
        return contracts.reliable && contracts.snapshots.filter {
            $0.versionId == owner.contractVersionId &&
            $0.contract.employerId == owner.employerId
        }.count == 1
    }

    private static func readLocal(
        _ owner: SalaryWorkRuleOwnerV2, defaults: UserDefaults
    ) -> SalaryDatedWorkRuleReadResultV2 {
        let key = ownerStorageKey(owner)
        let primary = defaults.object(forKey: key)
        let backup = defaults.object(forKey: key + ":backup")
        if primary == nil && backup == nil {
            return .init(records: [], reliable: true, warnings: [])
        }
        if let raw = primary as? String,
           let result = decode(raw, owner: owner) {
            return result
        }
        guard let rawBackup = backup as? String,
              let recovered = decode(rawBackup, owner: owner) else {
            return corrupt()
        }
        defaults.set(rawBackup, forKey: key)
        guard defaults.string(forKey: key) == rawBackup else { return corrupt() }
        return recovered
    }

    private static func writeLocal(
        _ records: [SalaryDatedWorkRuleV2], owner: SalaryWorkRuleOwnerV2,
        defaults: UserDefaults
    ) -> Bool {
        guard SalaryDatedWorkRuleApplicabilityV2.validTimeline(owner, records: records),
              let raw = encode(records, owner: owner) else { return false }
        let key = ownerStorageKey(owner)
        // A single authoritative envelope update. Backup follows only after verification.
        defaults.set(raw, forKey: key)
        guard defaults.string(forKey: key) == raw,
              let check = decode(raw, owner: owner),
              check.reliable && check.records == records else { return false }
        defaults.set(raw, forKey: key + ":backup")
        return true
    }

    static func encode(
        _ records: [SalaryDatedWorkRuleV2], owner: SalaryWorkRuleOwnerV2
    ) -> String? {
        guard SalaryDatedWorkRuleApplicabilityV2.validTimeline(owner, records: records) else {
            return nil
        }
        let envelope = Envelope(
            schema: 1, accountId: owner.accountId, employeeId: owner.employeeId,
            employerId: owner.employerId, contractVersionId: owner.contractVersionId,
            rules: records
        )
        guard let data = try? JSONEncoder().encode(envelope) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func decode(
        _ raw: String, owner: SalaryWorkRuleOwnerV2
    ) -> SalaryDatedWorkRuleReadResultV2? {
        guard let data = raw.data(using: .utf8),
              let decoded = try? JSONDecoder().decode(Envelope.self, from: data),
              decoded.schema == 1, decoded.accountId == owner.accountId,
              decoded.employeeId == owner.employeeId,
              decoded.employerId == owner.employerId,
              decoded.contractVersionId == owner.contractVersionId else { return nil }
        let records = decoded.rules.map { $0.withOwner(owner) }
        guard SalaryDatedWorkRuleApplicabilityV2.validTimeline(owner, records: records) else {
            return nil
        }
        return .init(records: records, reliable: true, warnings: [])
    }

    private static func corrupt() -> SalaryDatedWorkRuleReadResultV2 {
        .init(records: [], reliable: false, warnings: [storageWarning])
    }
}
