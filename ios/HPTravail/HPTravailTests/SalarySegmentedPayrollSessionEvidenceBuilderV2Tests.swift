import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalarySegmentedPayrollSessionEvidenceBuilderV2Tests: XCTestCase {
    func testRealV2SessionsReachB21WithoutMonthlyBaseDuplication() throws {
        let f = try fixture((4...8).map { session("s\($0)", Int64($0), 8, Int64($0), 16) })
        let proof = f.build()
        XCTAssertTrue(proof.reliable)
        XCTAssertEqual(proof.slices.first?.weeks.first?.week.paidMinutes, 2400)
        XCTAssertEqual(proof.contributingSessionIds.count, 5)
        let result = f.calculate()
        XCTAssertTrue(result.reliable)
        XCTAssertEqual(try XCTUnwrap(result.pieces.first).variableGross, 62.5, accuracy: 0.0001)
    }
    func testPaidAndUnpaidPausesUseCanonicalAllocation() throws {
        let s = session("s", 4, 8, 4, 16, pauses: [
            PaidPauseFactV2(start: date(4, 12), end: date(4, 12, 30), paid: false),
            PaidPauseFactV2(start: date(4, 14), end: date(4, 14, 15), paid: true)])
        let f = try fixture([s])
        let proof = f.build()
        XCTAssertTrue(proof.reliable)
        XCTAssertEqual(proof.slices.first?.weeks.first?.week.paidMinutes, 450)
        let canonical = SalaryPaidOverlapPolicyV2.paidOverlap(session: s, rangeStart: date(4, 0), rangeEnd: date(11, 0))
        XCTAssertEqual(proof.slices.first?.weeks.first?.week.paidMinutes, Int(floor(canonical.paidDuration / 60)))
    }
    func testUnknownPauseDoesNotBecomeUnpaidOrZero() throws {
        assertBlocked(try fixture([session("s", 4, 8, 4, 16, pauses: [
            PaidPauseFactV2(start: date(4, 12), end: date(4, 12, 30), paid: nil)])]))
    }
    func testOpenSessionBlocks() throws {
        assertBlocked(try fixture([SalarySessionFactV2(id: "s", entry: date(4, 8), exit: nil, employerId: "company", pauses: [])]))
    }
    func testStorageReliabilityDoesNotProveExhaustiveHistory() throws {
        assertBlocked(try fixture([], exhaustive: false))
    }
    func testCorruptSourceNeverBecomesEmptyConfirmedWeek() throws {
        assertBlocked(try fixture([], sourceReliable: false))
    }
    func testExplicitlyCoveredNoWorkWeekIsReliableZero() throws {
        let f = try fixture([])
        let proof = f.build()
        XCTAssertTrue(proof.reliable)
        XCTAssertEqual(proof.slices.first?.weeks.count, 1)
        XCTAssertEqual(proof.slices.first?.weeks.first?.week.paidMinutes, 0)
        let result = f.calculate()
        XCTAssertTrue(result.reliable)
        XCTAssertEqual(try XCTUnwrap(result.pieces.first).variableGross, 0, accuracy: 0)
    }
    func testFirstMissingCoverageDayBlocks() throws {
        assertBlocked(try fixture([session("s", 4, 8, 4, 16)], coveredStart: 5))
    }
    func testLastMissingCoverageDayBlocks() throws {
        assertBlocked(try fixture([session("s", 4, 8, 4, 16)], coveredEnd: 9))
    }
    func testUnfinishedWeekCannotBeCertified() throws {
        assertBlocked(try fixture([], checkedAt: date(8, 12)))
    }
    func testFutureCertificateIsRejected() throws {
        assertBlocked(try fixture([], checkedAt: date(12, 12)))
    }
    func testSourceEmployerMustMatchContractOwner() throws {
        assertBlocked(try fixture([], sourceEmployer: "other"))
    }
    func testOtherKnownEmployerDoesNotContaminateWeek() throws {
        let f = try fixture([session("own", 4, 8, 4, 16), session("other", 4, 8, 4, 16, employer: "other")])
        let proof = f.build()
        XCTAssertTrue(proof.reliable)
        XCTAssertEqual(proof.contributingSessionIds, ["own"])
        XCTAssertEqual(proof.slices.first?.weeks.first?.week.paidMinutes, 480)
    }
    func testUnassignedRelevantSessionBlocks() throws {
        assertBlocked(try fixture([session("s", 4, 8, 4, 16, employer: nil)]))
    }
    func testOverlappingSessionsBlock() throws {
        assertBlocked(try fixture([session("s1", 4, 8, 4, 16), session("s2", 4, 15, 4, 17)]))
    }
    func testDuplicatedIdentityOnDifferentDaysBlocks() throws {
        assertBlocked(try fixture([session("s", 4, 8, 4, 16), session("s", 5, 8, 5, 16)]))
    }
    func testPaidTimeOutsideSliceDoesNotLeakIntoPeriod() throws {
        let f = try fixture([session("outside", 4, 8, 4, 16), session("inside", 6, 8, 6, 16)], start: 6)
        let proof = f.build()
        XCTAssertFalse(proof.reliable)
        XCTAssertTrue(proof.slices.isEmpty)
        XCTAssertTrue(proof.warnings.contains(SalarySegmentedPayrollSessionEvidenceBuilderV2.edgeWarning))
    }
    func testPartialWeekWithConfirmedNoWorkOutsideRemainsUsable() throws {
        let f = try fixture([session("s", 6, 8, 6, 16)], start: 6)
        XCTAssertTrue(f.build().reliable)
        XCTAssertTrue(f.calculate().reliable)
    }
    func testStructuredNightUsesExplicitTimeZone() throws {
        let zone = "America/New_York"
        let f = try fixture([session("s", 4, 22, 5, 6, zone: zone)], zone: zone,
            payrollRules: payrollRules(night: 1.25), night: NightPremiumRuleV2(startMinute: 22 * 60, endMinute: 6 * 60, multiplier: 1.25))
        let proof = f.build()
        XCTAssertTrue(proof.reliable)
        XCTAssertEqual(proof.slices.first?.weeks.first?.week.nightMinutes, 480)
        XCTAssertEqual(try XCTUnwrap(f.calculate().pieces.first).variableGross, 20, accuracy: 0.0001)
    }
    func testMissingNightRuleBlocksRatherThanGuessingShift() throws {
        assertBlocked(try fixture([session("s", 4, 22, 5, 6)], payrollRules: payrollRules(night: 1.25)))
    }
    func testSundayComesFromActualPaidDates() throws {
        let f = try fixture([session("s", 10, 8, 10, 16)], payrollRules: payrollRules(sunday: 1.5))
        XCTAssertEqual(f.build().slices.first?.weeks.first?.week.sundayMinutes, 480)
        XCTAssertEqual(try XCTUnwrap(f.calculate().pieces.first).variableGross, 40, accuracy: 0.0001)
    }
    func testGlobalSourceWarningSurvivesB21() throws {
        let f = try fixture([session("s", 4, 8, 4, 16)], warnings: ["trace-import", "trace-import"])
        let result = f.calculate()
        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.warnings.filter { $0 == "trace-import" }.count, 1)
    }
    func testMissingPremiumProofBlocks() throws {
        var f = try fixture([])
        f.premiums = []
        assertBlocked(f)
    }
    func testMissingHolidayScopeBlocks() throws {
        var f = try fixture([])
        f.premiums = f.premiums.map { .init(slice: $0.slice, sourceId: $0.sourceId, reliable: $0.reliable,
                                           nightRule: $0.nightRule, holidayScope: nil) }
        assertBlocked(f)
    }
    func testInvalidTimeZoneBlocksRatherThanFallingBackToUtc() throws {
        var f = try fixture([])
        let s = f.source
        f.source = .init(employerId: s.employerId, work: s.work, sourceId: s.sourceId, exhaustive: s.exhaustive,
            coveredStartEpochDay: s.coveredStartEpochDay, coveredEndEpochDay: s.coveredEndEpochDay,
            checkedAt: s.checkedAt, timeZoneId: "Not/AZone")
        assertBlocked(f)
    }
    func testDedicatedMayFirstIsNotPaidByGenericHolidayRate() throws {
        let start = epochDay(2026, 4, 27)
        assertBlocked(try fixture([session("s", start + 4, 8, start + 4, 16)], start: start, end: start + 6))
    }
    func testSpringClockChangeCountsActualElapsedNight() throws {
        let start = epochDay(2026, 3, 23)
        let f = try fixture([session("s", start + 5, 22, start + 6, 6, zone: "Europe/Paris")],
            start: start, end: start + 6, zone: "Europe/Paris", payrollRules: payrollRules(night: 1.25),
            night: NightPremiumRuleV2(startMinute: 22 * 60, endMinute: 6 * 60, multiplier: 1.25))
        let proof = f.build()
        XCTAssertTrue(proof.reliable)
        XCTAssertEqual(proof.slices.first?.weeks.first?.week.paidMinutes, 420)
        XCTAssertEqual(proof.slices.first?.weeks.first?.week.nightMinutes, 420)
    }

    private func assertBlocked(_ f: Fixture, file: StaticString = #filePath, line: UInt = #line) {
        let proof = f.build()
        XCTAssertFalse(proof.reliable, file: file, line: line)
        XCTAssertTrue(proof.slices.isEmpty, file: file, line: line)
        XCTAssertFalse(proof.warnings.isEmpty, file: file, line: line)
        let result = f.calculate()
        XCTAssertFalse(result.reliable, file: file, line: line)
        XCTAssertTrue(result.pieces.isEmpty, file: file, line: line)
        XCTAssertTrue(Set(proof.warnings).isSubset(of: Set(result.warnings)), file: file, line: line)
    }
    private struct Fixture {
        let contracts: SalaryEmploymentContractPeriodResolutionV2
        let convention: SalaryConventionCoverageV2
        var source: SalarySegmentedPayrollSessionSourceV2
        var premiums: [SalarySegmentedPayrollPremiumEvidenceV2]
        let now: Date
        func build() -> SalarySegmentedPayrollSessionEvidenceResultV2 {
            SalarySegmentedPayrollSessionEvidenceBuilderV2.build(contracts: contracts, rules: convention,
                source: source, premiums: premiums, now: now)
        }
        func calculate() -> SalarySegmentedWorkedVariableGrossSourceResultV2 {
            SalarySegmentedPayrollSessionEvidenceBuilderV2.calculateVariables(contracts: contracts, rules: convention,
                source: source, premiums: premiums, now: now)
        }
    }
    private func fixture(_ sessions: [SalarySessionFactV2], start: Int64 = 4, end: Int64 = 10,
                         zone: String = "UTC", payrollRules: PayrollRulesV2? = nil, night: NightPremiumRuleV2? = nil,
                         exhaustive: Bool = true, sourceReliable: Bool = true, sourceEmployer: String = "company",
                         coveredStart: Int64? = nil, coveredEnd: Int64? = nil,
                         checkedAt: Date? = nil, warnings: [String] = []) throws -> Fixture {
        let contracts = try XCTUnwrap(SalaryEmploymentContractPeriodResolverV2.resolve(
            companyId: "company", periodStartEpochDay: start, periodEndEpochDay: end, sourceReliable: true,
            snapshots: [.init(versionId: "c1", sourceId: "contract-test", effectiveFromEpochDay: start,
                effectiveToEpochDay: nil, contract: ContractV2(id: "c1", employerId: "company", type: .fullTime,
                    contractualWeeklyMinutes: 2100, grossHourlyRate: 10, hireDateEpochDay: 0), checkedAtMs: 1, note: nil)]))
        let convention = SalaryConventionCoverageV2(companyId: "company", idcc: "0292",
            periodStartEpochDay: start, periodEndEpochDay: end, segments: [.init(startEpochDay: start,
                endEpochDay: end, snapshot: .init(idcc: "0292", versionId: "r1", sourceId: "rule-test",
                    effectiveFromEpochDay: start, effectiveToEpochDay: end,
                    rules: payrollRules ?? self.payrollRules(), checkedAtMs: 1, note: nil))],
            sourceReliable: true, fullyCovered: true, warnings: [])
        let slices = SalaryPayrollCalculationTimelineV2.align(contracts: contracts, rules: convention).slices
        let firstMonday = start - ((start % 7 + 10) % 7)
        let lastSunday = end - ((end % 7 + 10) % 7) + 6
        let now = date(lastSunday + 1, 12, zone: zone)
        let source = SalarySegmentedPayrollSessionSourceV2(employerId: sourceEmployer,
            work: .init(sessions: sessions, reliable: sourceReliable), sourceId: "runtime-snapshot-test",
            exhaustive: exhaustive, coveredStartEpochDay: coveredStart ?? firstMonday,
            coveredEndEpochDay: coveredEnd ?? lastSunday, checkedAt: checkedAt ?? now, timeZoneId: zone, warnings: warnings)
        return Fixture(contracts: contracts, convention: convention, source: source, premiums: slices.map {
            .init(slice: $0, sourceId: "rules-snapshot-test", reliable: true, nightRule: night,
                  holidayScope: .init(jurisdiction: .commonFrance, complete: true, postalCode: nil, warning: nil))
        }, now: now)
    }
    private func payrollRules(night: Double? = nil, sunday: Double? = nil) -> PayrollRulesV2 {
        PayrollRulesV2(weeklyRegularMinutes: 2100,
            overtimeTiers: [OvertimeTierV2(fromMinutes: 2100, toMinutes: nil, multiplier: 1.25)],
            nightMultiplier: night, sundayMultiplier: sunday)
    }
    private func session(_ id: String, _ day: Int64, _ hour: Int, _ endDay: Int64, _ endHour: Int,
                         zone: String = "UTC", employer: String? = "company", pauses: [PaidPauseFactV2] = []) -> SalarySessionFactV2 {
        .init(id: id, entry: date(day, hour, zone: zone), exit: date(endDay, endHour, zone: zone), employerId: employer, pauses: pauses)
    }
    private func date(_ day: Int64, _ hour: Int, _ minute: Int = 0, zone: String = "UTC") -> Date {
        var utc = Calendar(identifier: .gregorian); utc.timeZone = TimeZone(secondsFromGMT: 0)!
        var parts = utc.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: Double(day) * 86400))
        parts.hour = hour; parts.minute = minute; parts.second = 0
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: zone)!
        return calendar.date(from: parts)!
    }
    private func epochDay(_ year: Int, _ month: Int, _ day: Int) -> Int64 {
        var utc = Calendar(identifier: .gregorian); utc.timeZone = TimeZone(secondsFromGMT: 0)!
        return Int64(utc.date(from: DateComponents(year: year, month: month, day: day))!.timeIntervalSince1970 / 86400)
    }
}
