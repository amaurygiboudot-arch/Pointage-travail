import Foundation
import XCTest
@testable import RuntimeV2Contract

final class WorkSessionPersistenceV2Tests: XCTestCase {
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
