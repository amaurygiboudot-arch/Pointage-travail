import Foundation

/// Contrat factuel du journal de pointage RuntimeV2.
///
/// Le type est public uniquement pour permettre aux couches consommatrices (dont Salaire V2)
/// de lire les faits enregistrés. RuntimeV2 ne dépend d'aucune règle ou type SalaireV2.
public struct WorkSession: Codable, Equatable, Identifiable {
    public let id: UUID
    public var entry: Date
    public var exit: Date?
    public var pauses: [PausePeriod]
    public var employerId: String? = nil
    public var placeLabel: String? = nil

    public init(
        id: UUID,
        entry: Date,
        exit: Date?,
        pauses: [PausePeriod],
        employerId: String? = nil,
        placeLabel: String? = nil
    ) {
        self.id = id
        self.entry = entry
        self.exit = exit
        self.pauses = pauses
        self.employerId = employerId
        self.placeLabel = placeLabel
    }
}

public struct PausePeriod: Codable, Equatable, Identifiable {
    public let id: UUID
    public var start: Date
    public var end: Date?
    public var paid: Bool?

    public init(id: UUID, start: Date, end: Date?, paid: Bool? = nil) {
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

enum WorkSessionStorageOriginV2: Equatable {
    case primary
    case legacyMigration
    case transitionalPrimaryRepair
}

enum WorkSessionStorageResolutionV2: Equatable {
    case missing
    case valid([WorkSession], origin: WorkSessionStorageOriginV2)
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

    /** Un candidat invalide ne doit jamais remplacer le dernier journal valide. */
    static func write(_ sessions: [WorkSession], defaults: UserDefaults) -> Bool {
        guard isStructurallyValid(sessions),
              let data = try? JSONEncoder().encode(sessions),
              read(data) == .valid(sessions) else {
            return false
        }
        defaults.set(data, forKey: WorkSessionStorageV2.primaryKey)
        return read(defaults.data(forKey: WorkSessionStorageV2.primaryKey)) == .valid(sessions)
    }

    /**
     Lecture réservée à la clé V1 historique.

     Avant l'introduction du statut `paid`, le moteur iOS soustrayait systématiquement toute pause
     terminée du temps travaillé. Une pause V1 terminée sans champ `paid` a donc une sémantique
     historique connue : non rémunérée. Cette normalisation n'est jamais utilisée comme lecture V2
     normale. Une pause encore ouverte reste volontairement indéterminée afin d'exiger une
     qualification explicite avant sa fermeture.
     */
    static func readLegacy(_ data: Data?) -> WorkSessionsReadV2 {
        readHistoricalUnpaidCompatibility(data)
    }

    /**
     Réparation de transition réservée aux installations qui avaient déjà copié la V1 vers la
     première clé V2 avant que `paid` devienne obligatoire pour les pauses terminées.
     L'appelant doit la protéger par un marqueur one-shot persistant.
     */
    static func readTransitionalPrimary(_ data: Data?) -> WorkSessionsReadV2 {
        readHistoricalUnpaidCompatibility(data)
    }

    private static func readHistoricalUnpaidCompatibility(_ data: Data?) -> WorkSessionsReadV2 {
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
            guard session.entry.timeIntervalSinceReferenceDate.isFinite else { return false }
            if let exit = session.exit, !exit.timeIntervalSinceReferenceDate.isFinite { return false }
            if let exit = session.exit, exit <= session.entry { return false }
            if Set(session.pauses.map(\.id)).count != session.pauses.count { return false }
            if session.pauses.filter({ $0.end == nil }).count > 1 { return false }

            for pause in session.pauses {
                guard pause.start.timeIntervalSinceReferenceDate.isFinite,
                      pause.start >= session.entry else { return false }
                if let end = pause.end {
                    guard end.timeIntervalSinceReferenceDate.isFinite,
                          end > pause.start else { return false }
                    guard pause.paid != nil else { return false }
                    if let exit = session.exit, end > exit { return false }
                } else if session.exit != nil {
                    return false
                }
            }

            let orderedPauses = session.pauses.sorted { $0.start < $1.start }
            if orderedPauses.count > 1 {
                for index in 1..<orderedPauses.count {
                    let previousEnd = orderedPauses[index - 1].end ?? .distantFuture
                    if orderedPauses[index].start < previousEnd { return false }
                }
            }
        }
        for firstIndex in sessions.indices {
            for secondIndex in sessions.indices where secondIndex > firstIndex {
                let first = sessions[firstIndex]
                let second = sessions[secondIndex]
                let firstEnd = first.exit ?? .distantFuture
                let secondEnd = second.exit ?? .distantFuture
                if first.entry < secondEnd && second.entry < firstEnd {
                    return false
                }
            }
        }
        return true
    }
}

/** Prépare les mutations sans toucher au journal en mémoire ni au stockage. */
enum WorkSessionMutationV2 {
    static func togglingPause(
        in sessions: [WorkSession],
        at date: Date,
        paid: Bool?
    ) -> [WorkSession]? {
        guard WorkSessionPersistenceV2.isStructurallyValid(sessions),
              date.timeIntervalSinceReferenceDate.isFinite,
              let index = sessions.lastIndex(where: { $0.exit == nil }),
              date >= sessions[index].entry,
              sessions[index].pauses.allSatisfy({ pause in
                  pause.end.map { $0 <= date } ?? true
              }) else {
            return nil
        }
        var updated = sessions
        if let pauseIndex = updated[index].pauses.lastIndex(where: { $0.end == nil }) {
            guard date > updated[index].pauses[pauseIndex].start,
                  let resolvedPaid = updated[index].pauses[pauseIndex].paid ?? paid else {
                return nil
            }
            updated[index].pauses[pauseIndex].paid = resolvedPaid
            updated[index].pauses[pauseIndex].end = date
        } else {
            guard let paid else { return nil }
            updated[index].pauses.append(
                PausePeriod(id: UUID(), start: date, end: nil, paid: paid)
            )
        }
        return WorkSessionPersistenceV2.isStructurallyValid(updated) ? updated : nil
    }

    static func closingSession(
        in sessions: [WorkSession],
        at date: Date,
        expectedSessionId: UUID?
    ) -> [WorkSession]? {
        guard WorkSessionPersistenceV2.isStructurallyValid(sessions),
              date.timeIntervalSinceReferenceDate.isFinite,
              let index = sessions.lastIndex(where: { $0.exit == nil }),
              expectedSessionId == nil || sessions[index].id == expectedSessionId,
              date > sessions[index].entry,
              sessions[index].pauses.allSatisfy({ pause in
                  pause.paid != nil && (pause.end.map { $0 <= date } ?? (date > pause.start))
              }) else {
            return nil
        }
        var updated = sessions
        if let pauseIndex = updated[index].pauses.lastIndex(where: { $0.end == nil }) {
            updated[index].pauses[pauseIndex].end = date
        }
        updated[index].exit = date
        return WorkSessionPersistenceV2.isStructurallyValid(updated) ? updated : nil
    }
}

/**
 Propriétaire canonique des clés de persistance runtime iOS.

 La clé V1 n'est lue que pour une migration unique lorsque la clé V2 est réellement absente.
 Une clé V2 présente mais corrompue bloque la lecture, sauf pendant l'unique réparation de transition
 explicitement autorisée par l'appelant. Après pose du marqueur, aucun fallback V1/V2 n'est permis.
 */
enum WorkSessionStorageV2 {
    static let primaryKey = "hp_travail_sessions_v2"
    static let legacyKey = "hp_travail_sessions_v1"
    static let paidRepairMarkerKey = "hp_travail_sessions_v2_paid_repair_v1_done"

    /// Complete a migration before consuming its one-shot repair eligibility.
    /// On failed persistence, retain both the source and marker so the next load can retry.
    static func load(
        defaults: UserDefaults,
        persist: ([WorkSession]) -> Bool
    ) -> WorkSessionStorageResolutionV2 {
        let handled = defaults.bool(forKey: paidRepairMarkerKey)
        let resolution = resolve(
            primaryData: defaults.data(forKey: primaryKey),
            legacyData: defaults.data(forKey: legacyKey),
            allowTransitionalPrimaryRepair: !handled
        )
        if case .valid(let sessions, let origin) = resolution,
           origin != .primary {
            guard persist(sessions) else { return .corrupt }
            if origin == .legacyMigration {
                defaults.removeObject(forKey: legacyKey)
            }
        }
        if !handled { defaults.set(true, forKey: paidRepairMarkerKey) }
        return resolution
    }

    static func resolve(
        primaryData: Data?,
        legacyData: Data?,
        allowTransitionalPrimaryRepair: Bool = false
    ) -> WorkSessionStorageResolutionV2 {
        if primaryData != nil {
            switch WorkSessionPersistenceV2.read(primaryData) {
            case .valid(let sessions):
                return .valid(sessions, origin: .primary)
            case .missing:
                return .missing
            case .corrupt:
                guard allowTransitionalPrimaryRepair else { return .corrupt }
                switch WorkSessionPersistenceV2.readTransitionalPrimary(primaryData) {
                case .valid(let sessions):
                    return .valid(sessions, origin: .transitionalPrimaryRepair)
                case .missing, .corrupt:
                    return .corrupt
                }
            }
        }

        switch WorkSessionPersistenceV2.readLegacy(legacyData) {
        case .missing:
            return .missing
        case .valid(let sessions):
            return .valid(sessions, origin: .legacyMigration)
        case .corrupt:
            return .corrupt
        }
    }
}
