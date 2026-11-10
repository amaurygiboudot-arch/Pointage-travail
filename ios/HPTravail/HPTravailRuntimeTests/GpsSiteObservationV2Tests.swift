import Foundation
import XCTest
@testable import RuntimeV2Contract

final class GpsSiteObservationV2Tests: XCTestCase {
    private let account = "uid:site-observation"
    private let base = Date(timeIntervalSince1970: 1_800_000_000)
    private let zoneA = GpsZoneV2(id: UUID(), label: "A", latitude: 48, longitude: 2,
                                radius: 150, employerId: "company", kind: .worksite)
    private let zoneB = GpsZoneV2(id: UUID(), label: "B", latitude: 48.0001, longitude: 2,
                                radius: 150, employerId: "company", kind: .worksite)
    private var fingerprint: String { GpsZoneConfigurationV2.fingerprint(enabled: true, zones: [zoneA, zoneB])! }
    private func at(_ seconds: TimeInterval) -> Date { base.addingTimeInterval(seconds) }

    private func append(_ observations: [GpsSiteObservationV2], _ zone: GpsZoneV2,
                        entered: Bool, second: TimeInterval) throws -> [GpsSiteObservationV2] {
        try XCTUnwrap(GpsSiteObservationLogV2.appending(to: observations, account: account,
            fingerprint: fingerprint, zone: zone, entered: entered, at: at(second), continuityId: "continuous-test"))
    }

    private func state(observations: [GpsSiteObservationV2], events: [GpsPendingEventV2] = []) -> GpsPersistedStateV2 {
        .init(fingerprint: fingerprint, activeZoneIds: [], pendingExitZoneIds: [], pendingEvents: events,
            confirmedSessionId: nil, eventQueueOverflowed: false, accountScope: account,
            observations: observations)
    }

    func testEveryOverlappingWorkBoundaryIsRetainedWithExactProvenance() throws {
        var log = try append([], zoneA, entered: true, second: 0)
        log = try append(log, zoneB, entered: true, second: 10)
        log = try append(log, zoneA, entered: false, second: 20)
        log = try append(log, zoneB, entered: false, second: 30)
        XCTAssertEqual(log.map(\.zoneId), [zoneA.id, zoneB.id, zoneA.id, zoneB.id])
        XCTAssertEqual(log.map(\.entered), [true, true, false, false])
        XCTAssertEqual(log.map(\.observedAt), [at(0), at(10), at(20), at(30)])
        XCTAssertEqual(log.map(\.zoneLabel), ["A", "B", "A", "B"])
        XCTAssertTrue(log.allSatisfy { $0.account == account && $0.fingerprint == fingerprint && $0.employerId == "company" })
        XCTAssertEqual(Set(log.map(\.id)).count, 4)
    }

    func testRepeatedPresenceCallbackKeepsOriginalObservationIdAndTime() throws {
        let initial = try append([], zoneA, entered: true, second: 0)
        let withOverlap = try append(initial, zoneB, entered: true, second: 10)
        XCTAssertEqual(try append(withOverlap, zoneA, entered: true, second: 20), withOverlap)
        let exited = try append(withOverlap, zoneA, entered: false, second: 30)
        XCTAssertEqual(try append(exited, zoneA, entered: false, second: 40), exited)
        let returned = try append(exited, zoneA, entered: true, second: 50)
        XCTAssertEqual(returned.count, 4)
        XCTAssertNotEqual(returned[0].id, returned[3].id)
    }

    func testWrongOwnerConfigurationOutOfOrderAndHomeCannotEnterWorkLog() throws {
        let log = try append([], zoneA, entered: true, second: 10)
        XCTAssertNil(GpsSiteObservationLogV2.appending(to: log, account: "uid:other", fingerprint: fingerprint,
            zone: zoneA, entered: false, at: at(20)))
        XCTAssertNil(GpsSiteObservationLogV2.appending(to: log, account: account, fingerprint: "other",
            zone: zoneA, entered: false, at: at(20)))
        XCTAssertNil(GpsSiteObservationLogV2.appending(to: log, account: account, fingerprint: fingerprint,
            zone: zoneA, entered: false, at: at(0)))
        var home = zoneA
        home.kind = .home
        XCTAssertNil(GpsSiteObservationLogV2.appending(to: log, account: account, fingerprint: fingerprint,
            zone: home, entered: true, at: at(20)))
    }

    func testAcknowledgementKeepsWorkObservationsWhenManualSessionAlreadyExists() throws {
        let log = try append([], zoneA, entered: true, second: 0)
        let event = GpsPendingEventV2(id: UUID(), kind: .arrival, zoneIds: [zoneA.id], occurredAt: base,
                                      siteFactVersion: 1)
        var pending = state(observations: log, events: [event])
        pending.activeZoneIds = [zoneA.id]
        let manual = WorkSession(id: UUID(), entry: at(-60), exit: nil, pauses: [], employerId: "company")
        let commit = try XCTUnwrap(GpsAutomaticPointageV2.plan(state: pending, zones: [zoneA, zoneB],
            account: account, sessions: [manual], confirmedEmployerIds: ["company"], now: at(60)))
        XCTAssertEqual(commit.sessions, [manual])
        XCTAssertEqual(commit.state.observations, log)
        XCTAssertTrue(commit.state.pendingEvents.isEmpty)
        XCTAssertEqual(commit.state.confirmedSessionId, manual.id)
    }

    func testUnknownSiteFactVersionIsRejectedByStartupValidation() {
        let unsupported = GpsPendingEventV2(id: UUID(), kind: .arrival, zoneIds: [zoneA.id],
                                            occurredAt: base, siteFactVersion: 2)
        let pending = state(observations: [], events: [unsupported])
        XCTAssertEqual(GpsStateValidationV2.startupState(read: .valid(pending), expectedFingerprint: fingerprint,
            configuredZoneIds: [zoneA.id, zoneB.id], expectedAccountScope: account), .suspend(pending))
    }

    func testRawBoundariesProjectJourneyEvenWhenManualSessionKeepsItsOriginalEntry() throws {
        var log = try append([], zoneA, entered: true, second: 0)
        log = try append(log, zoneA, entered: false, second: 3_600)
        log = try append(log, zoneB, entered: true, second: 5_400)
        let manual = WorkSession(id: UUID(), entry: at(-60), exit: nil,
                                 pauses: [], employerId: "company", placeLabel: "Déclaration manuelle")
        let journey = try XCTUnwrap(GpsSiteJourneyProjectionV2.journeys(
            sessions: [manual], account: account, homeObservations: [], siteObservations: log
        ).first)
        XCTAssertEqual(journey.fromLabel, "A")
        XCTAssertEqual(journey.toLabel, "B")
        XCTAssertEqual(journey.departure, at(3_600))
        XCTAssertEqual(journey.arrival, at(5_400))
        XCTAssertEqual(journey.observedDuration, 1_800)
        XCTAssertEqual(manual.entry, at(-60))
        XCTAssertNil(manual.exit)
        XCTAssertNil(manual.segments)
    }

    func testRawJourneyRetainsHomeExclusionWithoutChangingWorkObservations() throws {
        var log = try append([], zoneA, entered: true, second: 0)
        log = try append(log, zoneA, entered: false, second: 3_600)
        log = try append(log, zoneB, entered: true, second: 5_400)
        let home = GpsHomeObservationV2(id: UUID(), account: account, fingerprint: fingerprint,
            zoneId: UUID(), entered: true, observedAt: at(4_000))
        let journey = try XCTUnwrap(GpsSiteJourneyProjectionV2.journeys(
            sessions: [], account: account, homeObservations: [home], siteObservations: log
        ).first)
        XCTAssertTrue(journey.excludedByHome)
        XCTAssertEqual(journey.observedDuration, 1_800)
        XCTAssertEqual(log.map(\.entered), [true, false, true])
    }

    private func withDefaults(_ body: (UserDefaults) throws -> Void) throws {
        let suite = "gps-observation-archive-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        try body(defaults)
    }

    func testArchiveIsIdempotentAndPreservesLegacyWithoutPromotingIt() throws {
        try withDefaults { defaults in
            let log = try append([], zoneA, entered: true, second: 0)
            let legacy = GpsPendingEventV2(id: UUID(), kind: .departure, zoneIds: [zoneA.id], occurredAt: at(10))
            let modern = GpsPendingEventV2(id: UUID(), kind: .arrival, zoneIds: [zoneA.id],
                                           occurredAt: at(20), siteFactVersion: 1)
            let pending = state(observations: log, events: [legacy, modern])
            XCTAssertTrue(GpsObservationArchiveStoreV2.preserve(pending, account: account, defaults: defaults))
            let archived = try XCTUnwrap(GpsObservationArchiveStoreV2.read(account: account, defaults: defaults))
            XCTAssertEqual(archived.observations, log)
            XCTAssertEqual(archived.legacy.map(\.event), [legacy])
            XCTAssertNil(archived.legacy[0].event.siteFactVersion)
            XCTAssertTrue(GpsObservationArchiveStoreV2.preserve(pending, account: account, defaults: defaults))
            XCTAssertEqual(GpsObservationArchiveStoreV2.read(account: account, defaults: defaults), archived)
            XCTAssertFalse(GpsObservationArchiveStoreV2.preserve(pending, account: "uid:other", defaults: defaults))
            XCTAssertEqual(GpsObservationArchiveStoreV2.read(account: "uid:other", defaults: defaults), GpsObservationArchiveV2())
        }
    }

    func testCorruptOrOtherAccountArchiveIsNotOverwritten() throws {
        try withDefaults { defaults in
            let observations = try append([], zoneA, entered: true, second: 0)
            let pending = state(observations: observations)
            let wrong = GpsSiteObservationV2(id: UUID(), account: "uid:other", fingerprint: fingerprint,
                zoneId: zoneA.id, zoneLabel: "A", employerId: "company", entered: true, observedAt: base)
            let badOwner = try JSONEncoder().encode(GpsObservationArchiveV2(observations: [wrong]))
            let duplicateIds = try JSONEncoder().encode(GpsObservationArchiveV2(observations: observations + observations))
            let key = GpsObservationArchiveStoreV2.key(account: account)
            for bytes in [Data("corrupt".utf8), badOwner, duplicateIds] {
                defaults.set(bytes, forKey: key)
                XCTAssertNil(GpsObservationArchiveStoreV2.read(account: account, defaults: defaults))
                XCTAssertFalse(GpsObservationArchiveStoreV2.preserve(pending, account: account, defaults: defaults))
                XCTAssertEqual(defaults.data(forKey: key), bytes)
            }
        }
    }

    func testSameObservationIdWithChangedContentDoesNotOverwriteArchive() throws {
        try withDefaults { defaults in
            let log = try append([], zoneA, entered: true, second: 0)
            XCTAssertTrue(GpsObservationArchiveStoreV2.preserve(state(observations: log), account: account, defaults: defaults))
            let key = GpsObservationArchiveStoreV2.key(account: account)
            let original = defaults.data(forKey: key)
            let changed = GpsSiteObservationV2(id: log[0].id, account: account, fingerprint: fingerprint,
                zoneId: zoneA.id, zoneLabel: "A", employerId: "company", entered: false, observedAt: at(20))
            XCTAssertFalse(GpsObservationArchiveStoreV2.preserve(state(observations: [changed]), account: account, defaults: defaults))
            XCTAssertEqual(defaults.data(forKey: key), original)
        }
    }
    func testInterruptedOrUnknownContinuityCannotCreateJourney() throws {
        var log = try append([], zoneA, entered: true, second: 0)
        log = try append(log, zoneA, entered: false, second: 3600)
        log = try append(log, zoneB, entered: true, second: 5400)
        log[2].continuityId = "after-gps-disabled"
        XCTAssertTrue(GpsSiteJourneyProjectionV2.journeys(sessions: [], account: account,
            homeObservations: [], siteObservations: log).isEmpty)
        log[2].continuityId = nil
        XCTAssertTrue(GpsSiteJourneyProjectionV2.journeys(sessions: [], account: account,
            homeObservations: [], siteObservations: log).isEmpty)
    }

    func testOverlapAndInitialOutsideStateCannotInventDepartureJourney() throws {
        let zoneC = GpsZoneV2(id: UUID(), label: "C", latitude: 49, longitude: 3,
            radius: 100, employerId: "company", kind: .worksite)
        var log = try append([], zoneA, entered: true, second: 0)
        log = try append(log, zoneB, entered: true, second: 10)
        log = try append(log, zoneA, entered: false, second: 20)
        log = try append(log, zoneC, entered: true, second: 30)
        XCTAssertTrue(GpsSiteJourneyProjectionV2.journeys(sessions: [], account: account,
            homeObservations: [], siteObservations: log).isEmpty)
        var initialOutside = try append([], zoneA, entered: false, second: 0)
        initialOutside = try append(initialOutside, zoneB, entered: true, second: 30)
        XCTAssertTrue(GpsSiteJourneyProjectionV2.journeys(sessions: [], account: account,
            homeObservations: [], siteObservations: initialOutside).isEmpty)
    }

    func testMonitoringContinuitySurvivesOrdinaryProcessRelaunch() throws {
        try withDefaults { defaults in
            let first = try XCTUnwrap(GpsObservationContinuityV2.current(defaults: defaults))
            XCTAssertEqual(GpsObservationContinuityV2.current(defaults: defaults), first)
            // A fresh reader reconstructs the generation from durable storage.
            XCTAssertEqual(defaults.string(forKey: GpsObservationContinuityV2.key), first)
            let encoded = try JSONEncoder().encode(try append([], zoneA, entered: true, second: 0))
            defaults.set(encoded, forKey: "observation-proof")
            let next = try XCTUnwrap(GpsObservationContinuityV2.rotate(defaults: defaults))
            XCTAssertNotEqual(next, first)
            XCTAssertEqual(GpsObservationContinuityV2.current(defaults: defaults), next)
            XCTAssertEqual(defaults.data(forKey: "observation-proof"), encoded)
        }
    }

    func testInvalidMonitoringGenerationCannotResumeOldContinuity() throws {
        try withDefaults { defaults in
            defaults.set("unknown-generation", forKey: GpsObservationContinuityV2.key)
            let recovered = try XCTUnwrap(GpsObservationContinuityV2.current(defaults: defaults))
            XCTAssertNotNil(UUID(uuidString: recovered))
            XCTAssertNotEqual(recovered, "unknown-generation")
        }
    }

    private final class CountingDefaults: UserDefaults {
        var writes = 0
        override func set(_ value: Any?, forKey defaultName: String) {
            writes += 1
            super.set(value, forKey: defaultName)
        }
    }

    func testExactArchiveReplayPerformsNoWriteAndStillRejectsChangedObservation() throws {
        let suite = "gps-archive-write-count-\(UUID())"
        let defaults = try XCTUnwrap(CountingDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let log = try append([], zoneA, entered: true, second: 0)
        let legacy = GpsPendingEventV2(id: UUID(), kind: .departure, zoneIds: [zoneA.id], occurredAt: at(10))
        let pending = state(observations: log, events: [legacy])
        XCTAssertTrue(GpsObservationArchiveStoreV2.preserve(pending, account: account, defaults: defaults))
        let writes = defaults.writes
        XCTAssertGreaterThan(writes, 0)
        XCTAssertTrue(GpsObservationArchiveStoreV2.preserve(pending, account: account, defaults: defaults))
        XCTAssertEqual(defaults.writes, writes)
        let conflict = GpsSiteObservationV2(id: log[0].id, account: account, fingerprint: fingerprint,
            zoneId: zoneA.id, zoneLabel: "A", employerId: "company", entered: false, observedAt: at(20))
        XCTAssertFalse(GpsObservationArchiveStoreV2.preserve(state(observations: [conflict]),
            account: account, defaults: defaults))
        XCTAssertEqual(defaults.writes, writes)
        XCTAssertEqual(GpsObservationArchiveStoreV2.read(account: account, defaults: defaults)?.observations, log)
    }

}
