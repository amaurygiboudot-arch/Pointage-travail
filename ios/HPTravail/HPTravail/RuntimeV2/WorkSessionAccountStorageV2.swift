import Foundation
import CryptoKit

/// Account-scoped journals never claim ownership of historical device-wide data.
/// The old V1/V2 keys remain intact for an explicit, verified recovery procedure.
enum WorkSessionAccountStorageV2 {
    static func authenticationScope(available: Bool, userId: String?) -> String? {
        guard available else { return nil }
        guard let userId else { return "guest" }
        let uid = userId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !uid.isEmpty else { return nil }
        return "uid:" + uid
    }

    static func scope(_ account: String?) -> String? {
        guard let account, !account.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        return account
    }

    static func key(account: String) -> String? {
        guard let account = scope(account) else { return nil }
        return "hp_travail_sessions_account_v2." + Data(account.utf8).base64EncodedString()
    }

    static func read(account: String, defaults: UserDefaults) -> WorkSessionsReadV2 {
        lock.lock(); defer { lock.unlock() }
        if defaults.object(forKey: recoveryKey) != nil {
            guard let receipt = receipt(defaults) else { return .corrupt }
            if receipt.owner == account {
                guard receipt.complete else { return .corrupt }
                return .valid(receipt.sessions)
            }
        }
        return readOriginalAccount(account: account, defaults: defaults)
    }

    private static func readOriginalAccount(account: String, defaults: UserDefaults) -> WorkSessionsReadV2 {
        guard let key = key(account: account) else { return .corrupt }
        guard defaults.object(forKey: key) != nil else { return .missing }
        guard let data = defaults.data(forKey: key) else { return .corrupt }
        return WorkSessionPersistenceV2.read(data)
    }

    static func write(_ sessions: [WorkSession], account: String, defaults: UserDefaults) -> Bool {
        lock.lock(); defer { lock.unlock() }
        if defaults.object(forKey: recoveryKey) != nil {
            guard var receipt = receipt(defaults) else { return false }
            if receipt.owner == account {
                guard receipt.complete, WorkSessionPersistenceV2.isStructurallyValid(sessions) else { return false }
                receipt.sessions = sessions
                return saveReceipt(receipt, defaults: defaults)
            }
        }
        guard let key = key(account: account),
              WorkSessionPersistenceV2.isStructurallyValid(sessions),
              let data = try? JSONEncoder().encode(sessions),
              WorkSessionPersistenceV2.read(data) == .valid(sessions) else { return false }
        defaults.set(data, forKey: key)
        return read(account: account, defaults: defaults) == .valid(sessions)
    }
    static let recoveryKey = "hp_travail_sessions_account_recovery_v2"
    private static let lock = NSRecursiveLock()

    struct RecoveryPreview: Equatable {
        let account: String
        let sourceDigest: String
        let destinationDigest: String
        let historicalCount: Int
        let firstEntry: Date?
        let lastEntry: Date?
        let containsOpenSession: Bool
    }

    enum RecoveryError: Error, LocalizedError {
        case signedInRequired, missing, corrupt, conflict, changed, reserved, persistence
        var errorDescription: String? {
            switch self {
            case .signedInRequired: return "Connecte-toi à ton compte avant de déclarer la propriété de l’ancien journal."
            case .missing: return "Aucun ancien journal à récupérer."
            case .corrupt: return "Journal ou reçu de récupération illisible. Les originaux sont conservés ; aucune récupération automatique."
            case .conflict: return "Les journaux comportent des pointages incompatibles, des périodes qui se chevauchent ou plusieurs sessions ouvertes. Une vérification est nécessaire."
            case .changed: return "Le compte ou les journaux ont changé depuis l’aperçu. Consulte un nouvel aperçu."
            case .reserved: return "Cet ancien journal est déjà réservé ou attribué à un autre compte."
            case .persistence: return "La récupération n’a pas pu être confirmée. Les originaux sont conservés ; réessaie avec le même compte."
            }
        }
    }

    private struct SourceBytes: Codable { let primary: Data?; let legacy: Data? }
    private struct RecoveryReceipt: Codable, Equatable {
        let version: Int
        let owner: String
        let sourceDigest: String
        let destinationDigest: String
        let declaredAt: Date
        let ownershipBasis: String
        var complete: Bool
        let recoveredHistorical: [WorkSession]
        var sessions: [WorkSession]
    }

    private static func digest(_ data: Data?) -> String {
        // Missing and an empty blob are distinct snapshots.
        let tagged = Data([data == nil ? 0 : 1]) + (data ?? Data())
        return SHA256.hash(data: tagged).map { String(format: "%02x", $0) }.joined()
    }

    private static func source(_ defaults: UserDefaults) throws -> (String, [WorkSession]) {
        let primary = defaults.data(forKey: WorkSessionStorageV2.primaryKey)
        let legacy = defaults.data(forKey: WorkSessionStorageV2.legacyKey)
        guard primary != nil || legacy != nil else {
            if defaults.object(forKey: WorkSessionStorageV2.primaryKey) != nil || defaults.object(forKey: WorkSessionStorageV2.legacyKey) != nil { throw RecoveryError.corrupt }
            throw RecoveryError.missing
        }
        guard defaults.object(forKey: WorkSessionStorageV2.primaryKey) == nil || primary != nil,
              defaults.object(forKey: WorkSessionStorageV2.legacyKey) == nil || legacy != nil else { throw RecoveryError.corrupt }
        let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
        let hash = digest(try encoder.encode(SourceBytes(primary: primary, legacy: legacy)))
        // Both sources must be accounted for. A valid V2 journal does not prove that
        // all V1 records were migrated. Divergent identities or overlaps need review.
        var historical: [WorkSession] = []
        if let primary {
            guard case .valid(let decoded) = WorkSessionPersistenceV2.read(primary) else { throw RecoveryError.corrupt }
            historical = decoded
        }
        if let legacy {
            guard case .valid(let decoded) = WorkSessionPersistenceV2.readLegacy(legacy) else { throw RecoveryError.corrupt }
            historical = try merged(decoded, historical)
        }
        return (hash, historical.sorted { $0.entry < $1.entry })
    }

    private static func receipt(_ defaults: UserDefaults) -> RecoveryReceipt? {
        guard let data = defaults.data(forKey: recoveryKey),
              let value = try? JSONDecoder().decode(RecoveryReceipt.self, from: data),
              value.version == 1, value.owner.hasPrefix("uid:"), value.owner.count > 4,
              value.ownershipBasis == "explicit_user_declaration_all_records",
              value.declaredAt.timeIntervalSinceReferenceDate.isFinite,
              WorkSessionPersistenceV2.isStructurallyValid(value.recoveredHistorical),
              WorkSessionPersistenceV2.isStructurallyValid(value.sessions) else { return nil }
        return value
    }

    private static func saveReceipt(_ value: RecoveryReceipt, defaults: UserDefaults) -> Bool {
        guard WorkSessionPersistenceV2.isStructurallyValid(value.sessions),
              let data = try? JSONEncoder().encode(value) else { return false }
        // Receipt and destination journal are a single atomic preference value.
        defaults.set(data, forKey: recoveryKey)
        return receipt(defaults) == value
    }

    static func hasUnassignedHistory(defaults: UserDefaults) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard defaults.object(forKey: WorkSessionStorageV2.primaryKey) != nil || defaults.object(forKey: WorkSessionStorageV2.legacyKey) != nil else { return false }
        guard let receipt = receipt(defaults), receipt.complete,
              let original = try? source(defaults), original.0 == receipt.sourceDigest else { return true }
        // Attribution evidence is immutable; later legitimate journal edits do not revoke it.
        return original.1 != receipt.recoveredHistorical
    }

    enum DiagnosticHistory: String {
        case complete, unassigned, corrupt, conflict, recoveryIncomplete, changed
    }

    /// Read-only explanation; never assigns historical records or relaxes payroll checks.
    static func diagnosticHistory(defaults: UserDefaults) -> DiagnosticHistory {
        lock.lock(); defer { lock.unlock() }
        if defaults.object(forKey: recoveryKey) != nil {
            guard let value = receipt(defaults) else { return .corrupt }
            guard value.complete else { return .recoveryIncomplete }
        }
        guard hasUnassignedHistory(defaults: defaults) else { return .complete }
        do {
            _ = try source(defaults)
            return defaults.object(forKey: recoveryKey) == nil ? .unassigned : .changed
        } catch RecoveryError.conflict { return .conflict }
        catch { return .corrupt }
    }

    private static func merged(_ historical: [WorkSession], _ current: [WorkSession]) throws -> [WorkSession] {
        var merged = Dictionary(uniqueKeysWithValues: current.map { ($0.id, $0) })
        for session in historical {
            if let existing = merged[session.id], existing != session { throw RecoveryError.conflict }
            merged[session.id] = session
        }
        let result = merged.values.sorted { $0.entry < $1.entry }
        guard WorkSessionPersistenceV2.isStructurallyValid(result) else { throw RecoveryError.conflict }
        return result
    }

    static func recoveryPreview(account: String?, defaults: UserDefaults) throws -> RecoveryPreview {
        lock.lock(); defer { lock.unlock() }
        guard let account, account.hasPrefix("uid:"), account.count > 4 else { throw RecoveryError.signedInRequired }
        let original = try source(defaults)
        guard let accountKey = key(account: account) else { throw RecoveryError.signedInRequired }
        let destinationHash = digest(defaults.data(forKey: accountKey))
        if defaults.object(forKey: recoveryKey) != nil {
            guard let value = receipt(defaults) else { throw RecoveryError.corrupt }
            guard value.owner == account else { throw RecoveryError.reserved }
            guard !value.complete else { throw RecoveryError.changed }
            guard value.sourceDigest == original.0, value.destinationDigest == destinationHash else { throw RecoveryError.changed }
        }
        let current: [WorkSession]
        switch readOriginalAccount(account: account, defaults: defaults) {
        case .missing: current = []
        case .valid(let sessions): current = sessions
        case .corrupt: throw RecoveryError.corrupt
        }
        _ = try merged(original.1, current)
        return RecoveryPreview(account: account, sourceDigest: original.0, destinationDigest: destinationHash,
            historicalCount: original.1.count, firstEntry: original.1.map(\.entry).min(),
            lastEntry: original.1.map(\.entry).max(), containsOpenSession: original.1.contains { $0.exit == nil })
    }

    /// Explicit declaration by the person, not an ownership claim verified by Firebase.
    static func recover(_ preview: RecoveryPreview, accountProvider: () -> String?, defaults: UserDefaults) throws {
        lock.lock(); defer { lock.unlock() }
        guard accountProvider() == preview.account else { throw RecoveryError.changed }
        guard try recoveryPreview(account: preview.account, defaults: defaults) == preview else { throw RecoveryError.changed }
        let original = try source(defaults)
        let current: [WorkSession]
        switch readOriginalAccount(account: preview.account, defaults: defaults) {
        case .missing: current = []
        case .valid(let sessions): current = sessions
        case .corrupt: throw RecoveryError.corrupt
        }
        let candidate = try merged(original.1, current)
        var value = RecoveryReceipt(version: 1, owner: preview.account, sourceDigest: preview.sourceDigest,
            destinationDigest: preview.destinationDigest, declaredAt: Date(),
            ownershipBasis: "explicit_user_declaration_all_records", complete: false,
            recoveredHistorical: original.1, sessions: candidate)
        guard accountProvider() == preview.account else { throw RecoveryError.changed }
        guard saveReceipt(value, defaults: defaults) else { throw RecoveryError.persistence }
        // A crash here leaves a unique-owner reservation, replayable only from the same preview.
        guard accountProvider() == preview.account,
              try source(defaults).0 == preview.sourceDigest,
              digest(defaults.data(forKey: key(account: preview.account)!)) == preview.destinationDigest else { throw RecoveryError.changed }
        value.complete = true
        guard saveReceipt(value, defaults: defaults) else { throw RecoveryError.persistence }
    }

}
