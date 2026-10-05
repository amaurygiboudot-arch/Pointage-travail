import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryConventionSeniorityPremiumBridgeV2Tests: XCTestCase {
    private let classification = ConventionClassificationV2(coefficient: 800)

    private func date(_ year: Int, _ month: Int, _ day: Int) -> PayrollCivilDateV2 {
        PayrollCivilDateV2(year: year, month: month, day: day)!
    }

    func testEmptyReliableStoreNeverActivatesBuiltInRule() {
        let selection = SalaryConventionSeniorityPremiumBridgeV2.selectOfficialRuntimeSource(
            stored: .init(rules: [], reliable: true, warnings: []),
            coverage: .init(state: .incomplete, record: nil, reliable: false, warnings: []),
            idcc: "0292",
            classification: classification,
            referenceDate: date(2026, 9, 1),
            accoKnowledge: .confirmedAbsence
        )

        XCTAssertFalse(selection.reliable)
        XCTAssertFalse(selection.confirmedNoRule)
        XCTAssertTrue(selection.rules.isEmpty)
    }

    func testStoredRuleRequiresKaliAndConfirmedAccoAbsence() {
        let rule = seniorityRule()
        let stored = SalaryConventionSeniorityPremiumStoreV2.ReadResult(
            rules: [rule],
            reliable: true,
            warnings: []
        )
        let blockedByKali = SalaryConventionSeniorityPremiumBridgeV2.selectOfficialRuntimeSource(
            stored: stored,
            coverage: coverage(state: .confirmedRules, authorities: []),
            idcc: "292",
            classification: classification,
            referenceDate: date(2026, 9, 1),
            accoKnowledge: .confirmedAbsence
        )
        XCTAssertFalse(blockedByKali.reliable)

        let kali = coverage(state: .confirmedRules, authorities: [.kali])
        let blockedByAcco = SalaryConventionSeniorityPremiumBridgeV2.selectOfficialRuntimeSource(
            stored: stored,
            coverage: kali,
            idcc: "292",
            classification: classification,
            referenceDate: date(2026, 9, 1),
            accoKnowledge: .unknown
        )
        XCTAssertFalse(blockedByAcco.reliable)

        let confirmed = SalaryConventionSeniorityPremiumBridgeV2.selectOfficialRuntimeSource(
            stored: stored,
            coverage: kali,
            idcc: "292",
            classification: classification,
            referenceDate: date(2026, 9, 1),
            accoKnowledge: .confirmedAbsence
        )
        XCTAssertTrue(confirmed.reliable)
        XCTAssertFalse(confirmed.confirmedNoRule)
        XCTAssertEqual(confirmed.rules, [rule])
    }

    func testConfirmedZeroRightAlsoRequiresAccoAbsence() {
        let stored = SalaryConventionSeniorityPremiumStoreV2.ReadResult(
            rules: [],
            reliable: true,
            warnings: []
        )
        let noRule = coverage(state: .confirmedNoRule, authorities: [.kali])

        let blocked = SalaryConventionSeniorityPremiumBridgeV2.selectOfficialRuntimeSource(
            stored: stored,
            coverage: noRule,
            idcc: "292",
            classification: classification,
            referenceDate: date(2026, 9, 1),
            accoKnowledge: .unknown
        )
        XCTAssertFalse(blocked.reliable)
        XCTAssertFalse(blocked.confirmedNoRule)

        let confirmed = SalaryConventionSeniorityPremiumBridgeV2.selectOfficialRuntimeSource(
            stored: stored,
            coverage: noRule,
            idcc: "292",
            classification: classification,
            referenceDate: date(2026, 9, 1),
            accoKnowledge: .confirmedAbsence
        )
        XCTAssertTrue(confirmed.reliable)
        XCTAssertTrue(confirmed.confirmedNoRule)
    }

    func testCorruptSourceKnowledgeBlocksEvenIfAccoValueLooksAbsent() {
        let selection = SalaryConventionSeniorityPremiumBridgeV2.selectOfficialRuntimeSource(
            stored: .init(rules: [seniorityRule()], reliable: true, warnings: []),
            coverage: coverage(state: .confirmedRules, authorities: [.kali]),
            idcc: "292",
            classification: classification,
            referenceDate: date(2026, 9, 1),
            accoKnowledge: .confirmedAbsence,
            sourceKnowledgeReliable: false,
            sourceKnowledgeWarnings: ["stockage source incohérent"]
        )

        XCTAssertFalse(selection.reliable)
        XCTAssertTrue(selection.rules.isEmpty)
        XCTAssertTrue(selection.warnings.contains { $0.contains("stockage source incohérent") })
    }

    private func seniorityRule() -> SalaryConventionSeniorityPremiumV2.Rule {
        .init(
            idcc: "292",
            ruleId: "kali_292_seniority_800",
            effectiveFrom: date(2011, 6, 28),
            effectiveTo: nil,
            classification: classification,
            basis: .actualMonthlyBase,
            steps: [.init(years: 3, rate: 0.024, fixedMonthlyAmount: nil)],
            includeConfirmedMonthlySupplement: true,
            source: "Légifrance KALI — règle vérifiée",
            extensionStatus: .extended,
            extensionEffectiveFrom: date(2012, 1, 5)
        )
    }

    private func coverage(
        state: ConventionMatterCoverageV2.State,
        authorities: Set<ConventionMatterCoverageV2.Authority>
    ) -> ConventionMatterCoverageV2.Snapshot {
        let record = ConventionMatterCoverageV2.Record(
            idcc: "292",
            matter: .seniorityPremium,
            effectiveFrom: date(2026, 9, 1),
            effectiveTo: date(2026, 9, 30),
            classification: classification,
            professionalStatus: nil,
            state: state,
            source: "Légifrance KALI — audit ancienneté",
            checkedAtMs: 1,
            authorities: authorities
        )
        return .init(
            state: state,
            record: record,
            reliable: state != .incomplete,
            warnings: []
        )
    }
}
