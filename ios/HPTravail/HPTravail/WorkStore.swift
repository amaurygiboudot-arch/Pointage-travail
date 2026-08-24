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

    init() { load() }

    var currentSession: WorkSession? { sessions.last(where: { $0.exit == nil }) }
    var isWorking: Bool { currentSession != nil }
    var isPaused: Bool { currentSession?.pauses.last?.end == nil && currentSession?.pauses.last != nil }

    func clockIn() {
        guard !isWorking else { return }
        sessions.append(WorkSession(id: UUID(), entry: Date(), exit: nil, pauses: []))
        save()
    }

    func togglePause() {
        guard let index = sessions.lastIndex(where: { $0.exit == nil }) else { return }
        if let pauseIndex = sessions[index].pauses.lastIndex(where: { $0.end == nil }) {
            sessions[index].pauses[pauseIndex].end = Date()
        } else {
            sessions[index].pauses.append(PausePeriod(id: UUID(), start: Date(), end: nil))
        }
        save()
    }

    func clockOut() {
        guard let index = sessions.lastIndex(where: { $0.exit == nil }) else { return }
        if let pauseIndex = sessions[index].pauses.lastIndex(where: { $0.end == nil }) {
            sessions[index].pauses[pauseIndex].end = Date()
        }
        sessions[index].exit = Date()
        save()
    }

    func workedDuration(for session: WorkSession, until endDate: Date = Date()) -> TimeInterval {
        let end = session.exit ?? endDate
        let pause = session.pauses.reduce(0.0) { total, period in
            total + ((period.end ?? endDate).timeIntervalSince(period.start))
        }
        return max(0, end.timeIntervalSince(session.entry) - pause)
    }

    func restorePreviousBackup() -> Bool {
        guard let backupURL = try? storageURLs().backup,
              let decoded = decodeFile(at: backupURL) else { return false }
        sessions = decoded
        save()
        persistenceWarning = nil
        return true
    }

    private func save() {
        do {
            let urls = try storageURLs()
            let envelope = WorkStoreEnvelope(schemaVersion: schemaVersion, sessions: sessions)
            let data = try JSONEncoder().encode(envelope)

            if fileManager.fileExists(atPath: urls.primary.path) {
                if fileManager.fileExists(atPath: urls.backup.path) {
                    try fileManager.removeItem(at: urls.backup)
                }
                try fileManager.copyItem(at: urls.primary, to: urls.backup)
            }

            try data.write(to: urls.primary, options: [.atomic])
            persistenceWarning = nil
        } catch {
            persistenceWarning = "La sauvegarde locale n’a pas pu être enregistrée."
            NSLog("WorkStore persistence save failed: %@", String(describing: error))
        }
    }

    private func load() {
        do {
            let urls = try storageURLs()
            if let primary = decodeFile(at: urls.primary) {
                sessions = primary
                return
            }
            if let backup = decodeFile(at: urls.backup) {
                sessions = backup
                persistenceWarning = "Les données précédentes ont été restaurées après une erreur de lecture."
                save()
                return
            }
            if let legacy = decodeLegacyUserDefaults() {
                sessions = legacy
                save()
                UserDefaults.standard.removeObject(forKey: legacyKey)
                return
            }
        } catch {
            NSLog("WorkStore persistence load failed: %@", String(describing: error))
        }
        persistenceWarning = fileManager.fileExists(atPath: (try? storageURLs().primary.path) ?? "")
            ? "Les données locales sont illisibles. Une restauration peut être nécessaire."
            : nil
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

    private func decodeFile(at url: URL) -> [WorkSession]? {
        guard fileManager.fileExists(atPath: url.path) else { return nil }
        do {
            let data = try Data(contentsOf: url)
            let envelope = try JSONDecoder().decode(WorkStoreEnvelope.self, from: data)
            guard envelope.schemaVersion == schemaVersion else {
                NSLog("WorkStore unsupported schema version: %d", envelope.schemaVersion)
                return nil
            }
            return envelope.sessions
        } catch {
            NSLog("WorkStore decode failed for %@: %@", url.lastPathComponent, String(describing: error))
            return nil
        }
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
