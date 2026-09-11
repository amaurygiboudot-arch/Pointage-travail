import Foundation

struct WorkSession: Codable, Identifiable {
    let id: UUID
    var entry: Date
    var exit: Date?
    var pauses: [PausePeriod]
}

struct PausePeriod: Codable, Identifiable {
    let id: UUID
    var start: Date
    var end: Date?
}

private struct WorkStoreSchemaHeader: Codable {
    let schemaVersion: Int
}

private struct WorkStoreEnvelope: Codable {
    let schemaVersion: Int
    let sessions: [WorkSession]
}

@MainActor
final class WorkStore: ObservableObject {
    @Published private(set) var sessions: [WorkSession] = []
    @Published private(set) var persistenceWarning: String?

    private let legacyKey = "hp_travail_sessions_v1"
    private let schemaVersion = 1
    private let fileManager = FileManager.default
    private var persistenceReadOnly = false

    init() { load() }

    var currentSession: WorkSession? { sessions.last(where: { $0.exit == nil }) }
    var isWorking: Bool { currentSession != nil }
    var isPaused: Bool { currentSession?.pauses.last?.end == nil && currentSession?.pauses.last != nil }

    func clearPersistenceWarning() {
        persistenceWarning = nil
    }

    func clockIn() {
        guard !persistenceReadOnly else {
            warnReadOnlySchema()
            return
        }
        guard !isWorking else { return }
        sessions.append(WorkSession(id: UUID(), entry: Date(), exit: nil, pauses: []))
        _ = save()
    }

    func togglePause() {
        guard !persistenceReadOnly else {
            warnReadOnlySchema()
            return
        }
        guard let index = sessions.lastIndex(where: { $0.exit == nil }) else { return }
        if let pauseIndex = sessions[index].pauses.lastIndex(where: { $0.end == nil }) {
            sessions[index].pauses[pauseIndex].end = Date()
        } else {
            sessions[index].pauses.append(PausePeriod(id: UUID(), start: Date(), end: nil))
        }
        _ = save()
    }

    func clockOut() {
        guard !persistenceReadOnly else {
            warnReadOnlySchema()
            return
        }
        guard let index = sessions.lastIndex(where: { $0.exit == nil }) else { return }
        if let pauseIndex = sessions[index].pauses.lastIndex(where: { $0.end == nil }) {
            sessions[index].pauses[pauseIndex].end = Date()
        }
        sessions[index].exit = Date()
        _ = save()
    }

    func workedDuration(for session: WorkSession, until endDate: Date = Date()) -> TimeInterval {
        let end = session.exit ?? endDate
        let rawDuration = max(0, end.timeIntervalSince(session.entry))
        let intervals = session.pauses.compactMap { period -> (Date, Date)? in
            let rawEnd = period.end ?? endDate
            let start = max(period.start, session.entry)
            let pauseEnd = min(rawEnd, end)
            return pauseEnd > start ? (start, pauseEnd) : nil
        }.sorted { $0.0 < $1.0 }

        var mergedPause: TimeInterval = 0
        var current: (Date, Date)?
        for interval in intervals {
            guard let existing = current else {
                current = interval
                continue
            }
            if interval.0 <= existing.1 {
                current = (existing.0, max(existing.1, interval.1))
            } else {
                mergedPause += existing.1.timeIntervalSince(existing.0)
                current = interval
            }
        }
        if let existing = current {
            mergedPause += existing.1.timeIntervalSince(existing.0)
        }
        return max(0, rawDuration - min(mergedPause, rawDuration))
    }

    func restorePreviousBackup() -> Bool {
        guard !persistenceReadOnly else {
            warnReadOnlySchema()
            return false
        }
        do {
            let urls = try storageURLs()
            guard let decoded = decodeFile(at: urls.backup) else { return false }
            guard !persistenceReadOnly else { return false }
            sessions = decoded
            guard save(preserveBackup: true, clearWarningOnSuccess: false) else {
                persistenceWarning = "La restauration a été chargée mais n’a pas pu être enregistrée durablement."
                return false
            }
            persistenceWarning = nil
            return true
        } catch {
            persistenceWarning = "Le stockage local est indisponible."
            NSLog("WorkStore restore storage lookup failed: %@", String(describing: error))
            return false
        }
    }

    @discardableResult
    private func save(preserveBackup: Bool = false, clearWarningOnSuccess: Bool = true) -> Bool {
        guard !persistenceReadOnly else {
            warnReadOnlySchema()
            return false
        }
        do {
            let urls = try storageURLs()

            // Never overwrite an on-disk store written by an unsupported schema. Check the
            // historical backup too, because normal rotation would otherwise destroy it.
            if unsupportedSchemaVersion(at: urls.primary) != nil ||
               (!preserveBackup && unsupportedSchemaVersion(at: urls.backup) != nil) {
                persistenceReadOnly = true
                warnReadOnlySchema()
                return false
            }

            let envelope = WorkStoreEnvelope(schemaVersion: schemaVersion, sessions: sessions)
            let data = try JSONEncoder().encode(envelope)

            var previousPrimaryData: Data?
            if !preserveBackup, fileManager.fileExists(atPath: urls.primary.path) {
                if decodeFile(at: urls.primary) != nil {
                    previousPrimaryData = try Data(contentsOf: urls.primary)
                } else if persistenceReadOnly {
                    warnReadOnlySchema()
                    return false
                }
            }

            // Failure here means the current mutation is not durable.
            try data.write(to: urls.primary, options: [.atomic])

            // Once the primary write succeeded, a failure rotating the historical backup
            // must not be reported as an unsaved pointage: the new primary is durable.
            if let previousPrimaryData, !preserveBackup {
                do {
                    try previousPrimaryData.write(to: urls.backup, options: [.atomic])
                } catch {
                    persistenceWarning = "Les données sont enregistrées, mais la copie de secours précédente n’a pas pu être mise à jour."
                    NSLog("WorkStore previous-backup rotation failed: %@", String(describing: error))
                    return true
                }
            }

            if clearWarningOnSuccess { persistenceWarning = nil }
            return true
        } catch {
            persistenceWarning = "La sauvegarde locale n’a pas pu être enregistrée."
            NSLog("WorkStore persistence save failed: %@", String(describing: error))
            return false
        }
    }

    private func load() {
        do {
            let urls = try storageURLs()

            // Inspect both headers before decoding either versioned payload. This protects a
            // future-schema primary or backup even if that schema has changed `sessions` so
            // radically that the current WorkStoreEnvelope can no longer decode it.
            if unsupportedSchemaVersion(at: urls.primary) != nil || unsupportedSchemaVersion(at: urls.backup) != nil {
                persistenceReadOnly = true
                warnReadOnlySchema()
                return
            }

            if let primary = decodeFile(at: urls.primary) {
                sessions = primary
                return
            }
            if persistenceReadOnly { return }

            if let backup = decodeFile(at: urls.backup) {
                sessions = backup
                persistenceWarning = "Les données précédentes ont été restaurées après une erreur de lecture."
                if !save(preserveBackup: true, clearWarningOnSuccess: false) {
                    persistenceWarning = "Les données de secours sont lisibles mais leur restauration durable a échoué."
                }
                return
            }
            if persistenceReadOnly { return }

            if let legacy = decodeLegacyUserDefaults() {
                sessions = legacy
                if save() {
                    UserDefaults.standard.removeObject(forKey: legacyKey)
                } else {
                    persistenceWarning = "Les anciennes données restent protégées, mais leur migration n’a pas abouti."
                }
                return
            }

            if fileManager.fileExists(atPath: urls.primary.path) || fileManager.fileExists(atPath: urls.backup.path) {
                persistenceWarning = "Les données locales sont illisibles. Une restauration peut être nécessaire."
            }
        } catch {
            persistenceWarning = "Le stockage local est indisponible."
            NSLog("WorkStore persistence load storage lookup failed: %@", String(describing: error))
        }
    }

    private func storageURLs() throws -> (primary: URL, backup: URL) {
        let base = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        ).appendingPathComponent("HoraTrack", isDirectory: true)
        try fileManager.createDirectory(at: base, withIntermediateDirectories: true)
        return (
            base.appendingPathComponent("work-sessions.json"),
            base.appendingPathComponent("work-sessions.previous.json")
        )
    }

    private func unsupportedSchemaVersion(at url: URL) -> Int? {
        guard fileManager.fileExists(atPath: url.path) else { return nil }
        do {
            let data = try Data(contentsOf: url)
            let header = try JSONDecoder().decode(WorkStoreSchemaHeader.self, from: data)
            return header.schemaVersion == schemaVersion ? nil : header.schemaVersion
        } catch {
            // A malformed file is handled by the normal corruption/recovery path. Only a
            // successfully decoded unsupported version triggers fail-closed protection.
            return nil
        }
    }

    private func decodeFile(at url: URL) -> [WorkSession]? {
        guard fileManager.fileExists(atPath: url.path) else { return nil }
        do {
            let data = try Data(contentsOf: url)
            let header = try JSONDecoder().decode(WorkStoreSchemaHeader.self, from: data)
            guard header.schemaVersion == schemaVersion else {
                persistenceReadOnly = true
                persistenceWarning = "Ces données ont été créées par une version plus récente de HoraTrack. Elles sont protégées en lecture seule : réinstalle la version la plus récente pour les modifier."
                NSLog("WorkStore unsupported schema version: %d", header.schemaVersion)
                return nil
            }
            let envelope = try JSONDecoder().decode(WorkStoreEnvelope.self, from: data)
            return envelope.sessions
        } catch {
            NSLog("WorkStore decode failed for %@: %@", url.lastPathComponent, String(describing: error))
            return nil
        }
    }

    private func warnReadOnlySchema() {
        persistenceWarning = "Ces données ont été créées par une version plus récente de HoraTrack et ne seront pas écrasées. Réinstalle la version la plus récente pour continuer à pointer."
    }

    private func decodeLegacyUserDefaults() -> [WorkSession]? {
        guard let data = UserDefaults.standard.data(forKey: legacyKey) else { return nil }
        do {
            return try JSONDecoder().decode([WorkSession].self, from: data)
        } catch {
            NSLog("WorkStore legacy migration failed: %@", String(describing: error))
            return nil
        }
    }
}
