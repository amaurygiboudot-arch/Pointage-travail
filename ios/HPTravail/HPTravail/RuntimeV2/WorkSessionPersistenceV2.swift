import Foundation

struct WorkSession: Codable, Equatable, Identifiable {
    let id: UUID
    var entry: Date
    var exit: Date?
    var pauses: [PausePeriod]
    var employerId: String? = nil
    var placeLabel: String? = nil
}

struct PausePeriod: Codable, Equatable, Identifiable {
    let id: UUID
    var start: Date
    var end: Date?
    var paid: Bool?

    init(id: UUID, start: Date, end: Date?, paid: Bool? = nil) {
        self.id = id
        self.start = start
        self.end = end
        self.paid = paid
    }
}

enum WorkSessionsReadV2: Equatable {
    case missing
    case valid([WorkSession])
    case corrupt
}

enum WorkSessionStorageResolutionV2: Equatable {
    case missing
    case valid([WorkSession], migratedFromLegacy: Bool)
    case corrupt
}

/** Lecture fail-closed du journal de pointage iOS. */
enum WorkSessionPersistenceV2 {
    static func read(_ data: Data?) -> WorkSessionsReadV2 {
        guard let data else { return .missing }
        guard let sessions = try? JSONDecoder().decode([WorkSession].self, from: data),
              isStructurallyValid(sessions) else {
            return .corrupt
        }
        return .valid(sessions)
    }

    /**
     Lecture réservée à la clé V1 historique.

     Avant l'introduction du statut `paid`, le moteur iOS soustrayait systématiquement toute pause
     terminée du temps travaillé. Une pause V1 terminée sans champ `paid` a donc une sémantique
     historique connue : non rémunérée. Cette normalisation n'est jamais appliquée au stockage V2.
     Une pause V1 encore ouverte reste volontairement indéterminée afin d'exiger une qualification
     explicite avant sa fermeture.
     */
    static func readLegacy(_ data: Data?) -> WorkSessionsReadV2 {
        guard let data else { return .missing }
        guard var sessions = try? JSONDecoder().decode([WorkSession].self, from: data) else {
            return .corrupt
        }

        for sessionIndex in sessions.indices {
            for pauseIndex in sessions[sessionIndex].pauses.indices {
                if sessions[sessionIndex].pauses[pauseIndex].end != nil,
                   sessions[sessionIndex].pauses[pauseIndex].paid == nil {
                    sessions[sessionIndex].pauses[pauseIndex].paid = false
                }
            }
        }

        guard isStructurallyValid(sessions) else { return .corrupt }
        return .valid(sessions)
    }

    static func isStructurallyValid(_ sessions: [WorkSession]) -> Bool {
        guard Set(sessions.map(\.id)).count == sessions.count else { return false }
        guard sessions.filter({ $0.exit == nil }).count <= 1 else { return false }

        for session in sessions {
            if let exit = session.exit, exit <= session.entry { return false }
            if Set(session.pauses.map(\.id)).count != session.pauses.count { return false }
            if session.pauses.filter({ $0.end == nil }).count > 1 { return false }

            for pause in session.pauses {
                guard pause.start >= session.entry else { return false }
                if let end = pause.end {
                    guard end > pause.start else { return false }
                    guard pause.paid != nil else { return false }
                    if let exit = session.exit, end > exit { return false }
                } else if session.exit != nil {
                    return false
                }
            }
        }
        return true
    }
}

/**
 Propriétaire canonique des clés de persistance runtime iOS.

 La clé V1 n'est lue que pour une migration unique lorsque la clé V2 est réellement absente.
 Une clé V2 présente mais corrompue bloque la lecture : aucun fallback vers V1 n'est autorisé.
 */
enum WorkSessionStorageV2 {
    static let primaryKey = "hp_travail_sessions_v2"
    static let legacyKey = "hp_travail_sessions_v1"

    static func resolve(primaryData: Data?, legacyData: Data?) -> WorkSessionStorageResolutionV2 {
        if primaryData != nil {
            switch WorkSessionPersistenceV2.read(primaryData) {
            case .valid(let sessions):
                return .valid(sessions, migratedFromLegacy: false)
            case .missing:
                return .missing
            case .corrupt:
                return .corrupt
            }
        }

        switch WorkSessionPersistenceV2.readLegacy(legacyData) {
        case .missing:
            return .missing
        case .valid(let sessions):
            return .valid(sessions, migratedFromLegacy: true)
        case .corrupt:
            return .corrupt
        }
    }
}
