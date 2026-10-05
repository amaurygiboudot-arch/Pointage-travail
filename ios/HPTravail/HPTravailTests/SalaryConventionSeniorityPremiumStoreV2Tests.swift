import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryConventionSeniorityPremiumStoreV2Tests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "SalaryConventionSeniorityPremiumStoreV2Tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        suiteName = nil
        super.tearDown()
    }

    private func date(_ year: Int, _ month: Int, _ day: Int) -> PayrollCivilDateV2 {
        PayrollCivilDateV2(year: year, month: month, day: day)!
    }

    private func rule(
        idcc: String = "0292",
        ruleId: String = "seniority-test"
    ) -> SalaryConventionSeniorityPremiumV2.Rule {
        .init(
            idcc: idcc,
            ruleId: ruleId,
            effectiveFrom: date(2025, 1, 1),
            effectiveTo: nil,
            classification: ConventionClassificationV2(),
            basis: .actualMonthlyBase,
            steps: [.init(years: 3, rate: 0.024, fixedMonthlyAmount: nil)],
            includeConfirmedMonthlySupplement: false,
            source: "KALI-test",
            extensionStatus: .extended,
            extensionEffectiveFrom: date(2025, 1, 1)
        )
    }

    func testMissingStoreIsReadableButDoesNotProveNoRule() {
        let stored = SalaryConventionSeniorityPremiumStoreV2.readConfirmed(defaults: defaults)

        XCTAssertTrue(stored.reliable)
        XCTAssertTrue(stored.rules.isEmpty)
        XCTAssertTrue(stored.warnings.isEmpty)
    }

    func testSaveConfirmedNormalizesIdccAndReloadsRule() {
        XCTAssertTrue(
            SalaryConventionSeniorityPremiumStoreV2.saveConfirmed(
                rule(idcc: "00292"),
                defaults: defaults
            )
        )

        let stored = SalaryConventionSeniorityPremiumStoreV2.readConfirmed(defaults: defaults)
        XCTAssertTrue(stored.reliable)
        XCTAssertEqual(stored.rules.count, 1)
        XCTAssertEqual(stored.rules.first?.idcc, "292")
        XCTAssertEqual(stored.rules.first?.ruleId, "seniority-test")
    }

    func testCorruptStoreFailsClosed() {
        defaults.set("{not-json", forKey: "salary_convention_seniority_premium_v2.confirmed_rules")

        let stored = SalaryConventionSeniorityPremiumStoreV2.readConfirmed(defaults: defaults)

        XCTAssertFalse(stored.reliable)
        XCTAssertTrue(stored.rules.isEmpty)
        XCTAssertTrue(stored.warnings.contains(SalaryConventionSeniorityPremiumStoreV2.storageWarning))
    }

    func testRulesFilterByNormalizedIdccWithoutTurningEmptyIntoAbsenceProof() {
        XCTAssertTrue(
            SalaryConventionSeniorityPremiumStoreV2.saveConfirmed(
                rule(idcc: "0292", ruleId: "a"),
                defaults: defaults
            )
        )
        XCTAssertTrue(
            SalaryConventionSeniorityPremiumStoreV2.saveConfirmed(
                rule(idcc: "1486", ruleId: "b"),
                defaults: defaults
            )
        )

        let selected = SalaryConventionSeniorityPremiumStoreV2.rules(
            idcc: "00292",
            defaults: defaults
        )
        XCTAssertTrue(selected.reliable)
        XCTAssertEqual(selected.rules.map(\.ruleId), ["a"])

        let absent = SalaryConventionSeniorityPremiumStoreV2.rules(
            idcc: "9999",
            defaults: defaults
        )
        XCTAssertTrue(absent.reliable)
        XCTAssertTrue(absent.rules.isEmpty)
    }
}
