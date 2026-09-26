import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
import RuntimeV2Contract
#endif

final class SalaryPayrollCoverageAttestationStoreV2Tests: XCTestCase {
    func testFingerprintIsOrderIndependent() {
        let a = session(id: "00000000-0000-0000-0000-000000000001", start: 1_000)
        let b = session(id: "00000000-0000-0000-0000-000000000002", start: 5_000)

        XCTAssertEqual(
            SalaryPayrollCoverageAttestationStoreV2.fingerprint([a, b]),
            SalaryPayrollCoverageAttestationStoreV2.fingerprint([b, a])
        )
    }

    func testPauseMutationChangesFingerprint() {
        let base = session(id: "00000000-0000-0000-0000-000000000001", start: 1_000)
        var changed = base
        changed.pauses = [
            PausePeriod(
                id: UUID(uuidString: "10000000-0000-0000-0000-000000000001")!,
                start: Date(timeIntervalSince1970: 1_200),
                end: Date(timeIntervalSince1970: 1_300),
                paid: true
            )
        ]

        XCTAssertNotEqual(
            SalaryPayrollCoverageAttestationStoreV2.fingerprint([base]),
            SalaryPayrollCoverageAttestationStoreV2.fingerprint([changed])
        )
    }

    func testConfirmedCoverageBecomesStaleAfterHistoryMutation() throws {
        let suite = "SalaryPayrollCoverageAttestationStoreV2Tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }

        let base = session(id: "00000000-0000-0000-0000-000000000001", start: 1_000)
        let checkedAt = Date(timeIntervalSince1970: 3 * 86_400)

        XCTAssertTrue(
            SalaryPayrollCoverageAttestationStoreV2.confirm(
                defaults: defaults,
                sessions: [base],
                storageReliable: true,
                companyId: "company",
                coveredStartEpochDay: 0,
                coveredEndEpochDay: 0,
                sourceId: "manual-confirmation",
                timeZoneId: "Europe/Paris",
                checkedAt: checkedAt
            )
        )

        let fresh = SalaryPayrollCoverageAttestationStoreV2.read(
            defaults: defaults,
            sessions: [base],
            storageReliable: true,
            companyId: "company",
            requiredStartEpochDay: 0,
            requiredEndEpochDay: 0,
            now: checkedAt.addingTimeInterval(1)
        )
        XCTAssertTrue(fresh.reliable)

        var changed = base
        changed.exit = Date(timeIntervalSince1970: 4_700)
        let stale = SalaryPayrollCoverageAttestationStoreV2.read(
            defaults: defaults,
            sessions: [changed],
            storageReliable: true,
            companyId: "company",
            requiredStartEpochDay: 0,
            requiredEndEpochDay: 0,
            now: checkedAt.addingTimeInterval(1)
        )
        XCTAssertFalse(stale.reliable)
        XCTAssertTrue(
            stale.warnings.contains(
                SalaryPayrollCoverageAttestationStoreV2.staleWarning
            )
        )
    }

    private func session(id: String, start: TimeInterval) -> WorkSession {
        WorkSession(
            id: UUID(uuidString: id)!,
            entry: Date(timeIntervalSince1970: start),
            exit: Date(timeIntervalSince1970: start + 3_600),
            pauses: [],
            employerId: "company",
            placeLabel: "Atelier"
        )
    }
}
