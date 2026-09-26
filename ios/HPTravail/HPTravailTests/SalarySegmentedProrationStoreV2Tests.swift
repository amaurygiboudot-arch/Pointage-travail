import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedProrationStoreV2Tests: XCTestCase {
    private var defaults: UserDefaults!
    private let companyId = "company-a"
    private let period = YearMonthV2(year: 2026, month: 9)!

    override func setUp() {
        super.setUp()
        let suite = "SalarySegmentedProrationStoreV2Tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        XCTAssertTrue(
            SalaryCompanyStoreV2.createOrUpdate(
                SalaryCompanyV2(
                    id: companyId,
                    name: "Entreprise test",
                    siret: "12345678901234"
                ),
                defaults: defaults
            )
        )
    }

    func testMissingProrationStaysUnknown() {
        let result = SalarySegmentedProrationStoreV2.resolve(
            companyId: companyId,
            period: period,
            defaults: defaults
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.proration)
        XCTAssertEqual(result.warnings, [SalarySegmentedProrationStoreV2.missingWarning])
    }

    func testConfirmedProrationRoundTripsByCompanyAndMonth() {
        let value = validProration()
        XCTAssertTrue(
            SalarySegmentedProrationStoreV2.save(
                companyId: companyId,
                period: period,
                proration: value,
                defaults: defaults
            )
        )

        let result = SalarySegmentedProrationStoreV2.resolve(
            companyId: companyId,
            period: period,
            defaults: defaults
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.proration, value)
        XCTAssertTrue(result.warnings.isEmpty)
    }

    func testCorruptStorageFailsClosed() {
        XCTAssertTrue(
            SalarySegmentedProrationStoreV2.save(
                companyId: companyId,
                period: period,
                proration: validProration(),
                defaults: defaults
            )
        )
        defaults.set(
            "{corrompu",
            forKey: "salary_segmented_proration_v2.\(companyId).\(period.description)"
        )

        let result = SalarySegmentedProrationStoreV2.resolve(
            companyId: companyId,
            period: period,
            defaults: defaults
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.proration)
        XCTAssertEqual(result.warnings, [SalarySegmentedProrationStoreV2.storageWarning])
    }

    func testNegativeScheduledMinutesAreRejected() {
        let invalid = ConfirmedSegmentedMonthlyProrationV2(
            sourceId: "planning confirme",
            checkedAtMs: 1_790_000_000_000,
            segments: [
                ConfirmedProrationSegmentV2(
                    versionId: "v1",
                    startEpochDay: 20_000,
                    endEpochDay: 20_014,
                    scheduledMinutes: -1
                )
            ]
        )

        XCTAssertFalse(
            SalarySegmentedProrationStoreV2.save(
                companyId: companyId,
                period: period,
                proration: invalid,
                defaults: defaults
            )
        )
    }

    func testDuplicateSegmentIdentityIsRejected() {
        let segment = ConfirmedProrationSegmentV2(
            versionId: "v1",
            startEpochDay: 20_000,
            endEpochDay: 20_014,
            scheduledMinutes: 4_200
        )
        let invalid = ConfirmedSegmentedMonthlyProrationV2(
            sourceId: "planning confirme",
            checkedAtMs: 1_790_000_000_000,
            segments: [segment, segment]
        )

        XCTAssertFalse(
            SalarySegmentedProrationStoreV2.save(
                companyId: companyId,
                period: period,
                proration: invalid,
                defaults: defaults
            )
        )
    }

    func testRemovingRecordReturnsToUnknownState() {
        XCTAssertTrue(
            SalarySegmentedProrationStoreV2.save(
                companyId: companyId,
                period: period,
                proration: validProration(),
                defaults: defaults
            )
        )
        XCTAssertTrue(
            SalarySegmentedProrationStoreV2.remove(
                companyId: companyId,
                period: period,
                defaults: defaults
            )
        )

        let result = SalarySegmentedProrationStoreV2.resolve(
            companyId: companyId,
            period: period,
            defaults: defaults
        )
        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.proration)
    }

    private func validProration() -> ConfirmedSegmentedMonthlyProrationV2 {
        ConfirmedSegmentedMonthlyProrationV2(
            sourceId: "planning confirme",
            checkedAtMs: 1_790_000_000_000,
            segments: [
                ConfirmedProrationSegmentV2(
                    versionId: "v1",
                    startEpochDay: 20_000,
                    endEpochDay: 20_014,
                    scheduledMinutes: 4_200
                ),
                ConfirmedProrationSegmentV2(
                    versionId: "v2",
                    startEpochDay: 20_015,
                    endEpochDay: 20_029,
                    scheduledMinutes: 4_200
                )
            ]
        )
    }
}
