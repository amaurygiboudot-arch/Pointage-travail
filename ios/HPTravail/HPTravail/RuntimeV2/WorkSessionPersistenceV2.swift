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
    /// Fact provenance, independent of optional diagnostics and payroll classification.
    public var closureOrigin: String? = nil
    public var departureObservedAt: Date? = nil
    /// Absent for historical/manual sessions. GPS presence is never paid work by default.
    public var segments: [WorkSegmentV2]? = nil
    public var gpsEntryEventId: UUID? = nil
    public var gpsExitEventId: UUID? = nil
    public var gpsZoneId: UUID? = nil
    public var gpsConfigurationFingerprint: String? = nil
    public var gpsAccountScope: String? = nil
    public var gpsHomeExclusionConfigured: Bool? = nil

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

public enum WorkSegmentKindV2: String, Codable {
    case work, intervention, paidTravel, onCall, personalTravel, nonWork, toConfirm
}

public struct WorkSegmentV2: Codable, Equatable {
    public var start: Date
    public var end: Date?
    public var kind: WorkSegmentKindV2
    public var confirmed: Bool

    public init(start: Date, end: Date?, kind: WorkSegmentKindV2, confirmed: Bool) {
        self.start = start
        self.end = end
        self.kind = kind
        self.confirmed = confirmed
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
    enum IssueCode: String {
        case invalidJSON, missingField, invalidType, invalidValue, duplicateIdentity
        case multipleOpenSessions, multipleOpenPauses, invalidTime, unqualifiedPause, overlap, invalidSegment, invalidGPSProvenance
    }
    enum Field: String {
        case id, entry, exit, pauses, employerId, placeLabel, closureOrigin, departureObservedAt, segments
        case gpsEntryEventId, gpsExitEventId, gpsZoneId, gpsConfigurationFingerprint, gpsAccountScope, gpsHomeExclusionConfigured
        case start, end, paid, kind, confirmed
    }
    enum PathPart: Equatable {
        case field(Field), index(Int)
        var text: String {
            switch self {
            case .field(let field): return field.rawValue
            case .index(let index): return "[\(index + 1)]"
            }
        }
    }
    struct Issue: Equatable {
        let code: IssueCode
        let path: [PathPart]
        var otherPath: [PathPart]? = nil
    }
    struct Inspection {
        let result: WorkSessionsReadV2
        let issue: Issue?
    }

    static func read(_ data: Data?) -> WorkSessionsReadV2 {
        inspect(data).result
    }

    /// The same decoder and validator serve persistence and the ephemeral recovery consultation.
    /// Decoder descriptions and arbitrary JSON keys never leave this boundary.
    static func inspect(_ data: Data?, historicalUnpaidCompatibility: Bool = false) -> Inspection {
        guard let data else { return Inspection(result: .missing, issue: nil) }
        var sessions: [WorkSession]
        do { sessions = try JSONDecoder().decode([WorkSession].self, from: data) }
        catch {
            let code: IssueCode
            let path: [CodingKey]
            switch error {
            case DecodingError.keyNotFound(let key, let context): code = .missingField; path = context.codingPath + [key]
            case DecodingError.typeMismatch(_, let context): code = .invalidType; path = context.codingPath
            case DecodingError.valueNotFound(_, let context): code = .invalidValue; path = context.codingPath
            case DecodingError.dataCorrupted(let context):
                code = context.codingPath.isEmpty ? .invalidJSON : .invalidValue; path = context.codingPath
            default: code = .invalidJSON; path = []
            }
            let safePath: [PathPart] = path.compactMap {
                if let index = $0.intValue, index >= 0 { return .index(index) }
                return Field(rawValue: $0.stringValue).map { .field($0) }
            }
            return Inspection(result: .corrupt, issue: Issue(code: code, path: safePath))
        }
        if historicalUnpaidCompatibility {
            for sessionIndex in sessions.indices {
                for pauseIndex in sessions[sessionIndex].pauses.indices {
                    if sessions[sessionIndex].pauses[pauseIndex].end != nil,
                       sessions[sessionIndex].pauses[pauseIndex].paid == nil {
                        sessions[sessionIndex].pauses[pauseIndex].paid = false
                    }
                }
            }
        }
        if let issue = structuralIssue(sessions) { return Inspection(result: .corrupt, issue: issue) }
        return Inspection(result: .valid(sessions), issue: nil)
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
        inspect(data, historicalUnpaidCompatibility: true).result
    }

    static func isStructurallyValid(_ sessions: [WorkSession]) -> Bool {
        structuralIssue(sessions) == nil
    }

    static func structuralIssue(_ sessions: [WorkSession]) -> Issue? {
        var identities: [UUID: Int] = [:]
        for (index, session) in sessions.enumerated() {
            if let previous = identities.updateValue(index, forKey: session.id) {
                return Issue(code: .duplicateIdentity, path: [.index(previous), .field(.id)], otherPath: [.index(index), .field(.id)])
            }
        }
        let open = sessions.indices.filter { sessions[$0].exit == nil }
        if open.count > 1 {
            return Issue(code: .multipleOpenSessions, path: [.index(open[0]), .field(.exit)], otherPath: [.index(open[1]), .field(.exit)])
        }
        for (sessionIndex, session) in sessions.enumerated() {
            let row: [PathPart] = [.index(sessionIndex)]
            func issue(_ code: IssueCode, _ path: [PathPart], other: [PathPart]? = nil) -> Issue {
                Issue(code: code, path: row + path, otherPath: other.map { row + $0 })
            }
            guard session.entry.timeIntervalSinceReferenceDate.isFinite else { return issue(.invalidTime, [.field(.entry)]) }
            if let exit = session.exit, !exit.timeIntervalSinceReferenceDate.isFinite { return issue(.invalidTime, [.field(.exit)]) }
            if let exit = session.exit, exit <= session.entry { return issue(.invalidTime, [.field(.exit)]) }
            var pauseIDs: [UUID: Int] = [:]
            for (index, pause) in session.pauses.enumerated() {
                if let previous = pauseIDs.updateValue(index, forKey: pause.id) {
                    return issue(.duplicateIdentity, [.field(.pauses), .index(previous), .field(.id)], other: [.field(.pauses), .index(index), .field(.id)])
                }
            }
            let openPauses = session.pauses.indices.filter { session.pauses[$0].end == nil }
            if openPauses.count > 1 {
                return issue(.multipleOpenPauses, [.field(.pauses), .index(openPauses[0]), .field(.end)], other: [.field(.pauses), .index(openPauses[1]), .field(.end)])
            }
            if let segments = session.segments {
                guard !segments.isEmpty else { return issue(.invalidSegment, [.field(.segments)]) }
                var cursor = session.entry
                for (index, segment) in segments.enumerated() {
                    let path: [PathPart] = [.field(.segments), .index(index)]
                    guard segment.start.timeIntervalSinceReferenceDate.isFinite, segment.start == cursor else { return issue(.invalidSegment, path + [.field(.start)]) }
                    guard segment.kind != .toConfirm || !segment.confirmed else { return issue(.invalidSegment, path + [.field(.confirmed)]) }
                    if let end = segment.end {
                        guard end.timeIntervalSinceReferenceDate.isFinite, end > segment.start,
                              session.exit.map({ end <= $0 }) ?? true else { return issue(.invalidSegment, path + [.field(.end)]) }
                        cursor = end
                    } else {
                        guard session.exit == nil, index == segments.count - 1 else { return issue(.invalidSegment, path + [.field(.end)]) }
                    }
                }
                if let exit = session.exit, cursor != exit { return issue(.invalidSegment, [.field(.segments), .index(segments.count - 1), .field(.end)]) }
            }
            if let eventId = session.gpsEntryEventId {
                guard eventId == session.id else { return issue(.invalidGPSProvenance, [.field(.gpsEntryEventId)]) }
                guard session.gpsZoneId != nil else { return issue(.invalidGPSProvenance, [.field(.gpsZoneId)]) }
                guard session.gpsConfigurationFingerprint?.isEmpty == false else { return issue(.invalidGPSProvenance, [.field(.gpsConfigurationFingerprint)]) }
                guard session.gpsAccountScope?.isEmpty == false else { return issue(.invalidGPSProvenance, [.field(.gpsAccountScope)]) }
                guard session.employerId?.isEmpty == false else { return issue(.invalidGPSProvenance, [.field(.employerId)]) }
                guard session.segments != nil else { return issue(.invalidGPSProvenance, [.field(.segments)]) }
            }
            if session.gpsExitEventId != nil {
                guard session.exit != nil else { return issue(.invalidGPSProvenance, [.field(.exit)]) }
                guard session.departureObservedAt == session.exit else { return issue(.invalidGPSProvenance, [.field(.departureObservedAt)]) }
                guard session.closureOrigin == "AUTOMATIC" else { return issue(.invalidGPSProvenance, [.field(.closureOrigin)]) }
                guard session.segments != nil else { return issue(.invalidGPSProvenance, [.field(.segments)]) }
            }
            for (index, pause) in session.pauses.enumerated() {
                let path: [PathPart] = [.field(.pauses), .index(index)]
                guard pause.start.timeIntervalSinceReferenceDate.isFinite, pause.start >= session.entry else { return issue(.invalidTime, path + [.field(.start)]) }
                if let end = pause.end {
                    guard end.timeIntervalSinceReferenceDate.isFinite, end > pause.start else { return issue(.invalidTime, path + [.field(.end)]) }
                    guard pause.paid != nil else { return issue(.unqualifiedPause, path + [.field(.paid)]) }
                    if let exit = session.exit, end > exit { return issue(.invalidTime, path + [.field(.end)]) }
                } else if session.exit != nil { return issue(.invalidTime, path + [.field(.end)]) }
            }
            let orderedPauses = session.pauses.indices.sorted { session.pauses[$0].start < session.pauses[$1].start }
            if orderedPauses.count > 1 {
                for index in 1..<orderedPauses.count {
                    let previous = orderedPauses[index - 1], current = orderedPauses[index]
                    let previousEnd = session.pauses[previous].end ?? .distantFuture
                    if session.pauses[current].start < previousEnd {
                        return issue(.overlap, [.field(.pauses), .index(previous), .field(.end)], other: [.field(.pauses), .index(current), .field(.start)])
                    }
                }
            }
        }
        for firstIndex in sessions.indices {
            for secondIndex in sessions.indices where secondIndex > firstIndex {
                let first = sessions[firstIndex], second = sessions[secondIndex]
                let firstEnd = first.exit ?? .distantFuture, secondEnd = second.exit ?? .distantFuture
                if first.entry < secondEnd && second.entry < firstEnd {
                    return Issue(code: .overlap, path: [.index(firstIndex), .field(.entry)], otherPath: [.index(secondIndex), .field(.entry)])
                }
            }
        }
        return nil
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
        if var segments = updated[index].segments, let last = segments.indices.last {
            if segments[last].end == nil {
                guard date > segments[last].start else { return nil }
                segments[last].end = date
            }
            updated[index].segments = segments
        }
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
