import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
@testable import RuntimeV2Contract
#endif

final class SalaryWorkCoverageAttestationV2Tests: XCTestCase {
    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(secondsFromGMT: 0)!
        return value
    }

    private func date(_ day: Int, _ hour: Int = 0) -> Date {
        calendar.date(from: DateComponents(
            year: 2026, month: 9, day: day, hour: hour
        ))!
    }

    private func defaults() -> UserDefaults {
        let suite = "SalaryWorkCoverageAttestationV2Tests.\(UUID().uuidString)"
        let value = UserDefaults(suiteName: suite)!
        value.removePersistentDomain(forName: suite)
        return value
    }

    func testFingerprintIgnoresSessionOrderButNotFacts() {
        let a = session("00000000-0000-0000-0000-000000000001", 1)
        let b = session("00000000-0000-0000-0000-000000000002", 2)
        XCTAssertEqual(
            SalaryWorkCoverageStoreV2.fingerprint([a, b]),
            SalaryWorkCoverageStoreV2.fingerprint([b, a])
        )
        var changed = a
        changed.employerId = "other"
        XCTAssertNotEqual(
            SalaryWorkCoverageStoreV2.fingerprint([a]),
            SalaryWorkCoverageStoreV2.fingerprint([changed])
        )
    }

    func testPauseCorrectionInvalidatesFingerprint() {
        var original = session("00000000-0000-0000-0000-000000000001", 1)
        original.pauses = [
            PausePeriod(
                id: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!,
                start: date(1, 12),
                end: date(1, 13),
                paid: false
            )
        ]
        var corrected = original
        corrected.pauses[0].paid = true
        XCTAssertNotEqual(
            SalaryWorkCoverageStoreV2.fingerprint([original]),
            SalaryWorkCoverageStoreV2.fingerprint([corrected])
        )
    }

    func testExplicitAttestationResolvesAndMutationInvalidatesIt() {
        let defaults = defaults()
        let sessions = [session("00000000-0000-0000-0000-000000000001", 1)]
        let start: Int64 = 20_331 // 2025-09-?? exact civil value is irrelevant to selection
        let end = start + 6
        let checkedAt = Date(timeIntervalSince1970: Double(end + 1) * 86_400 + 12 * 3_600)

        XCTAssertTrue(
            SalaryWorkCoverageStoreV2.confirmCoverage(
                defaults: defaults,
                sessions: sessions,
                storageReliable: true,
                employerId: "company",
                coveredStartEpochDay: start,
                coveredEndEpochDay: end,
                checkedAt: checkedAt,
                timeZoneId: "UTC",
                sourceId: "explicit-proof",
                now: checkedAt.addingTimeInterval(1)
            )
        )
        let resolved = SalaryWorkCoverageStoreV2.resolve(
            defaults: defaults,
            sessions: sessions,
            storageReliable: true,
            employerId: "company",
            requestedStartEpochDay: start,
            requestedEndEpochDay: end,
            timeZoneId: "UTC",
            now: checkedAt.addingTimeInterval(1)
        )
        XCTAssertTrue(resolved.exhaustive)
        XCTAssertEqual(resolved.sourceId, "explicit-proof")

        var changed = sessions[0]
        changed.placeLabel = "Changed"
        let invalidated = SalaryWorkCoverageStoreV2.resolve(
            defaults: defaults,
            sessions: [changed],
            storageReliable: true,
            employerId: "company",
            requestedStartEpochDay: start,
            requestedEndEpochDay: end,
            timeZoneId: "UTC",
            now: checkedAt.addingTimeInterval(1)
        )
        XCTAssertFalse(invalidated.exhaustive)
        XCTAssertTrue(invalidated.warnings.contains(SalaryWorkCoverageStoreV2.missingWarning))
    }

    func testEmptyHistoryIsNeverConfirmedWithoutExplicitAttestation() {
        let defaults = defaults()
        let unresolved = SalaryWorkCoverageStoreV2.resolve(
            defaults: defaults,
            sessions: [],
            storageReliable: true,
            employerId: "company",
            requestedStartEpochDay: 1,
            requestedEndEpochDay: 7,
            timeZoneId: "UTC",
            now: Date(timeIntervalSince1970: 10 * 86_400)
        )
        XCTAssertFalse(unresolved.exhaustive)
    }

    func testWrongEmployerTimezoneFutureOrNarrowCoverageDoNotMatch() {
        let fingerprint = "abc"
        let proof = SalaryWorkCoverageAttestationV2(
            employerId: "company",
            coveredStartEpochDay: 1,
            coveredEndEpochDay: 31,
            checkedAt: Date(timeIntervalSince1970: 200),
            timeZoneId: "Europe/Paris",
            sourceId: "proof",
            historyFingerprint: fingerprint
        )
        func match(
            employer: String = "company",
            start: Int64 = 8,
            end: Int64 = 14,
            zone: String = "Europe/Paris",
            now: Date = Date(timeIntervalSince1970: 300),
            currentFingerprint: String = fingerprint
        ) -> SalaryWorkCoverageAttestationV2? {
            SalaryWorkCoverageStoreV2.selectMatching(
                attestations: [proof],
                historyFingerprint: currentFingerprint,
                employerId: employer,
                requestedStartEpochDay: start,
                requestedEndEpochDay: end,
                timeZoneId: zone,
                now: now
            )
        }

        XCTAssertNotNil(match())
        XCTAssertNil(match(employer: "other"))
        XCTAssertNil(match(start: 0))
        XCTAssertNil(match(end: 40))
        XCTAssertNil(match(zone: "UTC"))
        XCTAssertNil(match(now: Date(timeIntervalSince1970: 199)))
        XCTAssertNil(match(currentFingerprint: "changed"))
    }

    func testFactoryKeepsExhaustiveFalseWhenStoreHasNoProof() {
        let source = SalarySegmentedPayrollSessionSourceFactoryV2.make(
            sessions: [session("00000000-0000-0000-0000-000000000001", 1)],
            storageReliable: true,
            defaults: defaults(),
            employerId: "company",
            requestedStartEpochDay: 1,
            requestedEndEpochDay: 7,
            timeZoneId: "UTC",
            now: date(20)
        )
        XCTAssertTrue(source.work.reliable)
        XCTAssertFalse(source.exhaustive)
        XCTAssertTrue(source.warnings.contains(SalaryWorkCoverageStoreV2.missingWarning))
    }

    private func session(_ id: String, _ day: Int) -> WorkSession {
        WorkSession(
            id: UUID(uuidString: id)!,
            entry: date(day, 8),
            exit: date(day, 16),
            pauses: [],
            employerId: "company",
            placeLabel: "Site"
        )
    }
}
