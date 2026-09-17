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
                    if let exit = session.exit, end > exit { return false }
                } else if session.exit != nil {
                    return false
                }
            }
        }
        return true
    }
}
