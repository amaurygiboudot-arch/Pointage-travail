import Foundation

@MainActor
final class WorkStoreV2: ObservableObject {
    @Published private(set) var sessions: [WorkSession] = []
    @Published private(set) var storageReliable = true

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        load()
    }

    var currentSession: WorkSession? {
        guard storageReliable else { return nil }
        return sessions.last(where: { $0.exit == nil })
    }

    var isWorking: Bool { currentSession != nil }
    var isPaused: Bool { currentSession?.pauses.last?.end == nil && currentSession?.pauses.last != nil }
    var currentPauseNeedsQualification: Bool {
        guard storageReliable,
              let pause = currentSession?.pauses.last(where: { $0.end == nil }) else {
            return false
        }
        return pause.paid == nil
    }

    @discardableResult
    func clockIn(
        at entryDate: Date = Date(),
        employerId requestedEmployerId: String? = nil,
        placeLabel: String? = nil
    ) -> Bool {
        guard storageReliable, !isWorking else { return false }
        let companies = SalaryCompanyStoreV2.readConfirmed(defaults: defaults)
        switch ClockInEmployerResolverV2.resolve(
            requestedEmployerId: requestedEmployerId,
            companies: companies
        ) {
        case .unassigned:
            return appendClockIn(
                entryDate: entryDate,
                employerId: nil,
                placeLabel: placeLabel
            )
        case .employer(let employerId):
            return appendClockIn(
                entryDate: entryDate,
                employerId: employerId,
                placeLabel: placeLabel
            )
        case .rejected:
            return false
        }
    }

    private func appendClockIn(
        entryDate: Date,
        employerId: String?,
        placeLabel: String?
    ) -> Bool {
        guard entryDate <= Date().addingTimeInterval(300),
              sessions.allSatisfy({ session in
                  guard let exit = session.exit else { return false }
                  return exit <= entryDate
              }) else {
            return false
        }
        var updated = sessions
        updated.append(
            WorkSession(
                id: UUID(),
                entry: entryDate,
                exit: nil,
                pauses: [],
                employerId: employerId,
                placeLabel: placeLabel
            )
        )
        guard persistCanonical(updated) else {
            storageReliable = false
            return false
        }
        sessions = updated
        _ = WorkHistoryCoverageStoreV2.invalidateRange(
            start: entryDate,
            end: entryDate.addingTimeInterval(0.001),
            defaults: defaults
        )
        return true
    }

    func togglePause(paid: Bool? = nil) {
        guard storageReliable else { return }
        guard let index = sessions.lastIndex(where: { $0.exit == nil }) else { return }
        if let pauseIndex = sessions[index].pauses.lastIndex(where: { $0.end == nil }) {
            guard let resolvedPaid = sessions[index].pauses[pauseIndex].paid ?? paid else { return }
            sessions[index].pauses[pauseIndex].paid = resolvedPaid
            sessions[index].pauses[pauseIndex].end = Date()
        } else {
            guard let paid else { return }
            sessions[index].pauses.append(
                PausePeriod(id: UUID(), start: Date(), end: nil, paid: paid)
            )
        }
        save()
        if storageReliable {
            _ = WorkHistoryCoverageStoreV2.invalidateRange(
                start: sessions[index].entry,
                end: Date().addingTimeInterval(0.001),
                defaults: defaults
            )
        }
    }

    @discardableResult
    func clockOut(
        at exitDate: Date = Date(),
        expectedSessionId: UUID? = nil
    ) -> Bool {
        guard storageReliable else { return false }
        guard let index = sessions.lastIndex(where: { $0.exit == nil }) else { return false }
        guard expectedSessionId == nil || sessions[index].id == expectedSessionId,
              exitDate <= Date().addingTimeInterval(300),
              exitDate > sessions[index].entry,
              sessions[index].pauses.allSatisfy({ $0.paid != nil }) else {
            return false
        }
        var updated = sessions
        if let pauseIndex = updated[index].pauses.lastIndex(where: { $0.end == nil }) {
            guard exitDate > updated[index].pauses[pauseIndex].start else { return false }
            updated[index].pauses[pauseIndex].end = exitDate
        }
        updated[index].exit = exitDate
        guard persistCanonical(updated) else {
            storageReliable = false
            return false
        }
        sessions = updated
        _ = WorkHistoryCoverageStoreV2.invalidateRange(
            start: updated[index].entry,
            end: exitDate.addingTimeInterval(0.001),
            defaults: defaults
        )
        return true
    }

    @discardableResult
    func rollbackClockIn(expectedSessionId: UUID) -> Bool {
        guard storageReliable,
              let index = sessions.lastIndex(where: { $0.exit == nil }),
              sessions[index].id == expectedSessionId,
              sessions[index].pauses.isEmpty else {
            return false
        }
        let removed = sessions[index]
        var updated = sessions
        updated.remove(at: index)
        guard persistCanonical(updated) else {
            storageReliable = false
            return false
        }
        sessions = updated
        _ = WorkHistoryCoverageStoreV2.invalidateRange(
            start: removed.entry,
            end: Date().addingTimeInterval(0.001),
            defaults: defaults
        )
        return true
    }

    @discardableResult
    func addManualSession(
        entry: Date,
        exit: Date,
        employerId requestedEmployerId: String?,
        placeLabel: String?
    ) -> Bool {
        guard storageReliable else { return false }

        let companies = SalaryCompanyStoreV2.readConfirmed(defaults: defaults)
        let employerId: String?
        switch ClockInEmployerResolverV2.resolve(
            requestedEmployerId: requestedEmployerId,
            companies: companies
        ) {
        case .unassigned:
            employerId = nil
        case .employer(let confirmedEmployerId):
            employerId = confirmedEmployerId
        case .rejected:
            return false
        }

        guard let updated = ManualSessionPolicyV2.appending(
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
        if storageReliable {
            _ = WorkHistoryCoverageStoreV2.invalidateRange(
                start: entry,
                end: exit,
                defaults: defaults
            )
        }
        return storageReliable
    }

    @discardableResult
    func confirmHistoryCoverage(
        id: UUID = UUID(),
        sourceId: String,
        employerId: String,
        startEpochDay: Int64,
        endEpochDay: Int64,
        timeZoneId: String,
        confirmedAt: Date = Date(),
        note: String? = nil
    ) -> Bool {
        guard storageReliable else { return false }
        let attestation = WorkHistoryCoverageAttestationV2(
            id: id,
            sourceId: sourceId,
            employerId: employerId,
            startEpochDay: startEpochDay,
            endEpochDay: endEpochDay,
            confirmedAt: confirmedAt,
            timeZoneId: timeZoneId,
            note: note
        )
        return WorkHistoryCoverageStoreV2.saveConfirmed(
            attestation,
            defaults: defaults,
            now: Date()
        )
    }

    func historyCoverage(
        employerId: String,
        startEpochDay: Int64,
        endEpochDay: Int64,
        timeZoneId: String,
        now: Date = Date()
    ) -> WorkHistoryCoverageResultV2 {
        WorkHistoryCoverageStoreV2.coverage(
            employerId: employerId,
            startEpochDay: startEpochDay,
            endEpochDay: endEpochDay,
            timeZoneId: timeZoneId,
            defaults: defaults,
            now: now
        )
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
        guard storageReliable, persistCanonical(sessions) else {
            storageReliable = false
            return
        }
    }

    private func persistCanonical(_ decoded: [WorkSession]) -> Bool {
        guard let data = try? JSONEncoder().encode(decoded) else { return false }

        defaults.set(data, forKey: WorkSessionStorageV2.primaryKey)
        return WorkSessionPersistenceV2.read(
            defaults.data(forKey: WorkSessionStorageV2.primaryKey)
        ) == .valid(decoded)
    }

    private func load() {
        let primaryData = defaults.data(forKey: WorkSessionStorageV2.primaryKey)
        let legacyData = defaults.data(forKey: WorkSessionStorageV2.legacyKey)
        let paidRepairAlreadyHandled = defaults.bool(forKey: WorkSessionStorageV2.paidRepairMarkerKey)

        let resolution = WorkSessionStorageV2.resolve(
            primaryData: primaryData,
            legacyData: legacyData,
            allowTransitionalPrimaryRepair: !paidRepairAlreadyHandled
        )

        if !paidRepairAlreadyHandled {
            defaults.set(true, forKey: WorkSessionStorageV2.paidRepairMarkerKey)
        }

        switch resolution {
        case .missing:
            sessions = []
            storageReliable = true

        case .valid(let decoded, let origin):
            switch origin {
            case .primary:
                sessions = decoded
                storageReliable = true

            case .legacyMigration, .transitionalPrimaryRepair:
                guard persistCanonical(decoded) else {
                    sessions = []
                    storageReliable = false
                    return
                }

                if origin == .legacyMigration {
                    defaults.removeObject(forKey: WorkSessionStorageV2.legacyKey)
                }
                _ = WorkHistoryCoverageStoreV2.clearAll(defaults: defaults)

                sessions = decoded
                storageReliable = true
            }

        case .corrupt:
            sessions = []
            storageReliable = false
        }
    }
}
