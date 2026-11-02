import Foundation
import XCTest
@testable import RuntimeV2Contract

final class GpsSiteJourneyV2Tests: XCTestCase {
    private let account = "uid:journey-test"
    private let fingerprint = "test-configuration"
    private let firstZone = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
    private let secondZone = UUID(uuidString: "22222222-2222-2222-2222-222222222222")!
    private let homeZone = UUID(uuidString: "33333333-3333-3333-3333-333333333333")!
    private let base = Date(timeIntervalSince1970: 1_800_000_000)

    private func at(_ seconds: TimeInterval) -> Date { base.addingTimeInterval(seconds) }

    private func session(zone: UUID, label: String, entry: Date, exit: Date?) -> WorkSession {
        let id = UUID()
        var value = WorkSession(id: id, entry: entry, exit: exit, pauses: [],
                                employerId: "company", placeLabel: label)
        value.gpsEntryEventId = id
        value.gpsZoneId = zone
        value.gpsConfigurationFingerprint = fingerprint
        value.gpsAccountScope = account
        value.gpsHomeExclusionConfigured = true
        value.segments = [.init(start: entry, end: exit, kind: .toConfirm, confirmed: false)]
        if let exit {
            value.gpsExitEventId = UUID()
            value.departureObservedAt = exit
            value.closureOrigin = "AUTOMATIC"
        }
        return value
    }

    private var pair: [WorkSession] {
        [session(zone: firstZone, label: "Site A", entry: at(0), exit: at(3_600)),
         session(zone: secondZone, label: "Site B", entry: at(5_400), exit: nil)]
    }

    private func home(entered: Bool, at seconds: TimeInterval,
                      owner: String? = nil, config: String? = nil) -> GpsHomeObservationV2 {
        .init(id: UUID(), account: owner ?? account, fingerprint: config ?? fingerprint,
              zoneId: homeZone, entered: entered, observedAt: at(seconds))
    }

    private func journeys(_ sessions: [WorkSession], home: [GpsHomeObservationV2]? = []) -> [GpsSiteJourneyV2] {
        GpsSiteJourneyProjectionV2.journeys(sessions: sessions, account: account, homeObservations: home)
    }

    func testWorksiteDepartureToAnotherWorksiteProjectsExactObservedDuration() throws {
        let sessions = pair
        let result = try XCTUnwrap(journeys(sessions).first)
        XCTAssertEqual(result.fromLabel, "Site A")
        XCTAssertEqual(result.toLabel, "Site B")
        XCTAssertEqual(result.departure, at(3_600))
        XCTAssertEqual(result.arrival, at(5_400))
        XCTAssertEqual(result.observedDuration, 1_800)
        XCTAssertEqual(result.id, sessions[0].gpsExitEventId!.uuidString + "." + sessions[1].id.uuidString)
        XCTAssertFalse(result.excludedByHome)
        XCTAssertTrue(result.homeExclusionConfigured)
        XCTAssertTrue(result.homeEvidenceReadable)
        // Projection never changes the qualification of the canonical pointages.
        XCTAssertTrue(sessions.allSatisfy { $0.segments?.allSatisfy { !$0.confirmed && $0.kind == .toConfirm } == true })
    }

    func testEnteringHomeDuringGapExcludesJourneyEvenWhenLeavingBeforeArrival() throws {
        let result = try XCTUnwrap(journeys(pair, home: [
            home(entered: true, at: 4_000), home(entered: false, at: 4_500)
        ]).first)
        XCTAssertTrue(result.excludedByHome)
        XCTAssertEqual(result.observedDuration, 1_800)
    }

    func testHomePresenceAlreadyActiveAtDepartureExcludesJourney() throws {
        let result = try XCTUnwrap(journeys(pair, home: [home(entered: true, at: 3_000)]).first)
        XCTAssertTrue(result.excludedByHome)
    }

    func testEarlierHomeVisitThatEndedBeforeDepartureDoesNotExcludeJourney() throws {
        let result = try XCTUnwrap(journeys(pair, home: [
            home(entered: true, at: 1_000), home(entered: false, at: 2_000)
        ]).first)
        XCTAssertFalse(result.excludedByHome)
        XCTAssertFalse(try XCTUnwrap(journeys(pair, home: [home(entered: true, at: 6_000)]).first).excludedByHome)
    }

    func testHomeEvidenceFromAnotherAccountOrConfigurationDoesNotCrossScopes() throws {
        let result = try XCTUnwrap(journeys(pair, home: [
            home(entered: true, at: 4_000, owner: "uid:another"),
            home(entered: true, at: 4_100, config: "another-configuration")
        ]).first)
        XCTAssertFalse(result.excludedByHome)
    }

    func testUnknownHomeEvidenceAndMissingConfigurationRemainExplicit() throws {
        let unreadable = try XCTUnwrap(journeys(pair, home: nil).first)
        XCTAssertFalse(unreadable.homeEvidenceReadable)
        var noHome = pair
        noHome[0].gpsHomeExclusionConfigured = nil
        let unconfigured = try XCTUnwrap(journeys(noHome).first)
        XCTAssertFalse(unconfigured.homeExclusionConfigured)
        XCTAssertTrue(unconfigured.homeEvidenceReadable)
    }

    func testSameWorksiteReturnAndNonpositiveGapDoNotCreateJourney() {
        var sameZone = pair
        sameZone[1].gpsZoneId = firstZone
        XCTAssertTrue(journeys(sameZone).isEmpty)
        var adjacent = pair
        adjacent[1].entry = at(3_600)
        adjacent[1].segments = [.init(start: at(3_600), end: nil, kind: .toConfirm, confirmed: false)]
        XCTAssertTrue(journeys(adjacent).isEmpty)
        var overlapping = pair
        overlapping[1].entry = at(3_500)
        overlapping[1].segments = [.init(start: at(3_500), end: nil, kind: .toConfirm, confirmed: false)]
        XCTAssertTrue(journeys(overlapping).isEmpty)
    }

    func testSessionAccountOrConfigurationMismatchPreventsPairing() {
        var other = pair
        other[1].gpsAccountScope = "uid:another"
        XCTAssertTrue(journeys(other).isEmpty)
        other = pair
        other[0].gpsAccountScope = "uid:another"
        XCTAssertTrue(journeys(other).isEmpty)
        other = pair
        other[1].gpsConfigurationFingerprint = "changed"
        XCTAssertTrue(journeys(other).isEmpty)
    }

    func testInterveningManualSessionPreventsArtificialDirectJourney() {
        let sessions = pair
        let manual = WorkSession(id: UUID(), entry: at(4_000), exit: at(4_500),
                                 pauses: [], employerId: "company", placeLabel: "Déclaration")
        XCTAssertTrue(journeys([sessions[0], manual, sessions[1]]).isEmpty)
        var manualDeparture = sessions[0]
        manualDeparture.gpsExitEventId = nil
        manualDeparture.closureOrigin = "MANUAL"
        XCTAssertTrue(journeys([manualDeparture, sessions[1]]).isEmpty)
    }

    func testHomeIsMonitoredButNeverProducesWorkPointage() {
        let work = GpsZoneV2(id: firstZone, label: "Travail", latitude: 48, longitude: 2,
                             radius: 150, employerId: "company", kind: .worksite)
        let home = GpsZoneV2(id: homeZone, label: "Domicile", latitude: 49, longitude: 2,
                             radius: 150, employerId: nil, kind: .home)
        XCTAssertEqual(Set(GpsZoneConfigurationV2.monitoredZones([work, home]).map(\.id)), [firstZone, homeZone])
        XCTAssertEqual(GpsZoneConfigurationV2.automaticZoneIds([work, home]), [firstZone])
        XCTAssertNil(GpsClockInFactsPolicyV2.facts(for: home))
        let empty = GpsPresenceTransitionV2.State(activeZoneIds: [], pendingExitZoneIds: [],
            pendingEvents: [], confirmedSessionId: nil, eventQueueOverflowed: false)
        for transition: GpsPresenceTransitionV2.Transition in [.enter, .exit] {
            XCTAssertEqual(GpsConfiguredPresenceTransitionV2.plan(state: empty,
                configuredZones: [work, home], zoneId: homeZone, transition: transition,
                occurredAt: base, recordsSiteFacts: true), empty)
        }
        XCTAssertNotEqual(GpsZoneConfigurationV2.fingerprint(enabled: true, zones: [work]),
                          GpsZoneConfigurationV2.fingerprint(enabled: true, zones: [work, home]))
    }

    private func withDefaults(_ body: (UserDefaults) throws -> Void) throws {
        let suite = "gps-home-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        try body(defaults)
    }

    func testHomeStorePreservesFirstObservationAndDeduplicatesSamePresence() throws {
        try withDefaults { defaults in
            XCTAssertEqual(GpsHomeObservationStoreV2.read(account: account, defaults: defaults), [])
            XCTAssertTrue(GpsHomeObservationStoreV2.append(account: account, fingerprint: fingerprint,
                zoneId: homeZone, entered: true, at: at(100), defaults: defaults))
            let original = try XCTUnwrap(GpsHomeObservationStoreV2.read(account: account, defaults: defaults))
            XCTAssertTrue(GpsHomeObservationStoreV2.append(account: account, fingerprint: fingerprint,
                zoneId: homeZone, entered: true, at: at(200), defaults: defaults))
            XCTAssertEqual(GpsHomeObservationStoreV2.read(account: account, defaults: defaults), original)
            XCTAssertTrue(GpsHomeObservationStoreV2.append(account: account, fingerprint: fingerprint,
                zoneId: homeZone, entered: false, at: at(300), defaults: defaults))
            let complete = try XCTUnwrap(GpsHomeObservationStoreV2.read(account: account, defaults: defaults))
            XCTAssertEqual(complete.map(\.entered), [true, false])
            XCTAssertEqual(complete.map(\.observedAt), [at(100), at(300)])
            XCTAssertEqual(GpsHomeObservationStoreV2.read(account: "uid:another", defaults: defaults), [])
        }
    }

    func testCorruptOrCrossAccountHomeJournalIsNeverReplacedByAppend() throws {
        try withDefaults { defaults in
            let key = GpsHomeObservationStoreV2.key(account: account)
            let wrongOwner = try JSONEncoder().encode([home(entered: true, at: 100, owner: "uid:another")])
            let duplicate = home(entered: true, at: 100)
            let duplicatedIds = try JSONEncoder().encode([duplicate, duplicate])
            let outOfOrder = try JSONEncoder().encode([home(entered: true, at: 200), home(entered: false, at: 100)])
            for bytes in [Data("corrupt".utf8), wrongOwner, duplicatedIds, outOfOrder] {
                defaults.set(bytes, forKey: key)
                XCTAssertNil(GpsHomeObservationStoreV2.read(account: account, defaults: defaults))
                XCTAssertFalse(GpsHomeObservationStoreV2.append(account: account, fingerprint: fingerprint,
                    zoneId: homeZone, entered: false, at: at(300), defaults: defaults))
                XCTAssertEqual(defaults.data(forKey: key), bytes)
            }
        }
    }

    func testHomeStoreRejectsOutOfOrderOrInvalidAppendWithoutMutation() throws {
        try withDefaults { defaults in
            XCTAssertTrue(GpsHomeObservationStoreV2.append(account: account, fingerprint: fingerprint,
                zoneId: homeZone, entered: true, at: at(200), defaults: defaults))
            let key = GpsHomeObservationStoreV2.key(account: account)
            let original = defaults.data(forKey: key)
            XCTAssertFalse(GpsHomeObservationStoreV2.append(account: account, fingerprint: fingerprint,
                zoneId: homeZone, entered: false, at: at(100), defaults: defaults))
            XCTAssertFalse(GpsHomeObservationStoreV2.append(account: account, fingerprint: "",
                zoneId: homeZone, entered: false, at: at(300), defaults: defaults))
            XCTAssertFalse(GpsHomeObservationStoreV2.append(account: account, fingerprint: fingerprint,
                zoneId: homeZone, entered: false, at: Date(timeIntervalSince1970: .nan), defaults: defaults))
            XCTAssertEqual(defaults.data(forKey: key), original)
        }
    }
}
