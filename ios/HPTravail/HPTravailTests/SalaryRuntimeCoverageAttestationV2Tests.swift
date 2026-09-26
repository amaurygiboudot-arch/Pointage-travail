import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
import RuntimeV2Contract
#endif

final class SalaryRuntimeCoverageAttestationV2Tests: XCTestCase {
    private let zone = "Europe/Paris"
    private let start = epochDay(2026, 9, 21)
    private lazy var end = start + 6
    private lazy var checkedAt = date(end + 1, 0).addingTimeInterval(1)

    func testExplicitEmptyCoverageCanBeCertified() throws {
        let a = try XCTUnwrap(create([]))
        XCTAssertTrue(SalaryRuntimeCoverageAttestationPolicyV2.validate(a, sessions: [], now: checkedAt.addingTimeInterval(1)))
    }

    func testBlankProvenanceCannotCreateCoverage() {
        XCTAssertNil(SalaryRuntimeCoverageAttestationPolicyV2.create(
            sessions: [], sourceId: "", coveredStartEpochDay: start, coveredEndEpochDay: end,
            checkedAt: checkedAt, timeZoneId: zone, now: checkedAt.addingTimeInterval(1)))
    }

    func testChangedPauseInvalidatesCoverage() throws {
        let initial = [session("00000000-0000-0000-0000-000000000001")]
        let a = try XCTUnwrap(create(initial))
        var changed = initial
        changed[0].pauses = [PausePeriod(id: UUID(), start: date(start, 12),
                                          end: date(start, 12, 30), paid: false)]
        XCTAssertFalse(SalaryRuntimeCoverageAttestationPolicyV2.validate(a, sessions: changed,
                                                                          now: checkedAt.addingTimeInterval(1)))
    }

    func testAddedSessionInsideCoverageInvalidatesCoverage() throws {
        let initial = [session("00000000-0000-0000-0000-000000000001")]
        let a = try XCTUnwrap(create(initial))
        let added = session("00000000-0000-0000-0000-000000000002", day: start + 1)
        XCTAssertFalse(SalaryRuntimeCoverageAttestationPolicyV2.validate(a, sessions: initial + [added],
                                                                          now: checkedAt.addingTimeInterval(1)))
    }

    func testFutureSessionDoesNotInvalidatePastCoverage() throws {
        let initial = [session("00000000-0000-0000-0000-000000000001")]
        let a = try XCTUnwrap(create(initial))
        let future = session("00000000-0000-0000-0000-000000000003", day: end + 2)
        XCTAssertTrue(SalaryRuntimeCoverageAttestationPolicyV2.validate(a, sessions: initial + [future],
                                                                         now: checkedAt.addingTimeInterval(300_000)))
    }

    func testOpenSessionInsideCoverageCannotBeCertified() {
        var open = session("00000000-0000-0000-0000-000000000001")
        open.exit = nil
        XCTAssertNil(create([open]))
    }

    func testStoreRejectsStaleAttestationAfterMutation() throws {
        let suite = "coverage-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let initial = [session("00000000-0000-0000-0000-000000000001")]
        XCTAssertTrue(SalaryRuntimeCoverageAttestationStoreV2.confirm(
            defaults: defaults, sessions: initial, storageReliable: true, sourceId: "explicit-check",
            coveredStartEpochDay: start, coveredEndEpochDay: end, checkedAt: checkedAt,
            timeZoneId: zone, now: checkedAt.addingTimeInterval(1)))
        XCTAssertNotNil(SalaryRuntimeCoverageAttestationStoreV2.read(
            defaults: defaults, sessions: initial, storageReliable: true, now: checkedAt.addingTimeInterval(1)))
        XCTAssertNil(SalaryRuntimeCoverageAttestationStoreV2.read(
            defaults: defaults, sessions: initial + [session("00000000-0000-0000-0000-000000000002", day: start + 1)],
            storageReliable: true, now: checkedAt.addingTimeInterval(1)))
    }

    private func create(_ sessions: [WorkSession]) -> SalaryRuntimeCoverageAttestationV2? {
        SalaryRuntimeCoverageAttestationPolicyV2.create(
            sessions: sessions, sourceId: "explicit-check", coveredStartEpochDay: start,
            coveredEndEpochDay: end, checkedAt: checkedAt, timeZoneId: zone,
            now: checkedAt.addingTimeInterval(1))
    }

    private func session(_ rawId: String, day: Int64? = nil) -> WorkSession {
        WorkSession(id: UUID(uuidString: rawId)!, entry: date(day ?? start, 8),
                    exit: date(day ?? start, 16), pauses: [], employerId: "company")
    }

    private func date(_ day: Int64, _ hour: Int, _ minute: Int = 0) -> Date {
        var utc = Calendar(identifier: .gregorian); utc.timeZone = TimeZone(secondsFromGMT: 0)!
        var parts = utc.dateComponents([.year, .month, .day],
            from: Date(timeIntervalSince1970: Double(day) * 86400))
        parts.hour = hour; parts.minute = minute; parts.second = 0
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: zone)!
        return calendar.date(from: parts)!
    }

    private func epochDay(_ year: Int, _ month: Int, _ day: Int) -> Int64 {
        var utc = Calendar(identifier: .gregorian); utc.timeZone = TimeZone(secondsFromGMT: 0)!
        return Int64(utc.date(from: DateComponents(year: year, month: month, day: day))!.timeIntervalSince1970 / 86400)
    }
}
