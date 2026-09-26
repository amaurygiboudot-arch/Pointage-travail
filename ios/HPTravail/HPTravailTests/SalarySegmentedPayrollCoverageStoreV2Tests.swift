import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedPayrollCoverageStoreV2Tests: XCTestCase {
    private let zone = "UTC"
    private let now = Date(timeIntervalSince1970: 30 * 86_400 + 12 * 3_600)

    func testReliableRuntimeWithoutAttestationNeverBecomesExhaustive() {
        let work = source([session("s", day: 4, hour: 8, endDay: 4, endHour: 16)])
        let result = SalarySegmentedPayrollCoverageStoreV2.resolveSource(
            work: work,
            coverage: .init(attestations: [], reliable: true, warnings: []),
            employerId: "company",
            requiredStartEpochDay: 4,
            requiredEndEpochDay: 10,
            timeZoneId: zone,
            now: now
        )

        XCTAssertTrue(result.work.reliable)
        XCTAssertFalse(result.exhaustive)
        XCTAssertTrue(result.warnings.contains(SalarySegmentedPayrollCoverageStoreV2.missingWarning))
    }

    func testMatchingAttestationProducesExhaustiveSource() throws {
        let work = source([session("s", day: 4, hour: 8, endDay: 4, endHour: 16)])
        let item = try attestation("a", start: 4, end: 10, work: work)
        let result = resolve(work: work, items: [item], start: 4, end: 10)

        XCTAssertTrue(result.exhaustive)
        XCTAssertEqual(result.coveredStartEpochDay, 4)
        XCTAssertEqual(result.coveredEndEpochDay, 10)
        XCTAssertTrue(result.sourceId.hasPrefix("coverage-"))
    }

    func testEditInsideCoveredRangeInvalidatesAttestation() throws {
        let original = source([session("s", day: 4, hour: 8, endDay: 4, endHour: 16)])
        let item = try attestation("a", start: 4, end: 10, work: original)
        let edited = source([session("s", day: 4, hour: 8, endDay: 4, endHour: 17)])
        let result = resolve(work: edited, items: [item], start: 4, end: 10)

        XCTAssertFalse(result.exhaustive)
        XCTAssertTrue(result.warnings.contains(SalarySegmentedPayrollCoverageStoreV2.staleWarning))
    }

    func testNewSessionOutsideCoveredRangeDoesNotInvalidateAttestation() throws {
        let original = source([session("inside", day: 4, hour: 8, endDay: 4, endHour: 16)])
        let item = try attestation("a", start: 4, end: 10, work: original)
        let expanded = source(original.sessions + [session("outside", day: 20, hour: 8, endDay: 20, endHour: 16)])

        XCTAssertTrue(resolve(work: expanded, items: [item], start: 4, end: 10).exhaustive)
    }

    func testAdjacentAttestationsCoverWithoutGap() throws {
        let work = source([
            session("s1", day: 4, hour: 8, endDay: 4, endHour: 16),
            session("s2", day: 11, hour: 8, endDay: 11, endHour: 16)
        ])
        let first = try attestation("a", start: 4, end: 10, work: work)
        let second = try attestation("b", start: 11, end: 17, work: work)

        let result = resolve(work: work, items: [first, second], start: 4, end: 17)
        XCTAssertTrue(result.exhaustive)
        XCTAssertEqual(result.coveredStartEpochDay, 4)
        XCTAssertEqual(result.coveredEndEpochDay, 17)
    }

    func testMissingDayKeepsCoverageNonExhaustive() throws {
        let work = source([session("s", day: 4, hour: 8, endDay: 4, endHour: 16)])
        let first = try attestation("a", start: 4, end: 9, work: work)
        let second = try attestation("b", start: 11, end: 17, work: work)

        let result = resolve(work: work, items: [first, second], start: 4, end: 17)
        XCTAssertFalse(result.exhaustive)
        XCTAssertTrue(result.warnings.contains(SalarySegmentedPayrollCoverageStoreV2.missingWarning))
    }

    func testOtherEmployerDoesNotProveCoverage() throws {
        let work = source([session("s", day: 4, hour: 8, endDay: 4, endHour: 16)])
        let item = try attestation("a", start: 4, end: 10, work: work)
        let other = SalarySegmentedPayrollCoverageAttestationV2(
            id: item.id,
            employerId: "other",
            coveredStartEpochDay: item.coveredStartEpochDay,
            coveredEndEpochDay: item.coveredEndEpochDay,
            confirmedAt: item.confirmedAt,
            timeZoneId: item.timeZoneId,
            factFingerprint: item.factFingerprint
        )

        XCTAssertFalse(resolve(work: work, items: [other], start: 4, end: 10).exhaustive)
    }

    func testOpenSessionCannotBeFingerprinted() {
        let open = SalarySessionFactV2(
            id: "s",
            entry: date(4, 8),
            exit: nil,
            employerId: "company",
            pauses: []
        )

        XCTAssertNil(
            SalarySegmentedPayrollCoverageStoreV2.fingerprint(
                sessions: [open],
                startEpochDay: 4,
                endEpochDay: 10,
                timeZoneId: zone,
                openEnd: now
            )
        )
    }

    func testPausePaidStatusChangeInvalidatesFingerprint() {
        let original = SalarySessionFactV2(
            id: "s",
            entry: date(4, 8),
            exit: date(4, 16),
            employerId: "company",
            pauses: [.init(start: date(4, 12), end: date(4, 12, 30), paid: false)]
        )
        let changed = SalarySessionFactV2(
            id: "s",
            entry: original.entry,
            exit: original.exit,
            employerId: original.employerId,
            pauses: [.init(start: date(4, 12), end: date(4, 12, 30), paid: true)]
        )

        let before = SalarySegmentedPayrollCoverageStoreV2.fingerprint(
            sessions: [original], startEpochDay: 4, endEpochDay: 10, timeZoneId: zone, openEnd: now
        )
        let after = SalarySegmentedPayrollCoverageStoreV2.fingerprint(
            sessions: [changed], startEpochDay: 4, endEpochDay: 10, timeZoneId: zone, openEnd: now
        )
        XCTAssertNotNil(before)
        XCTAssertNotEqual(before, after)
    }

    func testCodecRoundTripAndCorruption() throws {
        let work = source([session("s", day: 4, hour: 8, endDay: 4, endHour: 16)])
        let item = try attestation("a", start: 4, end: 10, work: work)
        let data = try XCTUnwrap(SalarySegmentedPayrollCoverageStoreV2.encode([item]))
        let decoded = SalarySegmentedPayrollCoverageStoreV2.decode(data)

        XCTAssertTrue(decoded.reliable)
        XCTAssertEqual(decoded.attestations, [item])
        XCTAssertFalse(SalarySegmentedPayrollCoverageStoreV2.decode(Data("bad".utf8)).reliable)
    }

    func testUnreliableRuntimeNeverPublishesExhaustiveCoverage() throws {
        let reliable = source([session("s", day: 4, hour: 8, endDay: 4, endHour: 16)])
        let item = try attestation("a", start: 4, end: 10, work: reliable)
        let unreliable = SalaryWorkSessionSourceV2(sessions: reliable.sessions, reliable: false)
        let result = resolve(work: unreliable, items: [item], start: 4, end: 10)

        XCTAssertFalse(result.work.reliable)
        XCTAssertFalse(result.exhaustive)
    }

    private func resolve(
        work: SalaryWorkSessionSourceV2,
        items: [SalarySegmentedPayrollCoverageAttestationV2],
        start: Int64,
        end: Int64
    ) -> SalarySegmentedPayrollSessionSourceV2 {
        SalarySegmentedPayrollCoverageStoreV2.resolveSource(
            work: work,
            coverage: .init(attestations: items, reliable: true, warnings: []),
            employerId: "company",
            requiredStartEpochDay: start,
            requiredEndEpochDay: end,
            timeZoneId: zone,
            now: now
        )
    }

    private func attestation(
        _ id: String,
        start: Int64,
        end: Int64,
        work: SalaryWorkSessionSourceV2
    ) throws -> SalarySegmentedPayrollCoverageAttestationV2 {
        let fingerprint = try XCTUnwrap(
            SalarySegmentedPayrollCoverageStoreV2.fingerprint(
                sessions: work.sessions,
                startEpochDay: start,
                endEpochDay: end,
                timeZoneId: zone,
                openEnd: now
            )
        )
        return .init(
            id: id,
            employerId: "company",
            coveredStartEpochDay: start,
            coveredEndEpochDay: end,
            confirmedAt: now,
            timeZoneId: zone,
            factFingerprint: fingerprint
        )
    }

    private func source(_ sessions: [SalarySessionFactV2]) -> SalaryWorkSessionSourceV2 {
        .init(sessions: sessions, reliable: true)
    }

    private func session(
        _ id: String,
        day: Int64,
        hour: Int,
        endDay: Int64,
        endHour: Int
    ) -> SalarySessionFactV2 {
        .init(
            id: id,
            entry: date(day, hour),
            exit: date(endDay, endHour),
            employerId: "company",
            pauses: []
        )
    }

    private func date(_ epochDay: Int64, _ hour: Int, _ minute: Int = 0) -> Date {
        Date(timeIntervalSince1970: Double(epochDay) * 86_400 + Double(hour * 3_600 + minute * 60))
    }
}
