import XCTest

final class SalaryConventionCoverageV2Tests: XCTestCase {
    func testJanuary1970UsesCivilUTCEpochDays() throws {
        let range = try XCTUnwrap(SalaryConventionCoverageResolverV2.monthEpochDayRange(period(1970, 1)))
        XCTAssertEqual(range.start, 0)
        XCTAssertEqual(range.end, 30)
    }

    func testLeapFebruaryHasTwentyNineCivilDays() throws {
        let range = try XCTUnwrap(SalaryConventionCoverageResolverV2.monthEpochDayRange(period(2028, 2)))
        XCTAssertEqual(range.end - range.start + 1, 29)
    }

    func testDecemberCrossesYearWithoutLosingADay() throws {
        let range = try XCTUnwrap(SalaryConventionCoverageResolverV2.monthEpochDayRange(period(2026, 12)))
        XCTAssertEqual(range.end - range.start + 1, 31)
    }

    func testSingleConfirmedVersionCoveringMonthIsExposedAsSingleSnapshot() throws {
        let month = period(2026, 9)
        let range = try XCTUnwrap(SalaryConventionCoverageResolverV2.monthEpochDayRange(month))
        let snapshot = rule(
            idcc: "1486",
            version: "v1",
            from: range.start - 10,
            to: range.end + 10
        )

        let result = SalaryConventionCoverageResolverV2.resolve(
            companyId: "company-a",
            period: month,
            companies: companyResult(idcc: "1486"),
            rules: ruleResult([snapshot])
        )

        XCTAssertTrue(result.sourceReliable)
        XCTAssertTrue(result.fullyCovered)
        XCTAssertEqual(result.segments.count, 1)
        XCTAssertEqual(result.segments[0].startEpochDay, range.start)
        XCTAssertEqual(result.segments[0].endEpochDay, range.end)
        XCTAssertEqual(result.singleSnapshotForWholePeriod?.versionId, "v1")
        XCTAssertFalse(result.requiresMultipleRuleVersions)
        XCTAssertTrue(result.warnings.isEmpty)
    }

    func testReliableRepairedRuleStoreWarningRemainsVisible() throws {
        let month = period(2026, 9)
        let range = try XCTUnwrap(SalaryConventionCoverageResolverV2.monthEpochDayRange(month))
        let snapshot = rule(idcc: "1486", version: "v1", from: range.start, to: range.end)
        let stored = SalaryConventionRuleReadResultV2(
            snapshots: [snapshot],
            reliable: true,
            repairedFromBackup: true,
            warnings: [SalaryConventionRuleStoreV2.repairedWarning]
        )

        let result = SalaryConventionCoverageResolverV2.resolve(
            companyId: "company-a",
            period: month,
            companies: companyResult(idcc: "1486"),
            rules: stored
        )

        XCTAssertTrue(result.sourceReliable)
        XCTAssertTrue(result.fullyCovered)
        XCTAssertNotNil(result.singleSnapshotForWholePeriod)
        XCTAssertEqual(result.warnings, [SalaryConventionRuleStoreV2.repairedWarning])
    }

    func testTwoContiguousVersionsRemainDistinctAndBlockSingleRuleShortcut() throws {
        let month = period(2026, 9)
        let range = try XCTUnwrap(SalaryConventionCoverageResolverV2.monthEpochDayRange(month))
        let split = range.start + 14
        let first = rule(idcc: "1486", version: "v1", from: range.start - 20, to: split)
        let second = rule(idcc: "1486", version: "v2", from: split + 1, to: nil)

        let result = SalaryConventionCoverageResolverV2.resolve(
            companyId: "company-a",
            period: month,
            companies: companyResult(idcc: "1486"),
            rules: ruleResult([first, second])
        )

        XCTAssertTrue(result.sourceReliable)
        XCTAssertTrue(result.fullyCovered)
        XCTAssertEqual(result.segments.map { $0.snapshot.versionId }, ["v1", "v2"])
        XCTAssertNil(result.singleSnapshotForWholePeriod)
        XCTAssertTrue(result.requiresMultipleRuleVersions)
        XCTAssertEqual(result.warnings, [SalaryConventionCoverageResolverV2.multipleVersionsWarning])
    }

    func testGapBetweenVersionsDoesNotFallbackToAnotherVersion() throws {
        let month = period(2026, 9)
        let range = try XCTUnwrap(SalaryConventionCoverageResolverV2.monthEpochDayRange(month))
        let first = rule(idcc: "1486", version: "v1", from: range.start, to: range.start + 9)
        let second = rule(idcc: "1486", version: "v2", from: range.start + 11, to: range.end)

        let result = SalaryConventionCoverageResolverV2.resolve(
            companyId: "company-a",
            period: month,
            companies: companyResult(idcc: "1486"),
            rules: ruleResult([first, second])
        )

        XCTAssertTrue(result.sourceReliable)
        XCTAssertFalse(result.fullyCovered)
        XCTAssertNil(result.singleSnapshotForWholePeriod)
        XCTAssertEqual(result.warnings, [SalaryConventionCoverageResolverV2.coverageWarning])
    }

    func testLaterVersionIsNotUsedAsHistoricalFallback() throws {
        let september = period(2026, 9)
        let octoberRange = try XCTUnwrap(SalaryConventionCoverageResolverV2.monthEpochDayRange(period(2026, 10)))
        let future = rule(idcc: "1486", version: "future", from: octoberRange.start, to: nil)

        let result = SalaryConventionCoverageResolverV2.resolve(
            companyId: "company-a",
            period: september,
            companies: companyResult(idcc: "1486"),
            rules: ruleResult([future])
        )

        XCTAssertTrue(result.sourceReliable)
        XCTAssertFalse(result.fullyCovered)
        XCTAssertTrue(result.segments.isEmpty)
        XCTAssertNil(result.singleSnapshotForWholePeriod)
    }

    func testIDCCIsNormalizedBeforeHistoryLookup() throws {
        let month = period(2026, 9)
        let range = try XCTUnwrap(SalaryConventionCoverageResolverV2.monthEpochDayRange(month))
        let snapshot = rule(idcc: "0044", version: "v1", from: range.start, to: range.end)

        let result = SalaryConventionCoverageResolverV2.resolve(
            companyId: "company-a",
            period: month,
            companies: companyResult(idcc: "44"),
            rules: ruleResult([snapshot])
        )

        XCTAssertEqual(result.idcc, "0044")
        XCTAssertTrue(result.fullyCovered)
        XCTAssertEqual(result.singleSnapshotForWholePeriod?.versionId, "v1")
    }

    func testBlankCompanyIDCCFailsClosed() {
        let result = SalaryConventionCoverageResolverV2.resolve(
            companyId: "company-a",
            period: period(2026, 9),
            companies: companyResult(idcc: "  "),
            rules: ruleResult([])
        )

        XCTAssertFalse(result.sourceReliable)
        XCTAssertFalse(result.fullyCovered)
        XCTAssertNil(result.idcc)
        XCTAssertEqual(result.warnings, [SalaryConventionCoverageResolverV2.idccWarning])
    }

    func testUnreliableCompanyStoreFailsClosedBeforeUsingCompanyFacts() {
        let companies = SalaryCompanyReadResultV2(
            companies: [company(idcc: "1486")],
            reliable: false,
            repairedFromBackup: false,
            warnings: ["corrupt"]
        )

        let result = SalaryConventionCoverageResolverV2.resolve(
            companyId: "company-a",
            period: period(2026, 9),
            companies: companies,
            rules: ruleResult([])
        )

        XCTAssertFalse(result.sourceReliable)
        XCTAssertEqual(result.warnings, [SalaryConventionCoverageResolverV2.companyStoreWarning])
    }

    func testUnreliableRuleStoreFailsClosedEvenWhenReadableSnapshotsExist() throws {
        let month = period(2026, 9)
        let range = try XCTUnwrap(SalaryConventionCoverageResolverV2.monthEpochDayRange(month))
        let stored = SalaryConventionRuleReadResultV2(
            snapshots: [rule(idcc: "1486", version: "v1", from: range.start, to: range.end)],
            reliable: false,
            repairedFromBackup: false,
            warnings: ["corrupt"]
        )

        let result = SalaryConventionCoverageResolverV2.resolve(
            companyId: "company-a",
            period: month,
            companies: companyResult(idcc: "1486"),
            rules: stored
        )

        XCTAssertFalse(result.sourceReliable)
        XCTAssertTrue(result.segments.isEmpty)
        XCTAssertEqual(result.warnings, [SalaryConventionCoverageResolverV2.ruleStoreWarning])
    }

    func testUnknownSelectedCompanyIsNeverReassignedToAnotherCompany() {
        let result = SalaryConventionCoverageResolverV2.resolve(
            companyId: "company-b",
            period: period(2026, 9),
            companies: companyResult(idcc: "1486"),
            rules: ruleResult([])
        )

        XCTAssertEqual(result.companyId, "company-b")
        XCTAssertFalse(result.sourceReliable)
        XCTAssertEqual(result.warnings, [SalaryConventionCoverageResolverV2.companyWarning])
    }

    private func period(_ year: Int, _ month: Int) -> YearMonthV2 {
        YearMonthV2(year: year, month: month)!
    }

    private func company(idcc: String) -> SalaryCompanyV2 {
        SalaryCompanyV2(
            id: "company-a",
            name: "Entreprise A",
            siret: "12345678901234",
            idcc: idcc
        )
    }

    private func companyResult(idcc: String) -> SalaryCompanyReadResultV2 {
        SalaryCompanyReadResultV2(
            companies: [company(idcc: idcc)],
            reliable: true,
            repairedFromBackup: false,
            warnings: []
        )
    }

    private func ruleResult(_ snapshots: [SalaryConventionRuleSnapshotV2]) -> SalaryConventionRuleReadResultV2 {
        SalaryConventionRuleReadResultV2(
            snapshots: snapshots,
            reliable: true,
            repairedFromBackup: false,
            warnings: []
        )
    }

    private func rule(
        idcc: String,
        version: String,
        from: Int64,
        to: Int64?
    ) -> SalaryConventionRuleSnapshotV2 {
        SalaryConventionRuleSnapshotV2(
            idcc: idcc,
            versionId: version,
            sourceId: "KALI-test",
            effectiveFromEpochDay: from,
            effectiveToEpochDay: to,
            rules: PayrollRulesV2(weeklyRegularMinutes: 35 * 60),
            checkedAtMs: 1,
            note: nil
        )
    }
}
