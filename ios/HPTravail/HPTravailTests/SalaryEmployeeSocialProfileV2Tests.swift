import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryEmployeeSocialProfileV2Tests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!
    private let companyId = "company-social"

    override func setUp() {
        super.setUp()
        suiteName = "SalaryEmployeeSocialProfileV2Tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
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

    override func tearDown() {
        if let suiteName {
            defaults.removePersistentDomain(forName: suiteName)
        }
        defaults = nil
        suiteName = nil
        super.tearDown()
    }

    func testMissingProfileNeverInventsNonCadreOrNoLocalRegime() {
        let stored = SalaryEmployeeSocialProfileStoreV2.readConfirmed(
            defaults: defaults,
            companyId: companyId
        )
        let result = SalaryEmployeeSocialProfileStoreV2.resolve(
            stored,
            companyId: companyId,
            period: period(2026, 9)
        )

        XCTAssertTrue(stored.reliable)
        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.professionalStatus)
        XCTAssertNil(result.alsaceMoselleLocalRegime)
        XCTAssertTrue(result.warnings.contains(SalaryEmployeeSocialProfileStoreV2.missingWarning))
    }

    func testExplicitNonCadreAndNoLocalRegimeAreReliableFacts() {
        XCTAssertTrue(
            SalaryEmployeeSocialProfileStoreV2.saveConfirmed(
                snapshot(
                    version: "v1",
                    from: epochDay(2026, 1, 1),
                    to: nil,
                    status: .nonCadre,
                    localRegime: false
                ),
                defaults: defaults
            )
        )

        let result = SalaryEmployeeSocialProfileStoreV2.resolve(
            defaults: defaults,
            companyId: companyId,
            period: period(2026, 9)
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.professionalStatus, .nonCadre)
        XCTAssertEqual(result.alsaceMoselleLocalRegime, false)
        XCTAssertTrue(result.warnings.isEmpty)
    }

    func testCorruptStorageIsNotTreatedAsMissing() {
        defaults.set(
            "{not-json",
            forKey: "salary_employee_social_profile_v2.\(companyId)"
        )

        let stored = SalaryEmployeeSocialProfileStoreV2.readConfirmed(
            defaults: defaults,
            companyId: companyId
        )
        let result = SalaryEmployeeSocialProfileStoreV2.resolve(
            stored,
            companyId: companyId,
            period: period(2026, 9)
        )

        XCTAssertFalse(stored.reliable)
        XCTAssertFalse(result.reliable)
        XCTAssertTrue(result.warnings.contains(SalaryEmployeeSocialProfileStoreV2.storageWarning))
    }

    func testRealStatusChangeInsideMonthBlocksMonthlyNetProfile() {
        let change = epochDay(2026, 9, 16)
        let stored = SalaryEmployeeSocialProfileReadResultV2(
            snapshots: [
                snapshot(
                    version: "v1",
                    from: epochDay(2026, 1, 1),
                    to: change - 1,
                    status: .nonCadre,
                    localRegime: false
                ),
                snapshot(
                    version: "v2",
                    from: change,
                    to: nil,
                    status: .cadre,
                    localRegime: false
                )
            ],
            reliable: true,
            warnings: []
        )

        let result = SalaryEmployeeSocialProfileStoreV2.resolve(
            stored,
            companyId: companyId,
            period: period(2026, 9)
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.professionalStatus)
        XCTAssertTrue(
            result.warnings.contains(
                SalaryEmployeeSocialProfileStoreV2.changedDuringPeriodWarning
            )
        )
    }

    func testSourceVersionChangeWithSameFactsKeepsMonthReliable() {
        let change = epochDay(2026, 9, 16)
        let stored = SalaryEmployeeSocialProfileReadResultV2(
            snapshots: [
                snapshot(
                    version: "v1",
                    from: epochDay(2026, 1, 1),
                    to: change - 1,
                    status: .cadre,
                    localRegime: true
                ),
                snapshot(
                    version: "v2",
                    from: change,
                    to: nil,
                    status: .cadre,
                    localRegime: true
                )
            ],
            reliable: true,
            warnings: []
        )

        let result = SalaryEmployeeSocialProfileStoreV2.resolve(
            stored,
            companyId: companyId,
            period: period(2026, 9)
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.professionalStatus, .cadre)
        XCTAssertEqual(result.alsaceMoselleLocalRegime, true)
    }

    func testOverlappingConfirmedVersionsAreRejected() {
        XCTAssertTrue(
            SalaryEmployeeSocialProfileStoreV2.saveConfirmed(
                snapshot(
                    version: "v1",
                    from: epochDay(2026, 1, 1),
                    to: epochDay(2026, 12, 31),
                    status: .nonCadre,
                    localRegime: false
                ),
                defaults: defaults
            )
        )
        XCTAssertFalse(
            SalaryEmployeeSocialProfileStoreV2.saveConfirmed(
                snapshot(
                    version: "v2",
                    from: epochDay(2026, 9, 1),
                    to: nil,
                    status: .cadre,
                    localRegime: false
                ),
                defaults: defaults
            )
        )
    }

    private func snapshot(
        version: String,
        from: Int64,
        to: Int64?,
        status: SalaryProfessionalStatusV2,
        localRegime: Bool
    ) -> SalaryEmployeeSocialProfileSnapshotV2 {
        SalaryEmployeeSocialProfileSnapshotV2(
            companyId: companyId,
            versionId: version,
            sourceId: "bulletin-confirmed",
            effectiveFromEpochDay: from,
            effectiveToEpochDay: to,
            professionalStatus: status,
            alsaceMoselleLocalRegime: localRegime,
            checkedAtMs: 1
        )
    }

    private func period(_ year: Int, _ month: Int) -> YearMonthV2 {
        YearMonthV2(year: year, month: month)!
    }

    private func epochDay(_ year: Int, _ month: Int, _ day: Int) -> Int64 {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let date = calendar.date(
            from: DateComponents(year: year, month: month, day: day)
        )!
        return Int64(floor(date.timeIntervalSince1970 / 86_400.0))
    }
}

