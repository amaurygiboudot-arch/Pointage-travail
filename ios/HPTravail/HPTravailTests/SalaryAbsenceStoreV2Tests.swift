import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryAbsenceStoreV2Tests: XCTestCase {
    private var defaults: UserDefaults!
    private let companyId = "company-a"
    private let period = YearMonthV2(year: 2026, month: 9)!

    override func setUp() {
        super.setUp()
        let suite = "SalaryAbsenceStoreV2Tests.\(UUID().uuidString)"
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

    func testEmptyListIsNotReliableAbsenceOfAbsenceWithoutMonthlyConfirmation() {
        let result = SalaryAbsenceStoreV2.resolve(
            companyId: companyId,
            period: period,
            defaults: defaults
        )

        XCTAssertFalse(result.reliable)
        XCTAssertTrue(result.absences.isEmpty)
        XCTAssertTrue(result.warnings.contains { $0.contains("non confirmée exhaustive") })
    }

    func testExplicitMonthlyConfirmationMakesEmptyListReliable() {
        XCTAssertTrue(
            SalaryAbsenceStoreV2.confirmMonth(
                companyId: companyId,
                period: period,
                source: "Vérification personnelle",
                defaults: defaults
            )
        )

        let result = SalaryAbsenceStoreV2.resolve(
            companyId: companyId,
            period: period,
            defaults: defaults
        )

        XCTAssertTrue(result.reliable)
        XCTAssertTrue(result.absences.isEmpty)
    }

    func testSavingAbsenceInvalidatesPreviousMonthlyConfirmation() {
        XCTAssertTrue(
            SalaryAbsenceStoreV2.confirmMonth(
                companyId: companyId,
                period: period,
                source: "Planning",
                defaults: defaults
            )
        )
        XCTAssertTrue(
            SalaryAbsenceStoreV2.save(
                companyId: companyId,
                absence: absence(),
                defaults: defaults
            )
        )

        let result = SalaryAbsenceStoreV2.resolve(
            companyId: companyId,
            period: period,
            defaults: defaults
        )

        XCTAssertFalse(result.reliable)
        XCTAssertEqual(result.absences.count, 1)
    }

    func testConfirmedStoredAbsenceBecomesReliableAfterReconfirmation() {
        XCTAssertTrue(
            SalaryAbsenceStoreV2.save(
                companyId: companyId,
                absence: absence(),
                defaults: defaults
            )
        )
        XCTAssertTrue(
            SalaryAbsenceStoreV2.confirmMonth(
                companyId: companyId,
                period: period,
                source: "Planning vérifié",
                defaults: defaults
            )
        )

        let result = SalaryAbsenceStoreV2.resolve(
            companyId: companyId,
            period: period,
            defaults: defaults
        )

        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.absences.count, 1)
    }

    func testCorruptRecordStoreFailsClosedEvenWithConfirmation() {
        XCTAssertTrue(
            SalaryAbsenceStoreV2.confirmMonth(
                companyId: companyId,
                period: period,
                source: "Planning",
                defaults: defaults
            )
        )
        defaults.set(
            "{corrompu",
            forKey: "salary_absences_v2.records.\(companyId)"
        )

        let result = SalaryAbsenceStoreV2.resolve(
            companyId: companyId,
            period: period,
            defaults: defaults
        )

        XCTAssertFalse(result.reliable)
        XCTAssertTrue(result.warnings.contains { $0.contains("incohérent") })
    }

    private func absence() -> SalaryAbsenceFactV2 {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Paris")!
        let start = calendar.date(
            from: DateComponents(year: 2026, month: 9, day: 8)
        )!
        let end = calendar.date(
            from: DateComponents(year: 2026, month: 9, day: 9)
        )!
        return SalaryAbsenceFactV2(
            id: "absence-1",
            employerId: companyId,
            type: SalaryAbsencePayrollImpactV2.typeUnpaid,
            start: start,
            end: end,
            salaryTreatment: .unpaid,
            fullDay: true,
            status: .confirmed
        )
    }
}
