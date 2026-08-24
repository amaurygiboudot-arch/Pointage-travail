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
        _ = save()
    }

    func togglePause() {
        guard let index = sessions.lastIndex(where: { $0.exit == nil }) else { return }
        if let pauseIndex = sessions[index].pauses.lastIndex(where: { $0.end == nil }) {
            sessions[index].pauses[pauseIndex].end = Date()
        } else {
            sessions[index].pauses.append(PausePeriod(id: UUID(), start: Date(), end: nil))
        }
        _ = save()
    }

    func clockOut() {
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
        do {
            let urls = try storageURLs()
            guard let decoded = decodeFile(at: urls.backup) else { return false }
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
        do {
            let urls = try storageURLs()
            let envelope = WorkStoreEnvelope(schemaVersion: schemaVersion, sessions: sessions)
            let data = try JSONEncoder().encode(envelope)

            // Ne prépare une nouvelle copie précédente que si le primaire actuel
            // est lui-même décodable. Un primaire corrompu ne doit jamais écraser
            // une sauvegarde connue comme valide.
            var previousPrimaryData: Data?
            if !preserveBackup,
               fileManager.fileExists(atPath: urls.primary.path),
               decodeFile(at: urls.primary) != nil {
                previousPrimaryData = try Data(contentsOf: urls.primary)
            }

            // L'écriture atomique du nouveau primaire passe en premier. Si elle
            // échoue, l'ancien primaire et l'ancienne sauvegarde restent intacts.
            try data.write(to: urls.primary, options: [.atomic])

            // La copie précédente est elle aussi remplacée atomiquement. En cas
            // d'échec, le primaire neuf reste durable et l'ancienne sauvegarde
            // n'est pas détruite avant qu'une nouvelle copie soit prête.
            if let previousPrimaryData, !preserveBackup {
                try previousPrimaryData.write(to: urls.backup, options: [.atomic])
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
            if let primary = decodeFile(at: urls.primary) {
                sessions = primary
                return
            }

            if let backup = decodeFile(at: urls.backup) {
                sessions = backup
                persistenceWarning = "Les données précédentes ont été restaurées après une erreur de lecture."
                // Conserver le backup connu comme valide pendant la reconstruction
                // du primaire : le primaire corrompu ne doit jamais le remplacer.
                if !save(preserveBackup: true, clearWarningOnSuccess: false) {
                    persistenceWarning = "Les données de secours sont lisibles mais leur restauration durable a échoué."
                }
                return
            }

            if let legacy = decodeLegacyUserDefaults() {
                sessions = legacy
                // Le seul exemplaire legacy n'est supprimé qu'après confirmation
                // que le nouveau fichier Application Support a bien été écrit.
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
