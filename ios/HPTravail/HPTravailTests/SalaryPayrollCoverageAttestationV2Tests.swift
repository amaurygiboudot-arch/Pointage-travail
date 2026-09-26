import Foundation
import XCTest
@testable import SalaryV2Contract

final class SalaryPayrollCoverageAttestationV2Tests: XCTestCase {
    private let zone = "Europe/Paris"
    private let startDay: Int64 = 20717
    private let endDay: Int64 = 20723

    func testExactAttestationRemainsValidAndSessionEditInvalidatesIt() throws {
        let check = date(2026, 9, 28, 0, 0)
        let original = source([session("a")])
        let fingerprint = try XCTUnwrap(SalaryPayrollCoverageAttestationPolicyV2.fingerprint(
            work: original, coveredStartEpochDay: startDay, coveredEndEpochDay: endDay, timeZoneId: zone))
        let proof = attestation(fingerprint, checkedAt: check)

        XCTAssertTrue(SalaryPayrollCoverageAttestationPolicyV2.isValid(
            proof, work: original, employerId: "company-a",
            coveredStartEpochDay: startDay, coveredEndEpochDay: endDay,
            timeZoneId: zone, now: check.addingTimeInterval(1)))

        let base = session("a")
        let edited = source([SalarySessionFactV2(
            id: "a", entry: base.entry,
            exit: base.exit?.addingTimeInterval(60),
            employerId: "company-a", pauses: base.pauses)])
        XCTAssertFalse(SalaryPayrollCoverageAttestationPolicyV2.isValid(
            proof, work: edited, employerId: "company-a",
            coveredStartEpochDay: startDay, coveredEndEpochDay: endDay,
            timeZoneId: zone, now: check.addingTimeInterval(1)))
    }

    func testPauseEditAndNewSessionInvalidateFingerprint() throws {
        let originalSession = session("a")
        let original = source([originalSession])
        let first = try XCTUnwrap(SalaryPayrollCoverageAttestationPolicyV2.fingerprint(
            work: original, coveredStartEpochDay: startDay, coveredEndEpochDay: endDay, timeZoneId: zone))
        let changedPause = PaidPauseFactV2(
            start: originalSession.pauses[0].start,
            end: originalSession.pauses[0].end,
            paid: true
        )
        let pauseEdited = source([SalarySessionFactV2(
            id: "a", entry: originalSession.entry, exit: originalSession.exit,
            employerId: "company-a", pauses: [changedPause])])
        let second = SalaryPayrollCoverageAttestationPolicyV2.fingerprint(
            work: pauseEdited, coveredStartEpochDay: startDay, coveredEndEpochDay: endDay, timeZoneId: zone)
        XCTAssertNotEqual(first, second)

        let added = source([originalSession, session("b", dayOffset: 1)])
        let third = SalaryPayrollCoverageAttestationPolicyV2.fingerprint(
            work: added, coveredStartEpochDay: startDay, coveredEndEpochDay: endDay, timeZoneId: zone)
        XCTAssertNotEqual(first, third)
    }

    func testOutOfRangeSessionDoesNotInvalidateFingerprint() {
        let original = source([session("a")])
        let withOutside = source([session("a"), session("outside", dayOffset: 20)])
        XCTAssertEqual(
            SalaryPayrollCoverageAttestationPolicyV2.fingerprint(
                work: original, coveredStartEpochDay: startDay, coveredEndEpochDay: endDay, timeZoneId: zone),
            SalaryPayrollCoverageAttestationPolicyV2.fingerprint(
                work: withOutside, coveredStartEpochDay: startDay, coveredEndEpochDay: endDay, timeZoneId: zone)
        )
    }

    func testStoreRequiresReliableClosedCoverageAndRejectsStaleFacts() throws {
        let suite = "SalaryPayrollCoverageAttestationV2Tests." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }

        let check = date(2026, 9, 28, 0, 0)
        let work = source([session("a")])
        XCTAssertNil(SalaryPayrollCoverageStoreV2.saveConfirmed(
            work: .init(sessions: work.sessions, reliable: false),
            employerId: "company-a", coveredStartEpochDay: startDay, coveredEndEpochDay: endDay,
            checkedAt: check, timeZoneId: zone, now: check, defaults: defaults))

        let saved = SalaryPayrollCoverageStoreV2.saveConfirmed(
            work: work, employerId: "company-a",
            coveredStartEpochDay: startDay, coveredEndEpochDay: endDay,
            checkedAt: check, timeZoneId: zone, now: check, defaults: defaults)
        XCTAssertNotNil(saved)

        let valid = SalaryPayrollCoverageStoreV2.source(
            work: work, employerId: "company-a",
            coveredStartEpochDay: startDay, coveredEndEpochDay: endDay,
            timeZoneId: zone, now: check.addingTimeInterval(1), defaults: defaults)
        XCTAssertTrue(valid.exhaustive)

        let changed = source([session("a"), session("b", dayOffset: 1)])
        let stale = SalaryPayrollCoverageStoreV2.source(
            work: changed, employerId: "company-a",
            coveredStartEpochDay: startDay, coveredEndEpochDay: endDay,
            timeZoneId: zone, now: check.addingTimeInterval(1), defaults: defaults)
        XCTAssertFalse(stale.exhaustive)
        XCTAssertTrue(stale.warnings.contains(SalaryPayrollCoverageAttestationPolicyV2.staleWarning))
    }

    func testCoverageMustBeClosedBeforeConfirmation() {
        let check = date(2026, 9, 28, 0, 0)
        XCTAssertTrue(SalaryPayrollCoverageAttestationPolicyV2.coverageClosedBeforeCheck(
            coveredEndEpochDay: endDay, checkedAt: check, timeZoneId: zone))
        XCTAssertFalse(SalaryPayrollCoverageAttestationPolicyV2.coverageClosedBeforeCheck(
            coveredEndEpochDay: endDay, checkedAt: check.addingTimeInterval(-1), timeZoneId: zone))
    }

    private func attestation(_ fingerprint: String, checkedAt: Date) -> SalaryPayrollCoverageAttestationV2 {
        .init(employerId: "company-a", coveredStartEpochDay: startDay, coveredEndEpochDay: endDay,
              checkedAt: checkedAt, timeZoneId: zone, sourceId: "coverage:test",
              sessionFingerprint: fingerprint)
    }

    private func source(_ sessions: [SalarySessionFactV2]) -> SalaryWorkSessionSourceV2 {
        .init(sessions: sessions, reliable: true)
    }

    private func session(_ id: String, dayOffset: Int = 0) -> SalarySessionFactV2 {
        let entry = date(2026, 9, 22 + dayOffset, 8, 0)
        let exit = date(2026, 9, 22 + dayOffset, 17, 0)
        return .init(id: id, entry: entry, exit: exit, employerId: "company-a", pauses: [
            .init(start: entry.addingTimeInterval(4 * 3600),
                  end: entry.addingTimeInterval(5 * 3600), paid: false)
        ])
    }

    private func date(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: zone)!
        return calendar.date(from: DateComponents(
            year: year, month: month, day: day, hour: hour, minute: minute, second: 0
        ))!
    }
}
