import Foundation
import XCTest
@testable import RuntimeV2Contract

final class WorkSessionPersistenceV2Tests: XCTestCase {
    func testFailedRepairRemainsRetryableAndDoesNotDuplicateSessions() throws {
        let suite = "repair-retry-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(RejectingPrimaryDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let session = WorkSession(id: UUID(), entry: start, exit: start.addingTimeInterval(3600),
            pauses: [PausePeriod(id: UUID(), start: start.addingTimeInterval(900),
                                 end: start.addingTimeInterval(1200), paid: nil)])
        let source = try JSONEncoder().encode([session])
        defaults.set(source, forKey: WorkSessionStorageV2.primaryKey)
        defaults.rejectPrimaryWrite = true
        XCTAssertEqual(WorkSessionStorageV2.load(defaults: defaults) {
            WorkSessionPersistenceV2.write($0, defaults: defaults)
        }, .corrupt)
        XCTAssertFalse(defaults.bool(forKey: WorkSessionStorageV2.paidRepairMarkerKey))
        XCTAssertEqual(defaults.data(forKey: WorkSessionStorageV2.primaryKey), source)

        // A new load retries the same primary; no fallback to unrelated legacy data.
        defaults.rejectPrimaryWrite = false
        guard case .valid(let repaired, let origin) = WorkSessionStorageV2.load(defaults: defaults, persist: {
            WorkSessionPersistenceV2.write($0, defaults: defaults)
        }) else { return XCTFail("Repair should retry after the write failure") }
        XCTAssertEqual(origin, .transitionalPrimaryRepair)
        XCTAssertEqual(repaired.map(\.id), [session.id])
        XCTAssertEqual(repaired.first?.pauses.first?.paid, false)
        XCTAssertTrue(defaults.bool(forKey: WorkSessionStorageV2.paidRepairMarkerKey))
        XCTAssertEqual(WorkSessionStorageV2.load(defaults: defaults) { _ in
            XCTFail("Successful migration must not run again")
            return false
        }, .valid(repaired, origin: .primary))
    }

    func testFailedLegacyMigrationRetainsSourceAndMarkerForRetry() throws {
        let suite = "legacy-retry-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(RejectingPrimaryDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let session = WorkSession(id: UUID(), entry: start, exit: start.addingTimeInterval(3600), pauses: [])
        let source = try JSONEncoder().encode([session])
        defaults.set(source, forKey: WorkSessionStorageV2.legacyKey)
        defaults.rejectPrimaryWrite = true
        XCTAssertEqual(WorkSessionStorageV2.load(defaults: defaults) {
            WorkSessionPersistenceV2.write($0, defaults: defaults)
        }, .corrupt)
        XCTAssertEqual(defaults.data(forKey: WorkSessionStorageV2.legacyKey), source)
        XCTAssertFalse(defaults.bool(forKey: WorkSessionStorageV2.paidRepairMarkerKey))
        defaults.rejectPrimaryWrite = false
        XCTAssertEqual(WorkSessionStorageV2.load(defaults: defaults) {
            WorkSessionPersistenceV2.write($0, defaults: defaults)
        }, .valid([session], origin: .legacyMigration))
        XCTAssertNil(defaults.data(forKey: WorkSessionStorageV2.legacyKey))
        XCTAssertEqual(WorkSessionPersistenceV2.read(defaults.data(forKey: WorkSessionStorageV2.primaryKey)), .valid([session]))
    }

    private let start = Date(timeIntervalSinceReferenceDate: 1_000)

    func testOverlappingSessionsAreCorrupt() throws {
        let first = WorkSession(
            id: UUID(),
            entry: start,
            exit: start.addingTimeInterval(3_600),
            pauses: []
        )
        let second = WorkSession(
            id: UUID(),
            entry: start.addingTimeInterval(1_800),
            exit: start.addingTimeInterval(5_400),
            pauses: []
        )

        XCTAssertEqual(
            WorkSessionPersistenceV2.read(try JSONEncoder().encode([first, second])),
            .corrupt
        )
    }

    func testOverlappingPausesAreCorruptAndAdjacentPausesRemainValid() throws {
        let overlapping = WorkSession(
            id: UUID(),
            entry: start,
            exit: start.addingTimeInterval(3_600),
            pauses: [
                PausePeriod(
                    id: UUID(),
                    start: start.addingTimeInterval(600),
                    end: start.addingTimeInterval(1_200),
                    paid: true
                ),
                PausePeriod(
                    id: UUID(),
                    start: start.addingTimeInterval(900),
                    end: start.addingTimeInterval(1_500),
                    paid: false
                )
            ]
        )
        XCTAssertEqual(
            WorkSessionPersistenceV2.read(try JSONEncoder().encode([overlapping])),
            .corrupt
        )

        let adjacent = WorkSession(
            id: UUID(),
            entry: start,
            exit: start.addingTimeInterval(3_600),
            pauses: [
                PausePeriod(
                    id: UUID(),
                    start: start.addingTimeInterval(600),
                    end: start.addingTimeInterval(900),
                    paid: true
                ),
                PausePeriod(
                    id: UUID(),
                    start: start.addingTimeInterval(900),
                    end: start.addingTimeInterval(1_200),
                    paid: false
                )
            ]
        )
        XCTAssertEqual(
            WorkSessionPersistenceV2.read(try JSONEncoder().encode([adjacent])),
            .valid([adjacent])
        )
    }

    func testMissingStorageIsReliableEmptyState() {
        XCTAssertEqual(WorkSessionPersistenceV2.read(nil), .missing)
    }

    func testMalformedStorageIsCorruptInsteadOfEmpty() {
        XCTAssertEqual(WorkSessionPersistenceV2.read(Data("not-json".utf8)), .corrupt)
    }

    func testLegacyPauseWithoutPaidStatusRemainsReadableButUnresolved() throws {
        let session = WorkSession(
            id: UUID(),
            entry: start,
            exit: nil,
            pauses: [PausePeriod(id: UUID(), start: start.addingTimeInterval(300), end: nil)]
        )

        guard case .valid(let sessions) = WorkSessionPersistenceV2.readLegacy(
            try JSONEncoder().encode([session])
        ) else {
            return XCTFail("Expected a valid legacy session")
        }
        XCTAssertNil(try XCTUnwrap(sessions.first).pauses.first?.paid)
    }

    func testLegacyCompletedPauseWithoutPaidStatusMigratesAsHistoricallyUnpaid() throws {
        let session = WorkSession(
            id: UUID(),
            entry: start,
            exit: start.addingTimeInterval(3_600),
            pauses: [
                PausePeriod(
                    id: UUID(),
                    start: start.addingTimeInterval(900),
                    end: start.addingTimeInterval(1_200),
                    paid: nil
                )
            ]
        )

        guard case .valid(let sessions, let origin) = WorkSessionStorageV2.resolve(
            primaryData: nil,
            legacyData: try JSONEncoder().encode([session])
        ) else {
            return XCTFail("Expected the historical V1 session to migrate")
        }
        XCTAssertEqual(origin, .legacyMigration)
        XCTAssertEqual(try XCTUnwrap(sessions.first).pauses.first?.paid, false)
    }

    func testCompletedPauseWithoutPaidStatusIsCorruptInPrimaryV2() throws {
        let session = WorkSession(
            id: UUID(),
            entry: start,
            exit: nil,
            pauses: [
                PausePeriod(
                    id: UUID(),
                    start: start.addingTimeInterval(300),
                    end: start.addingTimeInterval(600),
                    paid: nil
                )
            ]
        )

        XCTAssertEqual(
            WorkSessionPersistenceV2.read(try JSONEncoder().encode([session])),
            .corrupt
        )
    }

    func testClosedSessionWithoutExplicitPauseStatusIsCorruptInPrimaryV2() throws {
        let session = WorkSession(
            id: UUID(),
            entry: start,
            exit: start.addingTimeInterval(3_600),
            pauses: [
                PausePeriod(
                    id: UUID(),
                    start: start.addingTimeInterval(900),
                    end: start.addingTimeInterval(1_200),
                    paid: nil
                )
            ]
        )

        XCTAssertEqual(
            WorkSessionPersistenceV2.read(try JSONEncoder().encode([session])),
            .corrupt
        )
    }

    func testTransitionalPrimaryRepairNormalizesOnlyHistoricalCompletedPause() throws {
        let session = WorkSession(
            id: UUID(),
            entry: start,
            exit: start.addingTimeInterval(3_600),
            pauses: [
                PausePeriod(
                    id: UUID(),
                    start: start.addingTimeInterval(900),
                    end: start.addingTimeInterval(1_200),
                    paid: nil
                )
            ]
        )
        let data = try JSONEncoder().encode([session])

        XCTAssertEqual(
            WorkSessionStorageV2.resolve(
                primaryData: data,
                legacyData: nil,
                allowTransitionalPrimaryRepair: false
            ),
            .corrupt
        )

        guard case .valid(let sessions, let origin) = WorkSessionStorageV2.resolve(
            primaryData: data,
            legacyData: nil,
            allowTransitionalPrimaryRepair: true
        ) else {
            return XCTFail("Expected the one-shot transitional primary repair")
        }
        XCTAssertEqual(origin, .transitionalPrimaryRepair)
        XCTAssertEqual(try XCTUnwrap(sessions.first).pauses.first?.paid, false)
    }

    func testTransitionalPrimaryRepairKeepsOpenPauseUnresolved() throws {
        let session = WorkSession(
            id: UUID(),
            entry: start,
            exit: nil,
            pauses: [
                PausePeriod(
                    id: UUID(),
                    start: start.addingTimeInterval(900),
                    end: nil,
                    paid: nil
                )
            ]
        )

        guard case .valid(let sessions, let origin) = WorkSessionStorageV2.resolve(
            primaryData: try JSONEncoder().encode([session]),
            legacyData: nil,
            allowTransitionalPrimaryRepair: true
        ) else {
            return XCTFail("Expected an unresolved open pause to remain readable")
        }
        XCTAssertEqual(origin, .primary)
        XCTAssertNil(try XCTUnwrap(sessions.first).pauses.first?.paid)
    }

    func testTwoOpenSessionsAreCorrupt() throws {
        let sessions = [
            WorkSession(id: UUID(), entry: start, exit: nil, pauses: []),
            WorkSession(id: UUID(), entry: start.addingTimeInterval(60), exit: nil, pauses: [])
        ]

        XCTAssertEqual(
            WorkSessionPersistenceV2.read(try JSONEncoder().encode(sessions)),
            .corrupt
        )
    }

    func testClosedSessionCannotContainOpenPause() throws {
        let session = WorkSession(
            id: UUID(),
            entry: start,
            exit: start.addingTimeInterval(3_600),
            pauses: [PausePeriod(id: UUID(), start: start.addingTimeInterval(900), end: nil, paid: false)]
        )

        XCTAssertEqual(
            WorkSessionPersistenceV2.read(try JSONEncoder().encode([session])),
            .corrupt
        )
    }

    func testValidSessionPreservesExplicitPauseStatus() throws {
        let session = validSession()

        XCTAssertEqual(
            WorkSessionPersistenceV2.read(try JSONEncoder().encode([session])),
            .valid([session])
        )
    }

    func testV2StorageWinsWhenBothV2AndLegacyExist() throws {
        let v2 = validSession(offset: 0)
        let legacy = validSession(offset: 10_000)

        XCTAssertEqual(
            WorkSessionStorageV2.resolve(
                primaryData: try JSONEncoder().encode([v2]),
                legacyData: try JSONEncoder().encode([legacy])
            ),
            .valid([v2], origin: .primary)
        )
    }

    func testCorruptV2NeverFallsBackToValidLegacy() throws {
        let legacy = validSession()

        XCTAssertEqual(
            WorkSessionStorageV2.resolve(
                primaryData: Data("broken-v2".utf8),
                legacyData: try JSONEncoder().encode([legacy])
            ),
            .corrupt
        )
    }

    func testMissingV2MigratesValidLegacyOnce() throws {
        let legacy = validSession()

        XCTAssertEqual(
            WorkSessionStorageV2.resolve(
                primaryData: nil,
                legacyData: try JSONEncoder().encode([legacy])
            ),
            .valid([legacy], origin: .legacyMigration)
        )
    }

    func testMissingV2WithCorruptLegacyIsCorrupt() {
        XCTAssertEqual(
            WorkSessionStorageV2.resolve(
                primaryData: nil,
                legacyData: Data("broken-v1".utf8)
            ),
            .corrupt
        )
    }

    func testBothStoragesMissingStayMissing() {
        XCTAssertEqual(
            WorkSessionStorageV2.resolve(primaryData: nil, legacyData: nil),
            .missing
        )
    }

    private func validSession(offset: TimeInterval = 0) -> WorkSession {
        let entry = start.addingTimeInterval(offset)
        return WorkSession(
            id: UUID(),
            entry: entry,
            exit: entry.addingTimeInterval(3_600),
            pauses: [
                PausePeriod(
                    id: UUID(),
                    start: entry.addingTimeInterval(900),
                    end: entry.addingTimeInterval(1_200),
                    paid: true
                )
            ]
        )
    }
}

private final class RejectingPrimaryDefaults: UserDefaults, @unchecked Sendable {
    var rejectPrimaryWrite = false
    override func set(_ value: Any?, forKey defaultName: String) {
        if rejectPrimaryWrite && defaultName == WorkSessionStorageV2.primaryKey { return }
        super.set(value, forKey: defaultName)
    }
}
