import Foundation
import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
@testable import RuntimeV2Contract
#endif

final class SalaryGpsPresenceQualificationV2Tests: XCTestCase {
    private var calendar: Calendar {
        var result = Calendar(identifier: .gregorian)
        result.timeZone = TimeZone(secondsFromGMT: 0)!
        return result
    }

    private func date(_ day: Int, _ hour: Int) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 9, day: day, hour: hour))!
    }

    private func aggregate(_ facts: [SalarySessionFactV2]) -> SalaryPaidWorkAggregationV2 {
        SalaryPaidWorkAggregatorV2.aggregate(
            sessions: facts, employerId: "company", period: YearMonthV2(year: 2026, month: 9)!,
            calendar: calendar
        )
    }

    func testPersistedGpsPresenceCannotBecomeConfirmedPayOrPremiums() throws {
        var session = WorkSession(id: UUID(), entry: date(13, 22), exit: date(14, 6),
                                  pauses: [], employerId: "company")
        session.segments = [WorkSegmentV2(
            start: session.entry, end: session.exit, kind: .toConfirm, confirmed: false
        )]
        // Exercise the persisted contract, not only an in-memory mapping.
        let restored = try JSONDecoder().decode(WorkSession.self, from: JSONEncoder().encode(session))
        let source = SalaryWorkSessionBridgeV2.source(from: [restored], storageReliable: true)
        XCTAssertTrue(source.reliable) // Storage reliability is distinct from paid qualification.
        let fact = try XCTUnwrap(source.sessions.first)
        XCTAssertEqual(fact.segments, [PaidWorkSegmentFactV2(
            start: session.entry, end: session.exit, kind: .toConfirm, confirmed: false
        )])
        let result = aggregate(source.sessions)
        XCTAssertFalse(result.reliable)
        XCTAssertEqual(result.totalPaidMinutes, 0)
        XCTAssertTrue(result.warnings.contains(SalaryPaidWorkAggregatorV2.unresolvedSegmentWarning))
        XCTAssertEqual(result.weeks.count, 2) // Uncertainty survives both sides of the ISO week.

        let premium = SalaryPaidOverlapPolicyV2.paidOverlap(
            session: fact, rangeStart: date(14, 0), rangeEnd: date(14, 6)
        )
        XCTAssertFalse(premium.reliable)
        XCTAssertEqual(premium.paidDuration, 0)
    }

    func testHistoricalNilSegmentsPreserveExistingPaidContract() {
        let session = WorkSession(id: UUID(), entry: date(14, 8), exit: date(14, 16),
                                  pauses: [], employerId: "company")
        let source = SalaryWorkSessionBridgeV2.source(from: [session], storageReliable: true)
        XCTAssertEqual(source.sessions.first?.segments, [])
        XCTAssertTrue(aggregate(source.sessions).reliable)
        XCTAssertEqual(aggregate(source.sessions).totalPaidMinutes, 480)
    }

    func testExplicitEmptySegmentsDoNotFallBackToHistoricalPaidTime() {
        var session = WorkSession(id: UUID(), entry: date(14, 8), exit: date(14, 16),
                                  pauses: [], employerId: "company")
        session.segments = []
        let source = SalaryWorkSessionBridgeV2.source(from: [session], storageReliable: true)
        XCTAssertFalse(aggregate(source.sessions).reliable)
        XCTAssertEqual(aggregate(source.sessions).totalPaidMinutes, 0)
    }

    func testConfirmedKindsSurviveWeekAndPremiumClipping() {
        let fact = SalarySessionFactV2(
            id: "qualified", entry: date(13, 22), exit: date(14, 6), employerId: "company", pauses: [],
            segments: [
                .init(start: date(13, 22), end: date(13, 23), kind: .work, confirmed: true),
                .init(start: date(13, 23), end: date(14, 1), kind: .personalTravel, confirmed: true),
                .init(start: date(14, 1), end: date(14, 2), kind: .paidTravel, confirmed: true),
                .init(start: date(14, 2), end: date(14, 6), kind: .work, confirmed: true)
            ]
        )
        let result = aggregate([fact])
        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.weeks.map(\.paidMinutes), [60, 300])
        XCTAssertEqual(result.totalPaidMinutes, 360)
        let premium = SalaryPaidOverlapPolicyV2.paidOverlap(
            session: fact, rangeStart: date(14, 0), rangeEnd: date(14, 6)
        )
        XCTAssertTrue(premium.reliable)
        XCTAssertEqual(premium.paidDuration, 5 * 3_600)
    }

    func testMissingSegmentCoverageCannotDisappearDuringClipping() {
        let fact = SalarySessionFactV2(
            id: "gap", entry: date(13, 22), exit: date(14, 6), employerId: "company", pauses: [],
            segments: [.init(start: date(14, 0), end: date(14, 6), kind: .work, confirmed: true)]
        )
        let premium = SalaryPaidOverlapPolicyV2.paidOverlap(
            session: fact, rangeStart: date(14, 1), rangeEnd: date(14, 2)
        )
        XCTAssertFalse(premium.reliable)
        XCTAssertEqual(premium.paidDuration, 0)
        XCTAssertFalse(aggregate([fact]).reliable)
    }

    func testQualificationChangeInvalidatesExistingCoverageFingerprint() throws {
        func fingerprint(kind: PaidWorkSegmentKindV2, confirmed: Bool) throws -> String {
            let fact = SalarySessionFactV2(
                id: "same-observation", entry: date(14, 8), exit: date(14, 16),
                employerId: "company", pauses: [],
                segments: [.init(start: date(14, 8), end: date(14, 16), kind: kind, confirmed: confirmed)]
            )
            let day = Int64(date(14, 0).timeIntervalSince1970 / 86_400)
            return try XCTUnwrap(SalaryPayrollCoverageAttestationPolicyV2.fingerprint(
                work: .init(sessions: [fact], reliable: true), employerId: "company",
                coveredStartEpochDay: day, coveredEndEpochDay: day, timeZoneId: "UTC"
            ))
        }
        let unresolved = try fingerprint(kind: .toConfirm, confirmed: false)
        XCTAssertNotEqual(unresolved, try fingerprint(kind: .work, confirmed: true))
        XCTAssertNotEqual(try fingerprint(kind: .work, confirmed: false),
                          try fingerprint(kind: .work, confirmed: true))
        XCTAssertNotEqual(try fingerprint(kind: .work, confirmed: true),
                          try fingerprint(kind: .personalTravel, confirmed: true))
    }
}
