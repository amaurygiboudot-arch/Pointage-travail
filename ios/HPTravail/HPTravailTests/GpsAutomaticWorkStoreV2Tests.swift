#if !SWIFT_PACKAGE
import Foundation
import XCTest

/// Exercises actual scoped persistence and the ObservableObject without a ContentView.
@MainActor
final class GpsAutomaticWorkStoreV2Tests: XCTestCase {
    private let account = "uid:gps-workstore"
    private let employer = "gps-company"
    private let entryAt = Date().addingTimeInterval(-3 * 3_600)

    private func withFixture(_ body: (UserDefaults, URL, GpsZoneV2) throws -> Void) throws {
        let suite = "gps-workstore-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        let diagnosticRoot = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer {
            defaults.removePersistentDomain(forName: suite)
            try? FileManager.default.removeItem(at: diagnosticRoot)
        }
        XCTAssertTrue(SalaryCompanyStoreV2.createOrUpdate(
            .init(id: employer, name: "Entreprise test", siret: ""), defaults: defaults
        ))
        let zone = GpsZoneV2(id: UUID(), label: "Site test", latitude: 48, longitude: 2,
                            radius: 150, employerId: employer, kind: .worksite)
        defaults.set(true, forKey: GpsZoneConfigurationV2.enabledKey)
        defaults.set(try JSONEncoder().encode([zone]), forKey: GpsZoneConfigurationV2.zonesKey)
        try body(defaults, diagnosticRoot, zone)
    }

    private func arrivalState(_ zone: GpsZoneV2, includeDeparture: Bool) -> GpsPersistedStateV2 {
        let arrival = GpsPendingEventV2(id: UUID(), kind: .arrival, zoneIds: [zone.id],
            occurredAt: entryAt, siteFactVersion: 1)
        var events = [arrival]
        if includeDeparture {
            let leftAt = entryAt.addingTimeInterval(2 * 3_600)
            events.append(GpsPendingEventV2(id: UUID(), kind: .departure, zoneIds: [zone.id],
                occurredAt: leftAt,
                exitObservations: [.init(zoneId: zone.id, occurredAt: leftAt, continuingZoneIds: [])],
                departureEvidenceVersion: 1, siteFactVersion: 1))
        }
        return .init(fingerprint: GpsZoneConfigurationV2.fingerprint(enabled: true, zones: [zone])!,
            activeZoneIds: includeDeparture ? [] : [zone.id], pendingExitZoneIds: [], pendingEvents: events,
            confirmedSessionId: nil, eventQueueOverflowed: false, accountScope: account)
    }

    private func save(_ state: GpsPersistedStateV2, _ defaults: UserDefaults) {
        XCTAssertTrue(GpsStateStoreV2.write(state, defaults: defaults,
            storageKey: GpsStateStoreV2.scopedKey(account: account)))
    }

    private func readState(_ defaults: UserDefaults) throws -> GpsPersistedStateV2 {
        guard case .valid(let state) = GpsStateStoreV2.read(
            defaults.data(forKey: GpsStateStoreV2.scopedKey(account: account))) else {
            XCTFail("Expected readable scoped GPS state")
            throw NSError(domain: "GpsAutomaticWorkStoreV2Tests", code: 1)
        }
        return state
    }

    private func historyState(_ zone: GpsZoneV2) -> GpsPersistedStateV2 {
        var state = arrivalState(zone, includeDeparture: false)
        state.pendingEvents = []
        let nextZone = UUID()
        let continuity = UUID().uuidString
        state.observations = [(zone.id, true, 0.0), (zone.id, false, 600.0), (nextZone, true, 900.0)].map {
            GpsSiteObservationV2(id: UUID(), account: account, fingerprint: state.fingerprint,
                zoneId: $0.0, zoneLabel: $0.0 == zone.id ? zone.label : "Site suivant", employerId: employer,
                entered: $0.1, observedAt: entryAt.addingTimeInterval($0.2),
                homeExclusionConfigured: true, continuityId: continuity)
        }
        return state
    }

    func testHistorySnapshotRefreshesArrivalArchiveAndHomeWithoutMutatingPreviousSnapshot() throws {
        try withFixture { defaults, diagnosticRoot, zone in
            let complete = historyState(zone)
            var departureOnly = complete
            departureOnly.observations = Array(complete.observations!.prefix(2))
            save(departureOnly, defaults)
            let store = WorkStoreV2(defaults: defaults, diagnosticRoot: diagnosticRoot, accountProvider: { self.account })
            let beforeArrival = store.gpsHistorySnapshot
            XCTAssertEqual(beforeArrival.observations.count, 2)
            XCTAssertTrue(beforeArrival.journeys.isEmpty)

            save(complete, defaults)
            let arrived = store.gpsHistorySnapshot
            XCTAssertEqual(arrived.observations, complete.observations)
            XCTAssertEqual(arrived.journeys.map(\.observedDuration), [300])
            XCTAssertTrue(arrived.journeyHistoryReliable)

            var archived = complete
            archived.pendingEvents = [.init(id: UUID(), kind: .departure, zoneIds: [zone.id], occurredAt: entryAt)]
            XCTAssertTrue(GpsObservationArchiveStoreV2.preserve(archived, account: account, defaults: defaults))
            var cleared = complete
            cleared.observations = []
            save(cleared, defaults)
            let afterArchive = store.gpsHistorySnapshot
            XCTAssertEqual(afterArchive.observations, arrived.observations)
            XCTAssertEqual(afterArchive.journeys, arrived.journeys)
            XCTAssertEqual(afterArchive.historicalObservations.map(\.event), archived.pendingEvents)

            XCTAssertTrue(GpsHomeObservationStoreV2.append(account: account, fingerprint: complete.fingerprint,
                zoneId: UUID(), entered: true, at: entryAt.addingTimeInterval(700), defaults: defaults))
            let afterHome = store.gpsHistorySnapshot
            XCTAssertTrue(afterHome.journeys.isEmpty)
            XCTAssertTrue(afterHome.journeyHistoryReliable)
            XCTAssertEqual(afterHome.observations, arrived.observations)
            XCTAssertEqual(beforeArrival.observations.count, 2)
            XCTAssertEqual(arrived.journeys.count, 1)
            XCTAssertTrue(arrived.historicalObservations.isEmpty)
        }
    }

    func testHistorySnapshotDistinguishesMissingMalformedAndWrongTypeForEachJournal() throws {
        try withFixture { defaults, diagnosticRoot, _ in
            let store = WorkStoreV2(defaults: defaults, diagnosticRoot: diagnosticRoot, accountProvider: { self.account })
            let keys = [GpsStateStoreV2.scopedKey(account: account),
                        GpsObservationArchiveStoreV2.key(account: account),
                        GpsHomeObservationStoreV2.key(account: account)]
            XCTAssertTrue(store.gpsHistorySnapshot.journeyHistoryReliable)
            for key in keys {
                for unreadable: Any in ["wrong-storage-type", Data("malformed-json".utf8)] {
                    defaults.set(unreadable, forKey: key)
                    let snapshot = store.gpsHistorySnapshot
                    XCTAssertFalse(snapshot.journeyHistoryReliable, key)
                    XCTAssertEqual(snapshot.observationHistoryReliable,
                                   key == GpsHomeObservationStoreV2.key(account: account), key)
                    XCTAssertTrue(snapshot.journeys.isEmpty)
                    XCTAssertNotNil(defaults.object(forKey: key))
                    defaults.removeObject(forKey: key)
                    XCTAssertTrue(store.gpsHistorySnapshot.journeyHistoryReliable, key)
                }
            }
        }
    }

    func testHistorySnapshotRejectsNewCorruptionAndRestoresOnlyAfterReadableSourcesReturn() throws {
        try withFixture { defaults, diagnosticRoot, zone in
            save(historyState(zone), defaults)
            let store = WorkStoreV2(defaults: defaults, diagnosticRoot: diagnosticRoot, accountProvider: { self.account })
            XCTAssertEqual(store.gpsHistorySnapshot.journeys.count, 1)
            let key = GpsStateStoreV2.scopedKey(account: account)
            let original = try XCTUnwrap(defaults.data(forKey: key))
            defaults.set(Data("broken".utf8), forKey: key)
            XCTAssertFalse(store.gpsHistorySnapshot.observationHistoryReliable)
            XCTAssertTrue(store.gpsHistorySnapshot.observations.isEmpty)
            XCTAssertTrue(store.gpsHistorySnapshot.journeys.isEmpty)
            defaults.set(original, forKey: key)
            XCTAssertEqual(store.gpsHistorySnapshot.journeys.count, 1)

            let sessionsKey = try XCTUnwrap(WorkSessionAccountStorageV2.key(account: account))
            defaults.set(Data("broken-work-journal".utf8), forKey: sessionsKey)
            store.reloadAccount()
            XCTAssertFalse(store.gpsHistorySnapshot.storageReliable)
            XCTAssertTrue(store.gpsHistorySnapshot.journeys.isEmpty)
            XCTAssertTrue(store.gpsHistorySnapshot.observationHistoryReliable)
            defaults.removeObject(forKey: sessionsKey)
            store.reloadAccount()
            XCTAssertTrue(store.gpsHistorySnapshot.storageReliable)
            XCTAssertEqual(store.gpsHistorySnapshot.journeys.count, 1)
        }
    }

    func testHistorySnapshotCannotExposeOrCopyPreviousAccountWhileAuthenticationChanges() throws {
        try withFixture { defaults, diagnosticRoot, zone in
            save(historyState(zone), defaults)
            var activeAccount: String? = account
            let store = WorkStoreV2(defaults: defaults, diagnosticRoot: diagnosticRoot, accountProvider: { activeAccount })
            XCTAssertEqual(store.gpsHistorySnapshot.journeys.count, 1)
            let original = defaults.data(forKey: GpsStateStoreV2.scopedKey(account: account))
            let other = "uid:gps-history-other"
            activeAccount = other
            XCTAssertFalse(store.gpsHistorySnapshot.storageReliable)
            XCTAssertTrue(store.gpsHistorySnapshot.observations.isEmpty)
            store.reloadAccount()
            XCTAssertTrue(store.gpsHistorySnapshot.journeyHistoryReliable)
            XCTAssertTrue(store.gpsHistorySnapshot.observations.isEmpty)
            XCTAssertTrue(store.gpsHistorySnapshot.historicalObservations.isEmpty)
            XCTAssertNil(defaults.object(forKey: GpsStateStoreV2.scopedKey(account: other)))
            XCTAssertNil(defaults.object(forKey: GpsObservationArchiveStoreV2.key(account: other)))
            XCTAssertNil(defaults.object(forKey: GpsHomeObservationStoreV2.key(account: other)))
            activeAccount = nil
            XCTAssertFalse(store.gpsHistorySnapshot.journeyHistoryReliable)
            activeAccount = account
            store.reloadAccount()
            XCTAssertEqual(store.gpsHistorySnapshot.journeys.count, 1)
            XCTAssertEqual(defaults.data(forKey: GpsStateStoreV2.scopedKey(account: account)), original)
        }
    }

    func testDrainsPersistedArrivalAndDepartureThenReloadsWithoutDuplicating() throws {
        try withFixture { defaults, diagnosticRoot, zone in
            let original = arrivalState(zone, includeDeparture: true)
            save(original, defaults)
            let store = WorkStoreV2(defaults: defaults, diagnosticRoot: diagnosticRoot, accountProvider: { self.account })
            XCTAssertTrue(store.processAutomaticGpsPointage())
            XCTAssertEqual(store.sessions.count, 1)
            let session = try XCTUnwrap(store.sessions.first)
            XCTAssertEqual(session.entry, entryAt)
            XCTAssertEqual(session.exit, entryAt.addingTimeInterval(2 * 3_600))
            XCTAssertEqual(session.gpsEntryEventId, original.pendingEvents[0].id)
            XCTAssertEqual(session.gpsExitEventId, original.pendingEvents[1].id)
            XCTAssertEqual(session.closureOrigin, DailyDiagnosticV2.Origin.automatic.rawValue)
            XCTAssertFalse(store.paidTimeAssessment(for: session).reliable)
            XCTAssertTrue(try readState(defaults).pendingEvents.isEmpty)

            let restarted = WorkStoreV2(defaults: defaults, diagnosticRoot: diagnosticRoot, accountProvider: { self.account })
            XCTAssertTrue(restarted.processAutomaticGpsPointage())
            XCTAssertEqual(restarted.sessions, store.sessions)
            XCTAssertTrue(restarted.storageReliable)
        }
    }

    func testCommittedArrivalSurvivesMissingReceiptAndIsAcknowledgedAfterRestart() throws {
        try withFixture { defaults, diagnosticRoot, zone in
            let pending = arrivalState(zone, includeDeparture: false)
            save(pending, defaults)
            let committed = try XCTUnwrap(GpsAutomaticPointageV2.plan(state: pending, zones: [zone],
                account: account, sessions: [], confirmedEmployerIds: [employer], now: Date()))
            XCTAssertTrue(WorkSessionAccountStorageV2.write(committed.sessions, account: account, defaults: defaults))
            // Simulate process death after journal commit but before writing the GPS receipt.
            let restarted = WorkStoreV2(defaults: defaults, diagnosticRoot: diagnosticRoot, accountProvider: { self.account })
            XCTAssertTrue(restarted.processAutomaticGpsPointage())
            XCTAssertEqual(restarted.sessions, committed.sessions)
            XCTAssertEqual(try readState(defaults), committed.state)
            XCTAssertTrue(restarted.processAutomaticGpsPointage())
            XCTAssertEqual(restarted.sessions.count, 1)
        }
    }

    func testWorkAndNonWorkQualificationPreserveTimesProvenanceAndPauses() throws {
        for kind: WorkSegmentKindV2 in [.work, .nonWork] {
            try withFixture { defaults, diagnosticRoot, zone in
                let arrival = arrivalState(zone, includeDeparture: false)
                save(arrival, defaults)
                let store = WorkStoreV2(defaults: defaults, diagnosticRoot: diagnosticRoot, accountProvider: { self.account })
                XCTAssertTrue(store.processAutomaticGpsPointage())
                XCTAssertTrue(store.togglePause(paid: false, at: entryAt.addingTimeInterval(30 * 60)))
                XCTAssertTrue(store.togglePause(at: entryAt.addingTimeInterval(45 * 60)))
                let open = try XCTUnwrap(store.currentSession)
                let leftAt = entryAt.addingTimeInterval(2 * 3_600)
                var outside = try readState(defaults)
                outside.activeZoneIds = []
                outside.pendingEvents = [.init(id: UUID(), kind: .departure, zoneIds: [zone.id], occurredAt: leftAt,
                    expectedSessionId: open.id,
                    exitObservations: [.init(zoneId: zone.id, occurredAt: leftAt, continuingZoneIds: [])],
                    verifiedDepartureZoneId: zone.id, departureEvidenceVersion: 1,
                    expectedSessionEntry: open.entry, siteFactVersion: 1)]
                save(outside, defaults)
                XCTAssertTrue(store.processAutomaticGpsPointage())
                let before = try XCTUnwrap(store.sessions.first)
                XCTAssertFalse(store.paidTimeAssessment(for: before).reliable)
                XCTAssertTrue(store.qualifyGpsSession(before, account: account, as: kind))
                var expected = before
                expected.segments = before.segments?.map {
                    .init(start: $0.start, end: $0.end, kind: kind, confirmed: true)
                }
                XCTAssertEqual(store.sessions, [expected])
                XCTAssertEqual(store.sessions[0].pauses, open.pauses)
                let assessment = store.paidTimeAssessment(for: store.sessions[0])
                XCTAssertTrue(assessment.reliable)
                XCTAssertEqual(assessment.paidDuration, kind == .work ? 105 * 60 : 0)
                store.reloadAccount()
                XCTAssertEqual(store.sessions, [expected])
                // A stale screen cannot classify the same old snapshot a second time.
                XCTAssertFalse(store.qualifyGpsSession(before, account: account, as: .nonWork))
                XCTAssertEqual(store.sessions, [expected])
            }
        }
    }

    func testChangedAccountCannotConsumeOrQualifyPreviousAccountFacts() throws {
        try withFixture { defaults, diagnosticRoot, zone in
            var activeAccount: String? = account
            save(arrivalState(zone, includeDeparture: true), defaults)
            let store = WorkStoreV2(defaults: defaults, diagnosticRoot: diagnosticRoot, accountProvider: { activeAccount })
            XCTAssertTrue(store.processAutomaticGpsPointage())
            let original = try XCTUnwrap(store.sessions.first)
            let canonical = WorkSessionAccountStorageV2.read(account: account, defaults: defaults)
            activeAccount = "uid:someone-else"
            XCTAssertFalse(store.qualifyGpsSession(original, account: account, as: .work))
            XCTAssertFalse(store.processAutomaticGpsPointage())
            XCTAssertTrue(store.sessions.isEmpty)
            XCTAssertEqual(WorkSessionAccountStorageV2.read(account: account, defaults: defaults), canonical)
            activeAccount = account
            store.reloadAccount()
            XCTAssertEqual(store.sessions, [original])
        }
    }

    func testPersistedJournalChangeRejectsStaleQualificationWithoutOverwritingNewFacts() throws {
        try withFixture { defaults, diagnosticRoot, zone in
            save(arrivalState(zone, includeDeparture: true), defaults)
            let store = WorkStoreV2(defaults: defaults, diagnosticRoot: diagnosticRoot, accountProvider: { self.account })
            XCTAssertTrue(store.processAutomaticGpsPointage())
            let stale = try XCTUnwrap(store.sessions.first)
            var revised = stale
            revised.pauses = [.init(id: UUID(), start: entryAt.addingTimeInterval(30 * 60),
                                    end: entryAt.addingTimeInterval(45 * 60), paid: true)]
            XCTAssertTrue(WorkSessionAccountStorageV2.write([revised], account: account, defaults: defaults))
            XCTAssertFalse(store.qualifyGpsSession(stale, account: account, as: .work))
            XCTAssertEqual(WorkSessionAccountStorageV2.read(account: account, defaults: defaults), .valid([revised]))
        }
    }

    func testUnavailableQualificationKindsAndOpenSessionsAreRejected() throws {
        try withFixture { defaults, diagnosticRoot, zone in
            save(arrivalState(zone, includeDeparture: false), defaults)
            let store = WorkStoreV2(defaults: defaults, diagnosticRoot: diagnosticRoot, accountProvider: { self.account })
            XCTAssertTrue(store.processAutomaticGpsPointage())
            let open = try XCTUnwrap(store.currentSession)
            XCTAssertFalse(store.qualifyGpsSession(open, account: account, as: .work))
            XCTAssertFalse(store.qualifyGpsSession(open, account: account, as: .paidTravel))
            XCTAssertFalse(store.qualifyGpsSession(open, account: account, as: .toConfirm))
            XCTAssertEqual(store.currentSession, open)
        }
    }

    func testLegacyDepartureIsArchivedBeforeNewArrivalWithoutClosingHistoricalSession() throws {
        try withFixture { defaults, diagnosticRoot, zone in
            let historical = WorkSession(id: UUID(), entry: entryAt.addingTimeInterval(-7_200),
                exit: entryAt.addingTimeInterval(-6_000), pauses: [], employerId: employer)
            XCTAssertTrue(WorkSessionAccountStorageV2.write([historical], account: account, defaults: defaults))
            let legacy = GpsPendingEventV2(id: UUID(), kind: .departure, zoneIds: [zone.id],
                occurredAt: entryAt.addingTimeInterval(-3_600), expectedSessionId: historical.id,
                expectedSessionEntry: historical.entry)
            var pending = arrivalState(zone, includeDeparture: false)
            pending.pendingEvents.insert(legacy, at: 0)
            save(pending, defaults)
            let store = WorkStoreV2(defaults: defaults, diagnosticRoot: diagnosticRoot, accountProvider: { self.account })
            XCTAssertTrue(store.processAutomaticGpsPointage())
            XCTAssertEqual(store.sessions.count, 2)
            XCTAssertEqual(store.sessions.first, historical)
            XCTAssertEqual(store.currentSession?.entry, entryAt)
            XCTAssertNil(store.currentSession?.exit)
            let archive = try XCTUnwrap(GpsObservationArchiveStoreV2.read(account: account, defaults: defaults))
            XCTAssertEqual(archive.legacy.map(\.event), [legacy])
            XCTAssertTrue(try readState(defaults).pendingEvents.isEmpty)
            XCTAssertTrue(store.processAutomaticGpsPointage())
            XCTAssertEqual(store.sessions.count, 2)
        }
    }

    func testCorruptArchiveCannotDiscardLegacyHeadOrCreateFollowingSession() throws {
        try withFixture { defaults, diagnosticRoot, zone in
            let legacy = GpsPendingEventV2(id: UUID(), kind: .departure, zoneIds: [zone.id],
                                            occurredAt: entryAt.addingTimeInterval(-60))
            var pending = arrivalState(zone, includeDeparture: false)
            pending.pendingEvents.insert(legacy, at: 0)
            save(pending, defaults)
            let corrupt = Data("do-not-overwrite".utf8)
            let key = GpsObservationArchiveStoreV2.key(account: account)
            defaults.set(corrupt, forKey: key)
            let store = WorkStoreV2(defaults: defaults, diagnosticRoot: diagnosticRoot, accountProvider: { self.account })
            XCTAssertFalse(store.processAutomaticGpsPointage())
            XCTAssertTrue(store.sessions.isEmpty)
            XCTAssertEqual(try readState(defaults), pending)
            XCTAssertEqual(defaults.data(forKey: key), corrupt)
        }
    }
}
#endif
