import Foundation
import XCTest
@testable import SalaryV2Contract

final class SalaryPayrollCoverageAttestationV2Tests: XCTestCase {
    private let zoneId = "Europe/Paris"
    private var weekStart: Int64 { epochDay(2026, 9, 21) }
    private var weekEnd: Int64 { weekStart + 6 }
    private var checkedAt: Date { localDate(2026, 9, 28, 0, 0) }

    func testExactAttestationRemainsValidAndTimeEditInvalidatesIt() throws {
        let original = source([session("a", dayOffset: 0)])
        let proof = try attestation("00000000-0000-0000-0000-000000000001",
                                    start: weekStart, end: weekEnd, work: original)

        XCTAssertTrue(SalaryPayrollCoverageAttestationPolicyV2.isCurrent(
            proof, work: original, now: checkedAt.addingTimeInterval(1)
        ))

        let base = session("a", dayOffset: 0)
        let edited = source([
            SalarySessionFactV2(
                id: base.id,
                entry: base.entry,
                exit: base.exit?.addingTimeInterval(60),
                employerId: base.employerId,
                pauses: base.pauses
            )
        ])
        XCTAssertFalse(SalaryPayrollCoverageAttestationPolicyV2.isCurrent(
            proof, work: edited, now: checkedAt.addingTimeInterval(1)
        ))
    }

    func testPauseEditOrUnassignedSessionInvalidatesCoverage() throws {
        let originalSession = session("a", dayOffset: 0)
        let original = source([originalSession])
        let proof = try attestation("00000000-0000-0000-0000-000000000002",
                                    start: weekStart, end: weekEnd, work: original)

        let changedPause = PaidPauseFactV2(
            start: originalSession.pauses[0].start,
            end: originalSession.pauses[0].end,
            paid: true
        )
        let pauseEdited = source([
            SalarySessionFactV2(
                id: originalSession.id,
                entry: originalSession.entry,
                exit: originalSession.exit,
                employerId: originalSession.employerId,
                pauses: [changedPause]
            )
        ])
        XCTAssertFalse(SalaryPayrollCoverageAttestationPolicyV2.isCurrent(
            proof, work: pauseEdited, now: checkedAt.addingTimeInterval(1)
        ))

        let unassigned = session("unknown", dayOffset: 1, employerId: nil)
        XCTAssertFalse(SalaryPayrollCoverageAttestationPolicyV2.isCurrent(
            proof, work: source([originalSession, unassigned]), now: checkedAt.addingTimeInterval(1)
        ))
    }

    func testOtherEmployerAndOutOfRangeChangesDoNotInvalidateEmployerCoverage() {
        let original = source([session("a", dayOffset: 0)])
        let first = SalaryPayrollCoverageAttestationPolicyV2.fingerprint(
            work: original,
            employerId: "company-a",
            coveredStartEpochDay: weekStart,
            coveredEndEpochDay: weekEnd,
            timeZoneId: zoneId
        )

        let other = session("other", dayOffset: 1, employerId: "company-b")
        let outside = session("outside", dayOffset: 20)
        let second = SalaryPayrollCoverageAttestationPolicyV2.fingerprint(
            work: source([session("a", dayOffset: 0), other, outside]),
            employerId: "company-a",
            coveredStartEpochDay: weekStart,
            coveredEndEpochDay: weekEnd,
            timeZoneId: zoneId
        )

        XCTAssertEqual(first, second)
    }

    func testAdjacentAttestationsCoverRequestedWeek() throws {
        let work = source([
            session("a", dayOffset: 0),
            session("b", dayOffset: 3)
        ])
        let first = try attestation("00000000-0000-0000-0000-000000000003",
                                    start: weekStart, end: weekStart + 2, work: work)
        let second = try attestation("00000000-0000-0000-0000-000000000004",
                                     start: weekStart + 3, end: weekEnd, work: work)

        let result = SalaryPayrollCoverageStoreV2.resolve(
            attestations: [second, first],
            work: work,
            employerId: "company-a",
            requestedStartEpochDay: weekStart,
            requestedEndEpochDay: weekEnd,
            timeZoneId: zoneId,
            now: checkedAt.addingTimeInterval(1)
        )

        XCTAssertTrue(result.reliable)
        XCTAssertTrue(result.exhaustive)
        XCTAssertTrue(result.sourceId.contains(first.id.uuidString))
        XCTAssertTrue(result.sourceId.contains(second.id.uuidString))
        XCTAssertTrue(result.warnings.isEmpty)
    }

    func testGapRemainsNonExhaustive() throws {
        let work = source([session("a", dayOffset: 0)])
        let first = try attestation("00000000-0000-0000-0000-000000000005",
                                    start: weekStart, end: weekStart + 1, work: work)
        let second = try attestation("00000000-0000-0000-0000-000000000006",
                                     start: weekStart + 3, end: weekEnd, work: work)

        let result = SalaryPayrollCoverageStoreV2.resolve(
            attestations: [first, second],
            work: work,
            employerId: "company-a",
            requestedStartEpochDay: weekStart,
            requestedEndEpochDay: weekEnd,
            timeZoneId: zoneId,
            now: checkedAt.addingTimeInterval(1)
        )

        XCTAssertTrue(result.reliable)
        XCTAssertFalse(result.exhaustive)
        XCTAssertTrue(result.warnings.contains(
            SalaryPayrollCoverageAttestationPolicyV2.missingWarning
        ))
    }

    func testStaleAttestationCannotCompleteCoverageChain() throws {
        let original = source([session("a", dayOffset: 0)])
        let first = try attestation("00000000-0000-0000-0000-000000000007",
                                    start: weekStart, end: weekStart + 2, work: original)
        let second = try attestation("00000000-0000-0000-0000-000000000008",
                                     start: weekStart + 3, end: weekEnd, work: original)
        let changed = source([
            session("a", dayOffset: 0),
            session("new", dayOffset: 4)
        ])

        let result = SalaryPayrollCoverageStoreV2.resolve(
            attestations: [first, second],
            work: changed,
            employerId: "company-a",
            requestedStartEpochDay: weekStart,
            requestedEndEpochDay: weekEnd,
            timeZoneId: zoneId,
            now: checkedAt.addingTimeInterval(1)
        )

        XCTAssertTrue(result.reliable)
        XCTAssertFalse(result.exhaustive)
        XCTAssertTrue(result.warnings.contains(
            SalaryPayrollCoverageAttestationPolicyV2.staleWarning
        ))
    }

    func testFutureAttestationMakesResolutionUnreliable() throws {
        let work = source([session("a", dayOffset: 0)])
        let proof = try attestation("00000000-0000-0000-0000-000000000009",
                                    start: weekStart, end: weekEnd, work: work,
                                    checkedAt: checkedAt.addingTimeInterval(10_000))

        let result = SalaryPayrollCoverageStoreV2.resolve(
            attestations: [proof],
            work: work,
            employerId: "company-a",
            requestedStartEpochDay: weekStart,
            requestedEndEpochDay: weekEnd,
            timeZoneId: zoneId,
            now: checkedAt
        )

        XCTAssertFalse(result.reliable)
        XCTAssertFalse(result.exhaustive)
        XCTAssertTrue(result.warnings.contains(
            SalaryPayrollCoverageAttestationPolicyV2.corruptWarning
        ))
    }

    func testCodecRejectsDuplicateIdsAndCorruptPayload() throws {
        let work = source([session("a", dayOffset: 0)])
        let proof = try attestation("00000000-0000-0000-0000-000000000010",
                                    start: weekStart, end: weekEnd, work: work)
        XCTAssertNotNil(SalaryPayrollCoverageStoreV2.encode([proof]))
        XCTAssertNil(SalaryPayrollCoverageStoreV2.encode([proof, proof]))
        XCTAssertFalse(SalaryPayrollCoverageStoreV2.decode(Data("{}".utf8)).reliable)
    }

    func testUserDefaultsRoundTripAndStaleDetection() throws {
        let suite = "SalaryPayrollCoverageAttestationV2Tests." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }

        let work = source([session("a", dayOffset: 0)])
        let saved = SalaryPayrollCoverageStoreV2.saveConfirmed(
            work: work,
            employerId: "company-a",
            coveredStartEpochDay: weekStart,
            coveredEndEpochDay: weekEnd,
            checkedAt: checkedAt,
            timeZoneId: zoneId,
            now: checkedAt,
            defaults: defaults
        )
        XCTAssertNotNil(saved)

        let valid = SalaryPayrollCoverageStoreV2.source(
            work: work,
            employerId: "company-a",
            coveredStartEpochDay: weekStart,
            coveredEndEpochDay: weekEnd,
            timeZoneId: zoneId,
            now: checkedAt.addingTimeInterval(1),
            defaults: defaults
        )
        XCTAssertTrue(valid.exhaustive)

        let changed = source([
            session("a", dayOffset: 0),
            session("b", dayOffset: 2)
        ])
        let stale = SalaryPayrollCoverageStoreV2.source(
            work: changed,
            employerId: "company-a",
            coveredStartEpochDay: weekStart,
            coveredEndEpochDay: weekEnd,
            timeZoneId: zoneId,
            now: checkedAt.addingTimeInterval(1),
            defaults: defaults
        )
        XCTAssertFalse(stale.exhaustive)
        XCTAssertTrue(stale.warnings.contains(
            SalaryPayrollCoverageAttestationPolicyV2.staleWarning
        ))
    }

    func testCoverageMustBeClosedBeforeConfirmation() {
        XCTAssertTrue(SalaryPayrollCoverageAttestationPolicyV2.coverageClosedBeforeCheck(
            coveredEndEpochDay: weekEnd,
            checkedAt: checkedAt,
            timeZoneId: zoneId
        ))
        XCTAssertFalse(SalaryPayrollCoverageAttestationPolicyV2.coverageClosedBeforeCheck(
            coveredEndEpochDay: weekEnd,
            checkedAt: checkedAt.addingTimeInterval(-1),
            timeZoneId: zoneId
        ))
    }

    private func attestation(
        _ id: String,
        start: Int64,
        end: Int64,
        work: SalaryWorkSessionSourceV2,
        checkedAt customCheckedAt: Date? = nil
    ) throws -> SalaryPayrollCoverageAttestationV2 {
        let check = customCheckedAt ?? localStart(epochDay: end + 1)
        let fingerprint = try XCTUnwrap(
            SalaryPayrollCoverageAttestationPolicyV2.fingerprint(
                work: work,
                employerId: "company-a",
                coveredStartEpochDay: start,
                coveredEndEpochDay: end,
                timeZoneId: zoneId
            )
        )
        return .init(
            id: UUID(uuidString: id)!,
            employerId: "company-a",
            coveredStartEpochDay: start,
            coveredEndEpochDay: end,
            checkedAt: check,
            timeZoneId: zoneId,
            sessionFingerprint: fingerprint
        )
    }

    private func source(_ sessions: [SalarySessionFactV2]) -> SalaryWorkSessionSourceV2 {
        .init(sessions: sessions, reliable: true)
    }

    private func session(
        _ id: String,
        dayOffset: Int,
        employerId: String? = "company-a"
    ) -> SalarySessionFactV2 {
        let base = localDate(2026, 9, 22, 8, 0)
        let entry = localCalendar().date(byAdding: .day, value: dayOffset, to: base)!
        let exit = entry.addingTimeInterval(9 * 3600)
        return .init(
            id: id,
            entry: entry,
            exit: exit,
            employerId: employerId,
            pauses: [
                .init(
                    start: entry.addingTimeInterval(4 * 3600),
                    end: entry.addingTimeInterval(5 * 3600),
                    paid: false
                )
            ]
        )
    }

    private func localStart(epochDay: Int64) -> Date {
        let civil = civilDate(epochDay)
        return localDate(civil.0, civil.1, civil.2, 0, 0)
    }

    private func localDate(
        _ year: Int,
        _ month: Int,
        _ day: Int,
        _ hour: Int,
        _ minute: Int
    ) -> Date {
        localCalendar().date(from: DateComponents(
            year: year, month: month, day: day, hour: hour, minute: minute, second: 0
        ))!
    }

    private func localCalendar() -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: zoneId)!
        calendar.locale = Locale(identifier: "en_US_POSIX")
        return calendar
    }

    private func epochDay(_ year: Int, _ month: Int, _ day: Int) -> Int64 {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let date = calendar.date(from: DateComponents(year: year, month: month, day: day))!
        return Int64(date.timeIntervalSince1970 / 86_400)
    }

    private func civilDate(_ epochDay: Int64) -> (Int, Int, Int) {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let date = Date(timeIntervalSince1970: Double(epochDay) * 86_400)
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return (parts.year!, parts.month!, parts.day!)
    }
}
