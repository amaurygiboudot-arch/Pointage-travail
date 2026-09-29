import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class SalaryPayrollSourceKnowledgeV2Tests: XCTestCase {
    private var suite: String!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suite = "SalaryPayrollSourceKnowledgeV2Tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        defaults = nil
        suite = nil
        super.tearDown()
    }

    private func date(_ y: Int, _ m: Int, _ d: Int) -> PayrollCivilDateV2 {
        PayrollCivilDateV2(year: y, month: m, day: d)!
    }

    private func accoProof(
        siret: String = "44444026700011",
        exhaustive: Bool = true,
        scopeConfirmed: Bool = true
    ) -> SalaryPayrollSourceKnowledgeV2.Proof {
        .init(
            source: .acco,
            matter: .seniorityPremium,
            companyId: "company",
            idcc: "0292",
            subjectKey: nil,
            referenceFrom: date(2026, 9, 1),
            referenceTo: date(2026, 9, 30),
            officialCoverageThrough: date(2026, 9, 30),
            checkedAtMs: 100,
            officialScopeId: SalaryPayrollSourceKnowledgeV2.accoOfficialScopeId(siret)!,
            exhaustive: exhaustive,
            scopeConfirmed: scopeConfirmed,
            outcome: .noApplicableRule
        )
    }

    func testMissingProofNeverMeansConfirmedAbsence() {
        let result = SalaryPayrollSourceKnowledgeStoreV2.seniorityKnowledge(
            companyId: "company",
            idcc: "0292",
            referenceDate: date(2026, 9, 30),
            currentSiret: "44444026700011",
            defaults: defaults
        )
        XCTAssertTrue(result.reliable)
        XCTAssertNil(result.knowledge[.acco])
    }

    func testExhaustiveAccoAbsenceWithMatchingSiretIsConfirmed() {
        XCTAssertTrue(
            SalaryPayrollSourceKnowledgeStoreV2.record(
                accoProof(),
                defaults: defaults
            )
        )
        let result = SalaryPayrollSourceKnowledgeStoreV2.seniorityKnowledge(
            companyId: "company",
            idcc: "292",
            referenceDate: date(2026, 9, 30),
            currentSiret: "44444026700011",
            defaults: defaults
        )
        XCTAssertTrue(result.reliable)
        XCTAssertEqual(result.knowledge[.acco], .confirmedAbsence)
    }

    func testChangedSiretInvalidatesOldAccoAbsence() {
        XCTAssertTrue(
            SalaryPayrollSourceKnowledgeStoreV2.record(
                accoProof(),
                defaults: defaults
            )
        )
        let result = SalaryPayrollSourceKnowledgeStoreV2.seniorityKnowledge(
            companyId: "company",
            idcc: "0292",
            referenceDate: date(2026, 9, 30),
            currentSiret: "75050717000017",
            defaults: defaults
        )
        XCTAssertTrue(result.reliable)
        XCTAssertNil(result.knowledge[.acco])
    }

    func testNonExhaustiveOrUnconfirmedScopeNeverProvesAbsence() {
        for proof in [
            accoProof(exhaustive: false),
            accoProof(scopeConfirmed: false)
        ] {
            XCTAssertEqual(
                SalaryPayrollSourceKnowledgeV2.knowledgeFor(
                    proofs: [proof],
                    source: .acco,
                    matter: .seniorityPremium,
                    companyId: "company",
                    idcc: "0292",
                    referenceDate: date(2026, 9, 30)
                ),
                .unknown
            )
        }
    }

    func testCorruptJournalFailsClosed() {
        defaults.set("{not-json", forKey: "salary_payroll_source_knowledge_v2.proofs")
        let result = SalaryPayrollSourceKnowledgeStoreV2.seniorityKnowledge(
            companyId: "company",
            idcc: "0292",
            referenceDate: date(2026, 9, 30),
            currentSiret: "44444026700011",
            defaults: defaults
        )
        XCTAssertFalse(result.reliable)
        XCTAssertTrue(result.knowledge.isEmpty)
        XCTAssertTrue(result.warnings.contains(SalaryPayrollSourceKnowledgeStoreV2.storageWarning))
    }
}
