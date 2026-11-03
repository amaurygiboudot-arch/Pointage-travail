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

    enum DiagnosticStage: String {
        case primaryV2, legacyV1, accountV2, historicalMerge, accountMerge, context, complete
        var label: String {
            switch self {
            case .primaryV2: return "lecture de l’ancien V2"
            case .legacyV1: return "lecture de l’ancien V1 (compatibilité iOS)"
            case .accountV2: return "lecture du journal du compte"
            case .historicalMerge: return "rapprochement des anciens journaux"
            case .accountMerge: return "rapprochement avec le compte"
            case .context: return "vérification du contexte"
            case .complete: return "contrôle terminé"
            }
        }
    }
    enum DiagnosticSource: String {
        case primaryV2, legacyV1, accountV2
        var label: String {
            switch self {
            case .primaryV2: return "Ancien V2"
            case .legacyV1: return "Ancien V1"
            case .accountV2: return "Journal du compte"
            }
        }
    }
    struct DiagnosticReference: Equatable {
        let source: DiagnosticSource
        let path: [WorkSessionPersistenceV2.PathPart]
        var text: String {
            guard case .index(let index) = path.first else { return source.label + (path.isEmpty ? "" : " : " + path.map(\.text).joined(separator: ".")) }
            return source.label + ", pointage n° \(index + 1)" + (path.count > 1 ? " : " + path.dropFirst().map(\.text).joined(separator: ".") : "")
        }
    }
    enum DiagnosticReason: Equatable {
        case validation(WorkSessionPersistenceV2.IssueCode)
        case identityConflict, unreadableStorage, signedInRequired, missing, unreadable, conflict, changed, reserved, unverifiable, eligible
        var label: String {
            switch self {
            case .validation(let issue):
                switch issue {
                case .invalidJSON: return "JSON illisible"
                case .missingField: return "champ obligatoire absent"
                case .invalidType: return "type de champ invalide"
                case .invalidValue: return "valeur de champ invalide"
                case .duplicateIdentity: return "identifiant répété"
                case .multipleOpenSessions: return "plusieurs pointages ouverts"
                case .multipleOpenPauses: return "plusieurs pauses ouvertes"
                case .invalidTime: return "horaires incohérents"
                case .unqualifiedPause: return "pause terminée sans statut payé"
                case .overlap: return "périodes qui se chevauchent"
                case .invalidSegment: return "segment incohérent"
                case .invalidGPSProvenance: return "origine GPS incohérente"
                }
            case .identityConflict: return "même identifiant, contenu différent"
            case .unreadableStorage, .unreadable: return "source ou reçu illisible"
            case .signedInRequired: return "connexion au compte nécessaire"
            case .missing: return "aucun ancien journal"
            case .conflict: return "journaux incompatibles"
            case .changed: return "contexte modifié"
            case .reserved: return "journal réservé à un autre compte"
            case .unverifiable: return "contrôle non vérifiable"
            case .eligible: return "aucun blocage détecté"
            }
        }
        var code: String {
            switch self {
            case .validation(let code): return code.rawValue
            case .identityConflict: return "identityConflict"
            case .unreadableStorage: return "unreadableStorage"
            case .signedInRequired: return "signedInRequired"
            case .missing: return "missing"
            case .unreadable: return "unreadable"
            case .conflict: return "conflict"
            case .changed: return "changed"
            case .reserved: return "reserved"
            case .unverifiable: return "unverifiable"
            case .eligible: return "eligible"
            }
        }
    }
    struct RecoveryDiagnostic: Equatable {
        let stage: DiagnosticStage
        let reason: DiagnosticReason
        var code: String { reason.code }
        let references: [DiagnosticReference]
        var text: String {
            if stage == .complete { return "Contrôle terminé : aucun blocage détecté. Aucun pointage transféré." }
            let location = references.map(\.text).joined(separator: " ; ")
            return "Étape : \(stage.label). Motif : \(reason.label) (\(code))." + (location.isEmpty ? "" : "\n" + location) +
                "\nContrôle interrompu à cette étape ; les étapes suivantes n’ont pas été vérifiées. Aucun pointage transféré."
        }
    }
    fileprivate enum StoredValue: Equatable {
        case missing, data(Data), unreadable(String)
        init(_ object: Any?) {
            guard let object else { self = .missing; return }
            if let data = object as? Data { self = .data(data); return }
            // Hash the property-list value, including its type; never render it.
            let bytes = try? PropertyListSerialization.data(fromPropertyList: object, format: .binary, options: 0)
            self = .unreadable(bytes.map { digest($0) } ?? UUID().uuidString)
        }
        var data: Data? { if case .data(let data) = self { return data }; return nil }
        var exists: Bool { self != .missing }
    }
    fileprivate struct RecoveryInputs: Equatable {
        let primary: StoredValue
        let legacy: StoredValue
        let destination: StoredValue
        let recovery: StoredValue
    }
    struct DiagnosticConsent {
        fileprivate let account: String
        fileprivate let generation: UInt64
        fileprivate let inputs: RecoveryInputs
    }
    private final class RecoveryTrace {
        var references: [UUID: DiagnosticReference] = [:]
        var report: RecoveryDiagnostic?
        func reject(_ stage: DiagnosticStage, _ reason: DiagnosticReason, _ references: [DiagnosticReference] = []) {
            if report == nil { report = RecoveryDiagnostic(stage: stage, reason: reason, references: references) }
        }
        func reject(_ stage: DiagnosticStage, _ issue: WorkSessionPersistenceV2.Issue,
                    reference: ([WorkSessionPersistenceV2.PathPart]) -> DiagnosticReference) {
            reject(stage, .validation(issue.code), [issue.path, issue.otherPath].compactMap { $0 }.map(reference))
        }
    }
    private static func inputs(account: String, defaults: UserDefaults) -> RecoveryInputs {
        RecoveryInputs(primary: StoredValue(defaults.object(forKey: WorkSessionStorageV2.primaryKey)),
            legacy: StoredValue(defaults.object(forKey: WorkSessionStorageV2.legacyKey)),
            destination: StoredValue(defaults.object(forKey: key(account: account)!)),
            recovery: StoredValue(defaults.object(forKey: recoveryKey)))
    }

    /// Captures bytes only. Detailed decoding starts only after the separate ownership declaration.
    static func diagnosticConsent(account: String?, generation: UInt64, defaults: UserDefaults) throws -> DiagnosticConsent {
        lock.lock(); defer { lock.unlock() }
        guard let account, account.hasPrefix("uid:"), account.count > 4 else { throw RecoveryError.signedInRequired }
        return DiagnosticConsent(account: account, generation: generation, inputs: inputs(account: account, defaults: defaults))
    }

    static func diagnosticIsCurrent(_ consent: DiagnosticConsent, account: String?, generation: UInt64, defaults: UserDefaults) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return consent.account == account && consent.generation == generation &&
            consent.inputs == inputs(account: consent.account, defaults: defaults)
    }

    static func recoveryDiagnostic(_ consent: DiagnosticConsent, ownershipDeclared: Bool,
                                   contextProvider: () -> (String?, UInt64), defaults: UserDefaults) throws -> RecoveryDiagnostic {
        lock.lock(); defer { lock.unlock() }
        let context = contextProvider()
        guard ownershipDeclared, diagnosticIsCurrent(consent, account: context.0, generation: context.1, defaults: defaults) else { throw RecoveryError.changed }
        // A foreign reservation never grants access to the historical details.
        if consent.inputs.recovery.exists {
            // Read ownership before decoding any journal embedded in a receipt.
            struct Owner: Decodable { let owner: String }
            guard let bytes = consent.inputs.recovery.data,
                  let owner = try? JSONDecoder().decode(Owner.self, from: bytes) else { throw RecoveryError.corrupt }
            guard owner.owner == consent.account else { throw RecoveryError.reserved }
            guard receipt(bytes) != nil else { throw RecoveryError.corrupt }
        }
        let trace = RecoveryTrace()
        do { _ = try recoveryPreview(account: consent.account, inputs: consent.inputs, trace: trace) }
        catch let error as RecoveryError { trace.reject(.context, contextCode(error)) }
        catch { trace.reject(.context, .unverifiable) }
        let checked = contextProvider()
        guard diagnosticIsCurrent(consent, account: checked.0, generation: checked.1, defaults: defaults) else { throw RecoveryError.changed }
        return trace.report ?? RecoveryDiagnostic(stage: .complete, reason: .eligible, references: [])
    }

    private static func contextCode(_ error: RecoveryError) -> DiagnosticReason {
        switch error {
        case .signedInRequired: return .signedInRequired
        case .missing: return .missing
        case .corrupt: return .unreadable
        case .conflict: return .conflict
        case .changed: return .changed
        case .reserved: return .reserved
        case .persistence: return .unverifiable
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
        try source(RecoveryInputs(primary: StoredValue(defaults.object(forKey: WorkSessionStorageV2.primaryKey)),
            legacy: StoredValue(defaults.object(forKey: WorkSessionStorageV2.legacyKey)), destination: .missing, recovery: .missing))
    }

    private static func source(_ inputs: RecoveryInputs, trace: RecoveryTrace? = nil) throws -> (String, [WorkSession]) {
        let primary = inputs.primary.data, legacy = inputs.legacy.data
        guard primary != nil || legacy != nil else {
            if inputs.primary.exists || inputs.legacy.exists {
                let source: DiagnosticSource = inputs.primary.exists ? .primaryV2 : .legacyV1
                trace?.reject(source == .primaryV2 ? .primaryV2 : .legacyV1, .unreadableStorage, [.init(source: source, path: [])])
                throw RecoveryError.corrupt
            }
            throw RecoveryError.missing
        }
        guard !inputs.primary.exists || primary != nil else {
            trace?.reject(.primaryV2, .unreadableStorage, [.init(source: .primaryV2, path: [])]); throw RecoveryError.corrupt
        }
        guard !inputs.legacy.exists || legacy != nil else {
            trace?.reject(.legacyV1, .unreadableStorage, [.init(source: .legacyV1, path: [])]); throw RecoveryError.corrupt
        }
        let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
        let hash = digest(try encoder.encode(SourceBytes(primary: primary, legacy: legacy)))
        var historical: [WorkSession] = []
        if let primary {
            historical = try decoded(primary, source: .primaryV2, trace: trace)
            trace?.references = references(historical, source: .primaryV2)
        }
        if let legacy {
            let decoded = try decoded(legacy, source: .legacyV1, trace: trace)
            historical = try merged(decoded, historical, stage: .historicalMerge,
                historicalReferences: references(decoded, source: .legacyV1), currentReferences: trace?.references ?? [:], trace: trace)
        }
        return (hash, historical.sorted { $0.entry < $1.entry })
    }

    private static func references(_ sessions: [WorkSession], source: DiagnosticSource) -> [UUID: DiagnosticReference] {
        Dictionary(uniqueKeysWithValues: sessions.enumerated().map { ($0.element.id, DiagnosticReference(source: source, path: [.index($0.offset)])) })
    }

    private static func decoded(_ data: Data, source: DiagnosticSource, trace: RecoveryTrace?) throws -> [WorkSession] {
        let inspection = WorkSessionPersistenceV2.inspect(data, historicalUnpaidCompatibility: source == .legacyV1)
        if let issue = inspection.issue {
            let stage: DiagnosticStage = source == .primaryV2 ? .primaryV2 : source == .legacyV1 ? .legacyV1 : .accountV2
            trace?.reject(stage, issue) { DiagnosticReference(source: source, path: $0) }
        }
        guard case .valid(let sessions) = inspection.result else { throw RecoveryError.corrupt }
        return sessions
    }

    private static func receipt(_ defaults: UserDefaults) -> RecoveryReceipt? {
        receipt(defaults.data(forKey: recoveryKey))
    }

    private static func receipt(_ data: Data?) -> RecoveryReceipt? {
        guard let data, let value = try? JSONDecoder().decode(RecoveryReceipt.self, from: data),
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

    private static func merged(_ historical: [WorkSession], _ current: [WorkSession],
                               stage: DiagnosticStage = .accountMerge,
                               historicalReferences: [UUID: DiagnosticReference] = [:],
                               currentReferences: [UUID: DiagnosticReference] = [:], trace: RecoveryTrace? = nil) throws -> [WorkSession] {
        var merged = Dictionary(uniqueKeysWithValues: current.map { ($0.id, $0) })
        var provenance = currentReferences
        for session in historical {
            if let existing = merged[session.id], existing != session {
                trace?.reject(stage, .identityConflict, [historicalReferences[session.id], provenance[session.id]].compactMap { $0 }.map {
                    DiagnosticReference(source: $0.source, path: $0.path + [.field(.id)])
                })
                throw RecoveryError.conflict
            }
            merged[session.id] = session
            provenance[session.id] = historicalReferences[session.id]
        }
        let result = merged.values.sorted { $0.entry < $1.entry }
        if let issue = WorkSessionPersistenceV2.structuralIssue(result) {
            if let trace {
                let mapped = [issue.path, issue.otherPath].compactMap { $0 }.compactMap { path -> DiagnosticReference? in
                    guard case .index(let index) = path.first, result.indices.contains(index),
                          let original = provenance[result[index].id] else { return nil }
                    return DiagnosticReference(source: original.source, path: original.path + path.dropFirst())
                }
                trace.reject(stage, .validation(issue.code), mapped)
            }
            throw RecoveryError.conflict
        }
        trace?.references = provenance
        return result
    }

    static func recoveryPreview(account: String?, defaults: UserDefaults) throws -> RecoveryPreview {
        lock.lock(); defer { lock.unlock() }
        guard let account, account.hasPrefix("uid:"), account.count > 4 else { throw RecoveryError.signedInRequired }
        return try recoveryPreview(account: account, inputs: inputs(account: account, defaults: defaults))
    }

    private static func recoveryPreview(account: String, inputs: RecoveryInputs, trace: RecoveryTrace? = nil) throws -> RecoveryPreview {
        let original = try source(inputs, trace: trace)
        let destinationHash = digest(inputs.destination.data)
        if inputs.recovery.exists {
            guard let value = receipt(inputs.recovery.data) else { throw RecoveryError.corrupt }
            guard value.owner == account else { throw RecoveryError.reserved }
            guard !value.complete else { throw RecoveryError.changed }
            guard value.sourceDigest == original.0, value.destinationDigest == destinationHash else { throw RecoveryError.changed }
        }
        let current: [WorkSession]
        if !inputs.destination.exists { current = [] }
        else if let data = inputs.destination.data { current = try decoded(data, source: .accountV2, trace: trace) }
        else {
            trace?.reject(.accountV2, .unreadableStorage, [.init(source: .accountV2, path: [])]); throw RecoveryError.corrupt
        }
        _ = try merged(original.1, current, historicalReferences: trace?.references ?? [:],
            currentReferences: references(current, source: .accountV2), trace: trace)
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
