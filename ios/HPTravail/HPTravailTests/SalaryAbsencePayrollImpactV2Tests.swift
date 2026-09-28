import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryAbsencePayrollImpactV2Tests: XCTestCase {
    private let companyId = "company-a"
    private let period = YearMonthV2(year: 2026, month: 9)!

    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(identifier: "Europe/Paris")!
        value.locale = Locale(identifier: "fr_FR")
        return value
    }

    func testCountsOnlyConfirmedFullUnpaidCalendarDays() {
        let result = resolve([
            absence(7, 10)
        ])

        XCTAssertEqual(result.unpaidFullCalendarDays, 3)
        XCTAssertTrue(result.hasUnpaidAbsence)
        XCTAssertTrue(result.requiresPayrollReview)
    }

    func testWorkedDayIsExcludedFromFullDayAbsenceProration() {
        let result = resolve(
            [absence(7, 10)],
            sessions: [session(day: 8)]
        )

        XCTAssertEqual(result.unpaidFullCalendarDays, 2)
        XCTAssertTrue(result.warnings.contains { $0.contains("08/09/2026") })
    }

    func testOtherEmployerWorkDoesNotCancelAbsence() {
        let result = resolve(
            [absence(8, 9)],
            sessions: [session(day: 8, employerId: "company-b")]
        )

        XCTAssertEqual(result.unpaidFullCalendarDays, 1)
    }

    func testOverlappingAbsencesAreDeduplicated() {
        let result = resolve([
            absence(7, 10),
            absence(9, 11)
        ])

        XCTAssertEqual(result.unpaidFullCalendarDays, 4)
    }

    func testPartialAbsenceNeverBecomesFullCeilingDay() {
        let result = resolve([
            absence(14, 15, fullDay: false)
        ])

        XCTAssertEqual(result.unpaidFullCalendarDays, 0)
        XCTAssertTrue(result.hasUnpaidAbsence)
        XCTAssertTrue(result.warnings.contains { $0.contains("partielle") })
    }

    func testPaidLeaveDeclaredUnpaidFailsClosed() {
        let result = resolve([
            absence(
                14,
                19,
                type: SalaryAbsencePayrollImpactV2.typePaidLeave
            )
        ])

        XCTAssertNil(result.unpaidFullCalendarDays)
        XCTAssertFalse(result.hasUnpaidAbsence)
        XCTAssertTrue(result.requiresPayrollReview)
        XCTAssertTrue(result.warnings.contains { $0.contains("traitement incohérent") })
    }

    func testSicknessToConfirmDoesNotInventDeduction() {
        let result = resolve([
            absence(
                21,
                24,
                treatment: .toConfirm,
                type: SalaryAbsencePayrollImpactV2.typeSickness
            )
        ])

        XCTAssertNil(result.unpaidFullCalendarDays)
        XCTAssertFalse(result.hasUnpaidAbsence)
        XCTAssertTrue(result.requiresPayrollReview)
        XCTAssertTrue(result.warnings.contains { $0.contains("Arrêt maladie") })
    }

    func testMissingAbsenceSourceIsNotReliableEmptyList() {
        let result = SalaryAbsencePayrollImpactV2.forMonth(
            absences: [],
            period: period,
            acceptedEmployerIds: [companyId],
            workSessions: [],
            absenceSourceReliable: false,
            workSourceReliable: true,
            calendar: calendar
        )

        XCTAssertNil(result.unpaidFullCalendarDays)
        XCTAssertTrue(result.requiresPayrollReview)
    }

    private func resolve(
        _ absences: [SalaryAbsenceFactV2],
        sessions: [SalarySessionFactV2] = []
    ) -> SalaryAbsencePayrollImpactSnapshotV2 {
        SalaryAbsencePayrollImpactV2.forMonth(
            absences: absences,
            period: period,
            acceptedEmployerIds: [companyId],
            workSessions: sessions,
            absenceSourceReliable: true,
            workSourceReliable: true,
            calendar: calendar
        )
    }

    private func absence(
        _ startDay: Int,
        _ endExclusiveDay: Int,
        treatment: SalaryAbsenceTreatmentV2 = .unpaid,
        fullDay: Bool = true,
        type: String = SalaryAbsencePayrollImpactV2.typeUnpaid
    ) -> SalaryAbsenceFactV2 {
        SalaryAbsenceFactV2(
            id: "absence-\(startDay)-\(type)",
            employerId: companyId,
            type: type,
            start: localDate(startDay, 0),
            end: localDate(endExclusiveDay, 0),
            salaryTreatment: treatment,
            fullDay: fullDay,
            status: .confirmed
        )
    }

    private func session(
        day: Int,
        employerId: String? = nil
    ) -> SalarySessionFactV2 {
        SalarySessionFactV2(
            id: "session-\(day)",
            entry: localDate(day, 5),
            exit: localDate(day, 13),
            employerId: employerId ?? companyId,
            pauses: []
        )
    }

    private func localDate(_ day: Int, _ hour: Int) -> Date {
        calendar.date(
            from: DateComponents(
                calendar: calendar,
                timeZone: calendar.timeZone,
                year: 2026,
                month: 9,
                day: day,
                hour: hour
            )
        )!
    }
}
