import Foundation
import CryptoKit

/// Local opt-in evidence only. No identifiers, locations, labels or free-form errors enter the export.
enum DailyDiagnosticV2 {
    enum Kind: String, Codable {
        case recordingStarted, recordingStopped, journalLoadRequested
        case clockInRequested, clockOutRequested, pauseRequested, manualSessionRequested, gpsConfirmationDismissed
        case clockInCommitted, clockOutCommitted, pauseStarted, pauseEnded, manualSessionAdded, manualSessionExit, clockInRolledBack
        case gpsRegionCallback, gpsTransitionSaved, gpsTransitionUnchanged
        case gpsPersistenceFailed, gpsAuthorizationChanged, gpsFailure, gpsConfirmationRequested
        case clockInRejected, clockOutRejected
    }
    enum Origin: String, Codable { case manual = "MANUAL", widget = "WIDGET", automatic = "AUTOMATIC", gpsConfirmation = "GPS_CONFIRMATION", unknown = "UNKNOWN" }
    enum SourceIssue: String { case none, accountUnavailable, corrupt, writeFailed }
    enum Rejection: String, Codable {
        case storageUnavailable, sessionAlreadyOpen, employerUnresolved, invalidTimeOrOverlap, persistenceFailed
        case invalidTimeOrSession
    }
    struct Event: Codable, Equatable {
        var recordedAt: Date
        var occurredAt: Date?
        var kind: Kind
        var detail: Int?
        var zoneId: UUID? = nil
        var sessionId: UUID? = nil
        var origin: Origin? = nil
        var rejection: Rejection? = nil
    }
    struct Journal: Codable {
        var enabled = false
        var events: [Event] = []
        var truncated = false
    }
    static let maximumEvents = 2000
    static let retention: TimeInterval = 14 * 24 * 60 * 60
    private static let lock = NSRecursiveLock()
    private static var failures: [String: Int] = [:]
    private static var lastSweep: [String: Date] = [:]
    static var directory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("DailyDiagnosticV2", isDirectory: true)
    }
    private static func url(account: String, root: URL) -> URL {
        let digest = SHA256.hash(data: Data(account.utf8)).map { String(format: "%02x", $0) }.joined()
        return root.appendingPathComponent(digest + ".json")
    }
    static func read(account: String, root: URL = directory, now: Date = Date()) throws -> Journal {
        lock.lock(); defer { lock.unlock() }
        try purgeExpired(root: root, now: now)
        let path = url(account: account, root: root)
        guard FileManager.default.fileExists(atPath: path.path) else { return Journal() }
        var journal = try JSONDecoder().decode(Journal.self, from: Data(contentsOf: path))
        let count = journal.events.count
        journal.events = Array(journal.events.filter { $0.recordedAt >= now.addingTimeInterval(-retention) }.suffix(maximumEvents))
        if journal.events.count != count {
            journal.truncated = true
            try save(journal, account: account, root: root)
        }
        return journal
    }
    /// Sweep all account files on first use and at most hourly while the app is active.
    /// iOS cannot execute a purge while the app is stopped.
    private static func purgeExpired(root: URL, now: Date) throws {
        if let previous = lastSweep[root.path], now.timeIntervalSince(previous) < 3600 { return }
        guard FileManager.default.fileExists(atPath: root.path) else { return }
        let files = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
        for file in files where file.pathExtension == "json" {
            guard var journal = try? JSONDecoder().decode(Journal.self, from: Data(contentsOf: file)) else { continue }
            let count = journal.events.count
            journal.events = Array(journal.events.filter { $0.recordedAt >= now.addingTimeInterval(-retention) }.suffix(maximumEvents))
            if count != journal.events.count {
                journal.truncated = true
                try JSONEncoder().encode(journal).write(to: file, options: .atomic)
                #if os(iOS)
                try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: file.path)
                #endif
            }
        }
        lastSweep[root.path] = now
    }
    private static func save(_ journal: Journal, account: String, root: URL) throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var excluded = root
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try excluded.setResourceValues(values)
        let path = url(account: account, root: root)
        try JSONEncoder().encode(journal).write(to: path, options: .atomic)
        #if os(iOS)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: path.path)
        #endif
    }
    static func setEnabled(_ enabled: Bool, account: String, root: URL = directory, now: Date = Date()) throws {
        lock.lock(); defer { lock.unlock() }
        var journal = try read(account: account, root: root, now: now)
        guard journal.enabled != enabled else { return }
        journal.enabled = enabled
        journal.events.append(Event(recordedAt: now, occurredAt: now,
                                    kind: enabled ? .recordingStarted : .recordingStopped, detail: nil))
        trim(&journal)
        try save(journal, account: account, root: root)
    }
    static func append(_ kind: Kind, account: String?, occurredAt: Date? = nil,
                       detail: Int? = nil, zoneId: UUID? = nil, sessionId: UUID? = nil,
                       origin: Origin? = nil, rejection: Rejection? = nil, root: URL = directory, now: Date = Date()) throws {
        guard let account, !account.isEmpty else { return }
        lock.lock(); defer { lock.unlock() }
        do {
            var journal = try read(account: account, root: root, now: now)
            guard journal.enabled else { return }
            journal.events.append(Event(recordedAt: now, occurredAt: occurredAt, kind: kind, detail: detail, zoneId: zoneId, sessionId: sessionId, origin: origin, rejection: rejection))
            trim(&journal)
            try save(journal, account: account, root: root)
        } catch {
            failures[url(account: account, root: root).path, default: 0] += 1
            throw error
        }
    }
    private static func trim(_ journal: inout Journal) {
        if journal.events.count > maximumEvents {
            journal.events = Array(journal.events.suffix(maximumEvents))
            journal.truncated = true
        }
    }
    static func erase(account: String, root: URL = directory) throws {
        lock.lock(); defer { lock.unlock() }
        let path = url(account: account, root: root)
        if FileManager.default.fileExists(atPath: path.path) { try FileManager.default.removeItem(at: path) }
    }
    static func export(account: String, day: Date, calendar: Calendar = .current,
                       root: URL = directory, now: Date = Date(), sessions: [WorkSession]? = nil,
                       history: WorkSessionAccountStorageV2.DiagnosticHistory? = nil,
                       currentSourceIssue: SourceIssue? = nil) throws -> String {
        lock.lock(); defer { lock.unlock() }
        let journal = try read(account: account, root: root, now: now)
        let formatter = ISO8601DateFormatter()
        formatter.timeZone = calendar.timeZone
        let events = journal.events.filter {
            calendar.isDate($0.occurredAt ?? $0.recordedAt, inSameDayAs: day)
                || calendar.isDate($0.recordedAt, inSameDayAs: day)
        }
        let salt = UUID().uuidString
        func alias(_ id: UUID?) -> String {
            guard let id else { return "inconnu" }
            return SHA256.hash(data: Data((salt + id.uuidString).utf8)).prefix(6).map { String(format: "%02x", $0) }.joined()
        }
        var lines = ["HoraTrack — Diagnostic quotidien iOS v1",
                     "Version application : \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "inconnue") ; build : \(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "inconnu")",
                     "Jour sélectionné : \(formatter.string(from: calendar.startOfDay(for: day)))",
                     "Fuseau de sélection : \(calendar.timeZone.identifier)",
                     "Export créé : \(formatter.string(from: now))",
                     "Échecs d’écriture diagnostic observés depuis le lancement : \(failures[url(account: account, root: root).path, default: 0]) ; les traces peuvent être incomplètes.",
                     "État ACTUEL de l'enregistrement : \(journal.enabled ? "activé" : "désactivé")",
                     "Rétention : purge au prochain accès actif (tous comptes), 14 jours, 2000 événements maximum par compte. Historique tronqué : \(journal.truncated)",
                     "Ces traces ne prouvent pas la présence réelle. Absence de trace = inconnue, jamais absence de travail.",
                     "Pas de reconstruction avant activation. Une fermeture/privation GPS peut ne produire aucune trace.",
                     "Horodatage effectif = heure fournie au moteur ; horodatage enregistré = réception locale.",
                     "gpsRegionCallback : detail 1=entrée, 2=sortie. Heure effective inconnue : iOS fournit un callback sans heure du franchissement.",
                     "Callbacks d’inscriptions obsolètes/non vérifiées exclus. gpsConfirmationRequested : 1=acceptée, 0=refusée.",
                     "gpsTransitionSaved : detail = nombre de confirmations en attente ; gpsAuthorizationChanged : code CLAuthorizationStatus.",
                     "Les événements du jour et les écritures du jour portant sur une autre date sont inclus."]
        if events.isEmpty { lines.append("Aucune trace disponible pour ce jour : déroulement inconnu.") }
        for event in events {
            let effective = event.occurredAt.map { formatter.string(from: $0) } ?? "inconnue"
            lines.append("\(formatter.string(from: event.recordedAt)) | \(event.kind.rawValue) | heure effective=\(effective) | détail=\(event.detail.map(String.init) ?? "inconnu") | zone=\(alias(event.zoneId)) | session=\(alias(event.sessionId)) | origine=\(event.origin?.rawValue ?? "UNKNOWN") | refus=\(event.rejection?.rawValue ?? "inconnu")")
        }
        lines.append("Source du compte lisible : \(sessions != nil) ; historique : \(history?.rawValue ?? "unknown") ; motif source courante : \(currentSourceIssue?.rawValue ?? "unknown")")
        lines.append("Historique complet fiable : \(sessions != nil && history == .complete). Ce diagnostic ne certifie aucun total de paie.")
        lines.append("Historique : unassigned=non attribué ; corrupt=illisible ; conflict=incompatible ; recoveryIncomplete=récupération inachevée ; changed=source modifiée. Les faits du compte lisibles restent visibles séparément.")
        if let sessions {
            let current = sessions.last { $0.exit == nil }
            lines.append("Session courante à l'export (indépendante du jour choisi) : \(current.map { alias($0.id) } ?? "aucune dans le journal lisible")")
        } else {
            lines.append("Session courante à l'export : inconnue, source indisponible.")
        }
        lines.append("\nINSTANTANÉ du journal canonique lu à l'export (pas une trace historique) :")
        if let sessions, let interval = calendar.dateInterval(of: .day, for: day) {
            let selected = sessions.filter { $0.entry < interval.end && ($0.exit ?? now) >= interval.start }
            if selected.isEmpty { lines.append("Aucune session actuellement enregistrée sur ce jour ; cela ne prouve pas l'absence de travail.") }
            for session in selected {
                // Canonical facts survive diagnostic opt-out/erasure. Never export arbitrary
                // persisted origin strings, which are not an approved diagnostic vocabulary.
                let closureOrigin = session.closureOrigin.flatMap(Origin.init(rawValue:)) ?? .unknown
                let departureObserved = session.departureObservedAt.map { formatter.string(from: $0) } ?? "inconnue"
                lines.append("Session \(alias(session.id)) : entrée=\(formatter.string(from: session.entry)), sortie=\(session.exit.map { formatter.string(from: $0) } ?? "ouverte/inconnue"), origine=\(closureOrigin.rawValue), départ observé=\(departureObserved)")
                for pause in session.pauses {
                    lines.append("Pause : début=\(formatter.string(from: pause.start)), fin=\(pause.end.map { formatter.string(from: $0) } ?? "ouverte/inconnue"), payée=\(pause.paid.map { $0 ? "oui" : "non" } ?? "inconnue")")
                }
            }
        } else { lines.append("Journal canonique indisponible/non fiable ; horaires retenus inconnus.") }
        lines += ["", "À demander à l'utilisateur AVANT comparaison :",
                  "1. Heure réelle d'arrivée et de départ ?",
                  "2. Début/fin de chaque pause, payée ou non ?",
                  "3. Déplacements, changement de site, retour dans une zone ?",
                  "4. Actions manuelles, confirmations, téléphone éteint ou GPS coupé ?",
                  "Comparer récit, détections et décisions ; distinguer faits, hypothèses et inconnues. Ne pas corriger automatiquement."]
        return lines.joined(separator: "\n")
    }
}
