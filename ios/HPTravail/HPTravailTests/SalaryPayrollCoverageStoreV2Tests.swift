import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryPayrollCoverageStoreV2Tests: XCTestCase {
    private let zone = "Europe/Paris"
    private let monday: Int64 = 20_622

    func testGapOfOneDayNeverProvesCompletePeriod() {
        let result = SalaryPayrollCoverageStoreV2.resolve(
            attestations: [attestation("a", monday, monday + 2),
                           attestation("b", monday + 4, monday + 6)],
            work: .init(sessions: [], reliable: true),
            employerId: "company", requestedStartEpochDay: monday,
            requestedEndEpochDay: monday + 6, timeZoneId: zone, nowMs: nowMs)
        XCTAssertTrue(result.reliable)
        XCTAssertFalse(result.exhaustive)
        XCTAssertTrue(result.warnings.contains(SalaryPayrollCoveragePolicyV2.missingWarning))
    }

    func testContiguousRangesCanProveCompletePeriod() {
        let result = SalaryPayrollCoverageStoreV2.resolve(
            attestations: [attestation("a", monday, monday + 2),
                           attestation("b", monday + 3, monday + 6)],
            work: .init(sessions: [], reliable: true),
            employerId: "company", requestedStartEpochDay: monday,
            requestedEndEpochDay: monday + 6, timeZoneId: zone, nowMs: nowMs)
        XCTAssertTrue(result.reliable)
        XCTAssertTrue(result.exhaustive)
        XCTAssertTrue(result.sourceId.hasPrefix("coverage-v1:"))
    }

    func testChangedSessionInvalidatesAttestation() {
        let original = [session("s", day: monday, endHour: 16)]
        let fingerprint = SalaryPayrollCoveragePolicyV2.fingerprint(
            sessions: original, employerId: "company",
            coveredStartEpochDay: monday, coveredEndEpochDay: monday + 6,
            timeZoneId: zone)!
        let item = SalaryPayrollCoverageAttestationV2(
            id: "a", employerId: "company", coveredStartEpochDay: monday,
            coveredEndEpochDay: monday + 6, checkedAtMs: nowMs - 1000,
            timeZoneId: zone, sessionFingerprint: fingerprint)
        let changed = [session("s", day: monday, endHour: 17)]
        let result = SalaryPayrollCoverageStoreV2.resolve(
            attestations: [item], work: .init(sessions: changed, reliable: true),
            employerId: "company", requestedStartEpochDay: monday,
            requestedEndEpochDay: monday + 6, timeZoneId: zone, nowMs: nowMs)
        XCTAssertTrue(result.reliable)
        XCTAssertFalse(result.exhaustive)
        XCTAssertTrue(result.warnings.contains(SalaryPayrollCoveragePolicyV2.staleWarning))
    }

    func testChangedPauseInvalidatesAttestation() {
        let original = [session("s", day: monday, endHour: 16)]
        let fingerprint = SalaryPayrollCoveragePolicyV2.fingerprint(
            sessions: original, employerId: "company",
            coveredStartEpochDay: monday, coveredEndEpochDay: monday + 6,
            timeZoneId: zone)!
        let item = SalaryPayrollCoverageAttestationV2(
            id: "a", employerId: "company", coveredStartEpochDay: monday,
            coveredEndEpochDay: monday + 6, checkedAtMs: nowMs - 1000,
            timeZoneId: zone, sessionFingerprint: fingerprint)
        let changed = [SalarySessionFactV2(
            id: "s", entry: date(monday, 8), exit: date(monday, 16),
            employerId: "company",
            pauses: [PaidPauseFactV2(start: date(monday, 12),
                                     end: date(monday, 12, 30), paid: false)])]
        XCTAssertFalse(SalaryPayrollCoveragePolicyV2.current(item, sessions: changed, nowMs: nowMs))
    }

    func testUnfinishedWeekCannotBeCertified() {
        let end = monday + 6
        let midday = Int64((date(end, 12).timeIntervalSince1970 * 1000).rounded())
        XCTAssertFalse(SalaryPayrollCoveragePolicyV2.coverageClosedBeforeCheck(
            coveredEndEpochDay: end, checkedAtMs: midday, timeZoneId: zone))
    }

    func testStoreRoundTripAndSourceUsesPersistedProof() throws {
        let suite = "SalaryPayrollCoverageStoreV2Tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let work = SalaryWorkSessionSourceV2(sessions: [], reliable: true)
        let checked = date(monday + 7, 12)
        XCTAssertNotNil(SalaryPayrollCoverageStoreV2.saveConfirmed(
            defaults: defaults, work: work, employerId: "company",
            coveredStartEpochDay: monday, coveredEndEpochDay: monday + 6,
            checkedAt: checked, timeZoneId: zone, now: checked))
        let source = SalaryPayrollCoverageStoreV2.source(
            defaults: defaults, work: work, employerId: "company",
            coveredStartEpochDay: monday, coveredEndEpochDay: monday + 6,
            timeZoneId: zone, now: checked)
        XCTAssertTrue(source.exhaustive)
        XCTAssertTrue(source.sourceId.hasPrefix("coverage-v1:"))
    }

    private var nowMs: Int64 {
        Int64((date(monday + 8, 12).timeIntervalSince1970 * 1000).rounded())
    }

    private func attestation(_ id: String, _ start: Int64, _ end: Int64)
        -> SalaryPayrollCoverageAttestationV2 {
        let fp = SalaryPayrollCoveragePolicyV2.fingerprint(
            sessions: [], employerId: "company", coveredStartEpochDay: start,
            coveredEndEpochDay: end, timeZoneId: zone)!
        return .init(id: id, employerId: "company", coveredStartEpochDay: start,
                     coveredEndEpochDay: end, checkedAtMs: nowMs - 1000,
                     timeZoneId: zone, sessionFingerprint: fp)
    }

    private func session(_ id: String, day: Int64, endHour: Int) -> SalarySessionFactV2 {
        .init(id: id, entry: date(day, 8), exit: date(day, endHour),
              employerId: "company", pauses: [])
    }

    private func date(_ day: Int64, _ hour: Int, _ minute: Int = 0) -> Date {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        var c = utc.dateComponents([.year, .month, .day],
            from: Date(timeIntervalSince1970: Double(day) * 86_400))
        c.hour = hour; c.minute = minute; c.second = 0
        var local = Calendar(identifier: .gregorian)
        local.timeZone = TimeZone(identifier: zone)!
        return local.date(from: c)!
    }
}
