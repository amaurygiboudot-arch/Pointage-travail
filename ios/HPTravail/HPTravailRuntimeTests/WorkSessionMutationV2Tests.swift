import Foundation
import XCTest
@testable import RuntimeV2Contract

final class WorkSessionMutationV2Tests: XCTestCase {
    private let entry = Date(timeIntervalSince1970: 1_800_000_000)

    private func openSession(pauses: [PausePeriod] = []) -> WorkSession {
        WorkSession(id: UUID(), entry: entry, exit: nil, pauses: pauses)
    }

    private func pause(start: TimeInterval, end: TimeInterval?, paid: Bool? = true) -> PausePeriod {
        PausePeriod(
            id: UUID(),
            start: entry.addingTimeInterval(start),
            end: end.map { entry.addingTimeInterval($0) },
            paid: paid
        )
    }

    func testClockRollbackCannotStartPauseBeforeEntry() {
        let original = [openSession()]
        XCTAssertNil(WorkSessionMutationV2.togglingPause(
            in: original, at: entry.addingTimeInterval(-1), paid: false
        ))
        XCTAssertTrue(original[0].pauses.isEmpty)
    }

    func testClockRollbackCannotStartPauseBeforePreviousPauseEnded() {
        let original = [openSession(pauses: [pause(start: 60, end: 120)])]
        XCTAssertNil(WorkSessionMutationV2.togglingPause(
            in: original, at: entry.addingTimeInterval(90), paid: false
        ))
    }

    func testClockRollbackOrSameInstantCannotCloseOpenPause() {
        let original = [openSession(pauses: [pause(start: 60, end: nil)])]
        for offset in [59.0, 60.0] {
            XCTAssertNil(WorkSessionMutationV2.togglingPause(
                in: original, at: entry.addingTimeInterval(offset), paid: nil
            ))
        }
        XCTAssertNil(original[0].pauses[0].end)
    }

    func testExitBeforeCompletedPauseIsRejectedWithoutChangingSession() {
        let original = [openSession(pauses: [pause(start: 60, end: 120)])]
        XCTAssertNil(WorkSessionMutationV2.closingSession(
            in: original, at: entry.addingTimeInterval(90), expectedSessionId: original[0].id
        ))
        XCTAssertNil(original[0].exit)
        XCTAssertEqual(original[0].pauses[0].end, entry.addingTimeInterval(120))
    }

    func testExitAtCompletedPauseEndIsAllowed() throws {
        let original = [openSession(pauses: [pause(start: 60, end: 120)])]
        let closed = try XCTUnwrap(WorkSessionMutationV2.closingSession(
            in: original, at: entry.addingTimeInterval(120), expectedSessionId: original[0].id
        ))
        XCTAssertEqual(closed[0].exit, entry.addingTimeInterval(120))
    }

    func testExitCannotCloseUnknownPauseOrAnotherSession() {
        let unresolved = [openSession(pauses: [pause(start: 60, end: nil, paid: nil)])]
        XCTAssertNil(WorkSessionMutationV2.closingSession(
            in: unresolved, at: entry.addingTimeInterval(120), expectedSessionId: unresolved[0].id
        ))
        let original = [openSession()]
        XCTAssertNil(WorkSessionMutationV2.closingSession(
            in: original, at: entry.addingTimeInterval(120), expectedSessionId: UUID()
        ))
    }

    func testLegacyPauseQualificationAndRetryAfterClockRollback() throws {
        let original = [openSession(pauses: [pause(start: 60, end: nil, paid: nil)])]
        XCTAssertNil(WorkSessionMutationV2.togglingPause(
            in: original, at: entry.addingTimeInterval(59), paid: false
        ))
        let resumed = try XCTUnwrap(WorkSessionMutationV2.togglingPause(
            in: original, at: entry.addingTimeInterval(120), paid: false
        ))
        XCTAssertEqual(resumed[0].pauses[0].paid, false)
        XCTAssertEqual(resumed[0].pauses[0].end, entry.addingTimeInterval(120))
        XCTAssertNil(original[0].pauses[0].paid)
        XCTAssertNil(original[0].pauses[0].end)
    }

    func testClosingOvernightSessionClosesPaidPauseWithoutChangingQualification() throws {
        let original = [openSession(pauses: [pause(start: 25 * 3_600, end: nil, paid: true)])]
        let closed = try XCTUnwrap(WorkSessionMutationV2.closingSession(
            in: original, at: entry.addingTimeInterval(26 * 3_600), expectedSessionId: original[0].id
        ))
        XCTAssertEqual(closed[0].exit, entry.addingTimeInterval(26 * 3_600))
        XCTAssertEqual(closed[0].pauses[0].end, closed[0].exit)
        XCTAssertEqual(closed[0].pauses[0].paid, true)
        XCTAssertNil(original[0].exit)
        XCTAssertNil(original[0].pauses[0].end)
    }

    func testInvalidWritePreservesJournalAndReloadRemainsUsable() throws {
        let suite = "WorkSessionMutationV2Tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let original = [openSession(pauses: [pause(start: 60, end: 120)])]
        XCTAssertTrue(WorkSessionPersistenceV2.write(original, defaults: defaults))
        let originalData = defaults.data(forKey: WorkSessionStorageV2.primaryKey)

        var invalid = original
        invalid[0].exit = entry.addingTimeInterval(90)
        XCTAssertFalse(WorkSessionPersistenceV2.write(invalid, defaults: defaults))
        XCTAssertEqual(defaults.data(forKey: WorkSessionStorageV2.primaryKey), originalData)
        XCTAssertEqual(WorkSessionStorageV2.resolve(
            primaryData: defaults.data(forKey: WorkSessionStorageV2.primaryKey), legacyData: nil
        ), .valid(original, origin: .primary))

        let closed = try XCTUnwrap(WorkSessionMutationV2.closingSession(
            in: original, at: entry.addingTimeInterval(180), expectedSessionId: original[0].id
        ))
        XCTAssertTrue(WorkSessionPersistenceV2.write(closed, defaults: defaults))
        XCTAssertEqual(WorkSessionPersistenceV2.read(
            defaults.data(forKey: WorkSessionStorageV2.primaryKey)
        ), .valid(closed))
    }

    func testNonFiniteDateIsRejectedBeforeAnyWrite() throws {
        let suite = "WorkSessionMutationV2Tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        var invalid = [openSession()]
        invalid[0].entry = Date(timeIntervalSinceReferenceDate: .infinity)
        XCTAssertFalse(WorkSessionPersistenceV2.write(invalid, defaults: defaults))
        XCTAssertNil(defaults.data(forKey: WorkSessionStorageV2.primaryKey))
    }
}
