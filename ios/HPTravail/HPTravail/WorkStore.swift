import Foundation

@MainActor
final class WorkStore: ObservableObject {
    @Published private(set) var sessions: [WorkSession] = []
    @Published private(set) var storageReliable = true
    private let key = "hp_travail_sessions_v1"

    init() { load() }

    var currentSession: WorkSession? {
        guard storageReliable else { return nil }
        return sessions.last(where: { $0.exit == nil })
    }
    var isWorking: Bool { currentSession != nil }
    var isPaused: Bool { currentSession?.pauses.last?.end == nil && currentSession?.pauses.last != nil }

    func clockIn() {
        guard storageReliable, !isWorking else { return }
        sessions.append(WorkSession(id: UUID(), entry: Date(), exit: nil, pauses: []))
        save()
    }

    func togglePause(paid: Bool? = nil) {
        guard storageReliable else { return }
        guard let index = sessions.lastIndex(where: { $0.exit == nil }) else { return }
        if let pauseIndex = sessions[index].pauses.lastIndex(where: { $0.end == nil }) {
            sessions[index].pauses[pauseIndex].end = Date()
        } else {
            guard let paid else { return }
            sessions[index].pauses.append(
                PausePeriod(id: UUID(), start: Date(), end: nil, paid: paid)
            )
        }
        save()
    }

    func clockOut() {
        guard storageReliable else { return }
        guard let index = sessions.lastIndex(where: { $0.exit == nil }) else { return }
        if let pauseIndex = sessions[index].pauses.lastIndex(where: { $0.end == nil }) {
            sessions[index].pauses[pauseIndex].end = Date()
        }
        sessions[index].exit = Date()
        save()
    }

    @discardableResult
    func addManualSession(
        entry: Date,
        exit: Date,
        employerId: String?,
        placeLabel: String?
    ) -> Bool {
        guard storageReliable,
              let updated = ManualSessionPolicyV2.appending(
                to: sessions,
                entry: entry,
                exit: exit,
                employerId: employerId,
                placeLabel: placeLabel
              ) else {
            return false
        }
        sessions = updated
        save()
        return storageReliable
    }

    func paidTimeAssessment(for session: WorkSession, until endDate: Date = Date()) -> PaidTimeAssessmentV2 {
        PaidTimePolicyV2.assess(
            sessionStart: session.entry,
            sessionEnd: session.exit,
            pauses: session.pauses.map {
                PaidPauseFactV2(start: $0.start, end: $0.end, paid: $0.paid)
            },
            until: endDate
        )
    }

    private func save() {
        guard storageReliable,
              let data = try? JSONEncoder().encode(sessions) else {
            storageReliable = false
            return
        }
        UserDefaults.standard.set(data, forKey: key)
    }

    private func load() {
        switch WorkSessionPersistenceV2.read(UserDefaults.standard.data(forKey: key)) {
        case .missing:
            sessions = []
            storageReliable = true
        case .valid(let decoded):
            sessions = decoded
            storageReliable = true
        case .corrupt:
            sessions = []
            storageReliable = false
        }
    }
}
