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

@MainActor
final class WorkStore: ObservableObject {
    @Published private(set) var sessions: [WorkSession] = []
    private let key = "hp_travail_sessions_v1"

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

    private func save() {
        guard let data = try? JSONEncoder().encode(sessions) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }

    private func load() {
        guard let data = UserDefaults.standard.data(forKey: key),
              let decoded = try? JSONDecoder().decode([WorkSession].self, from: data) else { return }
        sessions = decoded
    }
}
