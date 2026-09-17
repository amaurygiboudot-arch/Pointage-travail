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
        let session = WorkSession(
            id: UUID(),
            entry: start,
            exit: start.addingTimeInterval(3_600),
            pauses: [
                PausePeriod(
                    id: UUID(),
                    start: start.addingTimeInterval(900),
                    end: start.addingTimeInterval(1_200),
                    paid: true
                )
            ]
        )

        XCTAssertEqual(
            WorkSessionPersistenceV2.read(try JSONEncoder().encode([session])),
            .valid([session])
        )
    }
}
