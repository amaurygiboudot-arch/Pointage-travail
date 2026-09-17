import Foundation
import XCTest
@testable import RuntimeV2Contract

final class WorkSessionPersistenceV2Tests: XCTestCase {
    private let start = Date(timeIntervalSinceReferenceDate: 1_000)

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

        guard case .valid(let sessions) = WorkSessionPersistenceV2.read(
            try JSONEncoder().encode([session])
        ) else {
            return XCTFail("Expected a valid legacy session")
        }
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
            .valid([v2], migratedFromLegacy: false)
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
            .valid([legacy], migratedFromLegacy: true)
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
