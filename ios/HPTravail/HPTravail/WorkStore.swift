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
        guard end > session.entry else { return 0 }

        let intervals = session.pauses.compactMap { period -> (Date, Date)? in
            let rawEnd = period.end ?? endDate
            guard rawEnd > period.start else { return nil }
            let start = max(period.start, session.entry)
            let clippedEnd = min(rawEnd, end)
            guard clippedEnd > start else { return nil }
            return (start, clippedEnd)
        }.sorted { $0.0 < $1.0 }

        var mergedPause: TimeInterval = 0
        if let first = intervals.first {
            var currentStart = first.0
            var currentEnd = first.1

            for interval in intervals.dropFirst() {
                if interval.0 <= currentEnd {
                    currentEnd = max(currentEnd, interval.1)
                } else {
                    mergedPause += currentEnd.timeIntervalSince(currentStart)
                    currentStart = interval.0
                    currentEnd = interval.1
                }
            }
            mergedPause += currentEnd.timeIntervalSince(currentStart)
        }

        let rawDuration = end.timeIntervalSince(session.entry)
        return max(0, rawDuration - min(mergedPause, rawDuration))
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
