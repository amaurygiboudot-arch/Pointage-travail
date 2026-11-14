import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class EmployerContributionProjectionV2Tests: XCTestCase {
    typealias P = EmployerContributionProjectionV2
    private struct Corpus: Decodable { let fixtures: [Fixture] }
    private struct Fixture: Decodable {
        struct Context: Decodable { let companyId: String?; let year: Int; let month: Int; let contributionGrossCents: Int64?; let reliable: Bool }
        struct Line: Decodable { let family: String; let state: String; let amountCents: Int64?; let source: String? }
        struct Reduction: Decodable { let state: String; let amountCents: Int64?; let source: String? }
        struct Expected: Decodable { let knownSubtotalBeforeReductionsCents: Int64?; let knownSubtotalAfterReductionsCents: Int64?; let coverageComplete: Bool; let monetaryReliable: Bool }
        let id: String; let context: Context; let lines: [Line]; let reductions: Reduction; let expected: Expected
    }
    func testSharedScenarioCorpus() throws {
        #if SWIFT_PACKAGE
        let url = try XCTUnwrap(Bundle.module.url(forResource: "employer_projection_parity_v2", withExtension: "json"))
        #else
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("Fixtures/employer_projection_parity_v2.json")
        #endif
        let corpus = try JSONDecoder().decode(Corpus.self, from: Data(contentsOf: url))
        XCTAssertEqual(corpus.fixtures.count, 23)
        for f in corpus.fixtures {
            let result = P.calculate(context: .init(companyId: f.context.companyId, year: f.context.year, month: f.context.month,
                                                     contributionGrossCents: f.context.contributionGrossCents, reliable: f.context.reliable),
                                     lines: try f.lines.map { .init(family: try XCTUnwrap(P.Family(rawValue: $0.family)), state: try XCTUnwrap(P.State(rawValue: $0.state)), amountCents: $0.amountCents, source: $0.source) },
                                     reductions: .init(state: try XCTUnwrap(P.State(rawValue: f.reductions.state)), amountCents: f.reductions.amountCents, source: f.reductions.source))
            XCTAssertEqual(result.knownSubtotalBeforeReductionsCents, f.expected.knownSubtotalBeforeReductionsCents, f.id)
            XCTAssertEqual(result.knownSubtotalAfterReductionsCents, f.expected.knownSubtotalAfterReductionsCents, f.id)
            XCTAssertEqual(result.coverageComplete, f.expected.coverageComplete, f.id)
            XCTAssertEqual(result.monetaryReliable, f.expected.monetaryReliable, f.id)
            XCTAssertEqual(result.lines.count, 10, f.id)
        }
    }
    func testDuplicateAndUnprovenLinesAreInvalidNotSilentlyDropped() {
        let context = P.Context(companyId: "A", year: 2026, month: 1, contributionGrossCents: 250000, reliable: true)
        let good = P.Line(family: .statutory, state: .known, amountCents: 100, source: "Fixture")
        let duplicated = P.calculate(context: context, lines: [good, good])
        XCTAssertEqual(duplicated.lines.first { $0.family == .statutory }?.state, .invalid)
        XCTAssertNil(duplicated.lines.first { $0.family == .statutory }?.amountCents)
        for lines in [[good, good], [good, .init(family: .mobility, state: .known, amountCents: 0, source: nil)],
                      [good, .init(family: .mobility, state: .missing, amountCents: 0, source: nil)]] {
            let result = P.calculate(context: context, lines: lines)
            XCTAssertNil(result.knownSubtotalBeforeReductionsCents)
            XCTAssertFalse(result.monetaryReliable)
        }
    }
    func testContextMatchingRequiresSameCompanyMonthAndGross() {
        let result = P.calculate(context: .init(companyId: "A", year: 2026, month: 1, contributionGrossCents: 250000, reliable: true),
                                 lines: [.init(family: .statutory, state: .known, amountCents: 100, source: "Fixture")])
        XCTAssertTrue(result.matches(companyId: "A", year: 2026, month: 1, gross: 2500))
        XCTAssertFalse(result.matches(companyId: "B", year: 2026, month: 1, gross: 2500))
        XCTAssertFalse(result.matches(companyId: nil, year: 2026, month: 1, gross: 2500))
        XCTAssertFalse(result.matches(companyId: "A", year: 2026, month: 2, gross: 2500))
        XCTAssertFalse(result.matches(companyId: "A", year: 2026, month: 1, gross: 2500.01))
    }
}
