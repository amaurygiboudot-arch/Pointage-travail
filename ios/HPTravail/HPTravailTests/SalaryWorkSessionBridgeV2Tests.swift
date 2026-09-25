import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
@testable import RuntimeV2Contract
#endif

final class SalaryWorkSessionBridgeV2Tests: XCTestCase {
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }

    private func date(_ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(
            calendar: calendar,
            timeZone: calendar.timeZone,
            year: 2026,
            month: 9,
            day: day,
            hour: hour,
            minute: minute
        ))!
    }

    func testBridgeCopiesEmployerAndPauseFactsExactly() {
        let sessionId = UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
        let session = WorkSession(
            id: sessionId,
            entry: date(14, 8),
            exit: date(14, 16),
            pauses: [
                PausePeriod(
                    id: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!,
                    start: date(14, 12),
                    end: date(14, 12, 30),
                    paid: false
                )
            ],
            employerId: "company-a",
            placeLabel: "Site A"
        )

        let source = SalaryWorkSessionBridgeV2.source(
            from: [session],
            storageReliable: true
        )

        XCTAssertTrue(source.reliable)
        XCTAssertEqual(source.sessions.count, 1)
        XCTAssertEqual(source.sessions[0].id, sessionId.uuidString)
        XCTAssertEqual(source.sessions[0].entry, session.entry)
        XCTAssertEqual(source.sessions[0].exit, session.exit)
        XCTAssertEqual(source.sessions[0].employerId, "company-a")
        XCTAssertEqual(source.sessions[0].pauses.count, 1)
        XCTAssertEqual(source.sessions[0].pauses[0].start, date(14, 12))
        XCTAssertEqual(source.sessions[0].pauses[0].end, date(14, 12, 30))
        XCTAssertEqual(source.sessions[0].pauses[0].paid, false)
    }

    func testBridgeNeverAssignsSelectedEmployerToUnassignedSession() {
        let unassigned = WorkSession(
            id: UUID(),
            entry: date(15, 8),
            exit: date(15, 10),
            pauses: [],
            employerId: nil,
            placeLabel: nil
        )
        let employerB = WorkSession(
            id: UUID(),
            entry: date(15, 10),
            exit: date(15, 13),
            pauses: [],
            employerId: "company-b",
            placeLabel: nil
        )
        let employerA = WorkSession(
            id: UUID(),
            entry: date(15, 13),
            exit: date(15, 14),
            pauses: [],
            employerId: "company-a",
            placeLabel: nil
        )

        let source = SalaryWorkSessionBridgeV2.source(
            from: [unassigned, employerB, employerA],
            storageReliable: true
        )
        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: source.sessions,
            employerId: "company-a",
            period: YearMonthV2(year: 2026, month: 9)!,
            sourceReliable: source.reliable,
            calendar: calendar
        )

        XCTAssertNil(source.sessions[0].employerId)
        XCTAssertEqual(source.sessions[1].employerId, "company-b")
        XCTAssertEqual(source.sessions[2].employerId, "company-a")
        XCTAssertFalse(result.reliable)
        XCTAssertEqual(result.completedSessionCount, 1)
        XCTAssertEqual(result.totalPaidMinutes, 60)
        XCTAssertTrue(result.warnings.contains(SalaryPaidWorkAggregatorV2.unassignedEmployerWarning))
    }

    func testBridgePropagatesUnreliableRuntimeStorageWithoutHidingFacts() {
        let session = WorkSession(
            id: UUID(),
            entry: date(16, 8),
            exit: date(16, 9),
            pauses: [],
            employerId: "company-a",
            placeLabel: nil
        )

        let source = SalaryWorkSessionBridgeV2.source(
            from: [session],
            storageReliable: false
        )
        let result = SalaryPaidWorkAggregatorV2.aggregate(
            sessions: source.sessions,
            employerId: "company-a",
            period: YearMonthV2(year: 2026, month: 9)!,
            sourceReliable: source.reliable,
            calendar: calendar
        )

        XCTAssertFalse(source.reliable)
        XCTAssertEqual(source.sessions.count, 1)
        XCTAssertFalse(result.reliable)
        XCTAssertEqual(result.totalPaidMinutes, 60)
        XCTAssertTrue(result.warnings.contains(SalaryPaidWorkAggregatorV2.sourceWarning))
    }

    func testConfirmedCoverageMakesSegmentedSourceExhaustive() throws {
        let sessions = [coverageSession(day: 14)]
        let start = epochDay(14)
        let end = epochDay(20)
        let attestation = try coverageAttestation(sessions: sessions, start: start, end: end)

        let source = SalaryRuntimePayrollCoverageV2.sourceFrom(
            sessions: sessions,
            storageReliable: true,
            attestation: attestation,
            employerId: "company-a",
            coveredStartEpochDay: start,
            coveredEndEpochDay: end,
            timeZoneId: "UTC",
            now: date(22, 0)
        )

        XCTAssertTrue(source.work.reliable)
        XCTAssertTrue(source.exhaustive)
        XCTAssertEqual(source.sourceId, attestation.sourceId)
        XCTAssertTrue(source.warnings.isEmpty)
    }

    func testMissingCoverageNeverTurnsReliableStorageIntoExhaustiveHistory() {
        let start = epochDay(14)
        let end = epochDay(20)
        let source = SalaryRuntimePayrollCoverageV2.sourceFrom(
            sessions: [coverageSession(day: 14)],
            storageReliable: true,
            attestation: nil,
            employerId: "company-a",
            coveredStartEpochDay: start,
            coveredEndEpochDay: end,
            timeZoneId: "UTC",
            now: date(22, 0)
        )

        XCTAssertTrue(source.work.reliable)
        XCTAssertFalse(source.exhaustive)
        XCTAssertTrue(source.warnings.contains(SalaryRuntimePayrollCoverageV2.missingAttestationWarning))
    }

    func testChangingPauseInsideCoverageInvalidatesAttestation() throws {
        let start = epochDay(14)
        let end = epochDay(20)
        let original = [coverageSession(day: 14)]
        let attestation = try coverageAttestation(sessions: original, start: start, end: end)
        var changed = original[0]
        changed.pauses = [
            PausePeriod(
                id: UUID(),
                start: date(14, 12),
                end: date(14, 12, 30),
                paid: false
            )
        ]

        let source = SalaryRuntimePayrollCoverageV2.sourceFrom(
            sessions: [changed],
            storageReliable: true,
            attestation: attestation,
            employerId: "company-a",
            coveredStartEpochDay: start,
            coveredEndEpochDay: end,
            timeZoneId: "UTC",
            now: date(22, 0)
        )

        XCTAssertFalse(source.exhaustive)
        XCTAssertTrue(source.warnings.contains(SalaryRuntimePayrollCoverageV2.staleAttestationWarning))
    }

    func testSessionOutsideCoverageDoesNotInvalidatePastAttestation() throws {
        let start = epochDay(14)
        let end = epochDay(20)
        let original = [coverageSession(day: 14)]
        let attestation = try coverageAttestation(sessions: original, start: start, end: end)

        let source = SalaryRuntimePayrollCoverageV2.sourceFrom(
            sessions: original + [coverageSession(day: 28)],
            storageReliable: true,
            attestation: attestation,
            employerId: "company-a",
            coveredStartEpochDay: start,
            coveredEndEpochDay: end,
            timeZoneId: "UTC",
            now: date(29, 0)
        )

        XCTAssertTrue(source.exhaustive)
    }

    func testUnreliableStorageAlwaysBlocksExhaustivity() throws {
        let start = epochDay(14)
        let end = epochDay(20)
        let sessions = [coverageSession(day: 14)]
        let attestation = try coverageAttestation(sessions: sessions, start: start, end: end)

        let source = SalaryRuntimePayrollCoverageV2.sourceFrom(
            sessions: sessions,
            storageReliable: false,
            attestation: attestation,
            employerId: "company-a",
            coveredStartEpochDay: start,
            coveredEndEpochDay: end,
            timeZoneId: "UTC",
            now: date(22, 0)
        )

        XCTAssertFalse(source.work.reliable)
        XCTAssertFalse(source.exhaustive)
    }

    private func coverageSession(day: Int) -> WorkSession {
        WorkSession(
            id: UUID(),
            entry: date(day, 8),
            exit: date(day, 16),
            pauses: [],
            employerId: "company-a",
            placeLabel: "Site"
        )
    }

    private func epochDay(_ day: Int) -> Int64 {
        Int64(floor(date(day, 0).timeIntervalSince1970 / 86_400))
    }

    private func coverageAttestation(
        sessions: [WorkSession],
        start: Int64,
        end: Int64
    ) throws -> SalaryPayrollCoverageAttestationV2 {
        SalaryPayrollCoverageAttestationV2(
            sourceId: "coverage-test",
            employerId: "company-a",
            coveredStartEpochDay: start,
            coveredEndEpochDay: end,
            checkedAt: date(21, 23),
            timeZoneId: "UTC",
            snapshotId: try XCTUnwrap(
                SalaryRuntimePayrollCoverageV2.snapshotId(
                    sessions: sessions,
                    coveredStartEpochDay: start,
                    coveredEndEpochDay: end,
                    timeZoneId: "UTC"
                )
            )
        )
    }
}
