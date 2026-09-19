import XCTest
#if SWIFT_PACKAGE
@testable import SalaryV2Contract
#endif

final class EmployerWorkforceContributionsV2Tests: XCTestCase {
    private let cappedFnal = EmployerWorkforceContributionsV2.FnalTreatment.capped0Point1Percent
    private let uncappedFnal = EmployerWorkforceContributionsV2.FnalTreatment.uncapped0Point5Percent
    private let standardTraining = EmployerWorkforceContributionsV2.TrainingTreatment.standard

    func testUnder11StandardCaseUsesCappedFnalAndTrainingRate() {
        let result = EmployerWorkforceContributionsV2.calculate(
            grossSocial: 5_000,
            applicableMonthlyCeiling: 4_005,
            year: 2026,
            band: .under11,
            fnalTreatment: cappedFnal,
            trainingTreatment: standardTraining
        )

        XCTAssertTrue(result.complete)
        XCTAssertEqual(result.fnalAmount ?? -1, 4.005, accuracy: 0.001)
        XCTAssertEqual(result.trainingAmount ?? -1, 27.50, accuracy: 0.001)
    }

    func testFrom11To49StandardCaseUsesCappedFnalAndOnePercentTraining() {
        let result = EmployerWorkforceContributionsV2.calculate(
            grossSocial: 5_000,
            applicableMonthlyCeiling: 4_005,
            year: 2026,
            band: .from11To49,
            fnalTreatment: cappedFnal,
            trainingTreatment: standardTraining
        )

        XCTAssertTrue(result.complete)
        XCTAssertEqual(result.fnalAmount ?? -1, 4.005, accuracy: 0.001)
        XCTAssertEqual(result.trainingAmount ?? -1, 50, accuracy: 0.001)
    }

    func testAtLeast50CanUseUncappedFnalAndOnePercentTraining() {
        let result = EmployerWorkforceContributionsV2.calculate(
            grossSocial: 5_000,
            applicableMonthlyCeiling: 4_005,
            year: 2026,
            band: .atLeast50,
            fnalTreatment: uncappedFnal,
            trainingTreatment: standardTraining
        )

        XCTAssertTrue(result.complete)
        XCTAssertEqual(result.fnalAmount ?? -1, 25, accuracy: 0.001)
        XCTAssertEqual(result.trainingAmount ?? -1, 50, accuracy: 0.001)
    }

    func testAtLeast50CanStillUseCappedFnalWhenLegalRegimeConfirmsIt() {
        let result = EmployerWorkforceContributionsV2.calculate(
            grossSocial: 5_000,
            applicableMonthlyCeiling: 4_005,
            year: 2026,
            band: .atLeast50,
            fnalTreatment: cappedFnal,
            trainingTreatment: standardTraining
        )

        XCTAssertTrue(result.complete)
        XCTAssertEqual(result.fnalAmount ?? -1, 4.005, accuracy: 0.001)
        XCTAssertEqual(result.trainingAmount ?? -1, 50, accuracy: 0.001)
    }

    func testConfirmedTrainingExemptionIsARealZero() {
        let result = EmployerWorkforceContributionsV2.calculate(
            grossSocial: 2_500,
            applicableMonthlyCeiling: 4_005,
            year: 2026,
            band: .under11,
            fnalTreatment: cappedFnal,
            trainingTreatment: .exemptConfirmed
        )

        XCTAssertTrue(result.complete)
        XCTAssertEqual(result.fnalAmount ?? -1, 2.5, accuracy: 0.001)
        XCTAssertEqual(result.trainingAmount ?? -1, 0, accuracy: 0)
    }

    func testUnknownFnalRegimeIsNeverInferredFromWorkforceBand() {
        let result = EmployerWorkforceContributionsV2.calculate(
            grossSocial: 2_500,
            applicableMonthlyCeiling: 4_005,
            year: 2026,
            band: .atLeast50,
            fnalTreatment: nil,
            trainingTreatment: standardTraining
        )

        XCTAssertFalse(result.complete)
        XCTAssertNil(result.fnalAmount)
        XCTAssertEqual(result.trainingAmount ?? -1, 25, accuracy: 0.001)
        XCTAssertNil(result.totalEmployerAmount)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("FNAL") })
    }

    func testUnknownTrainingApplicabilityIsNeverTreatedAsStandard() {
        let result = EmployerWorkforceContributionsV2.calculate(
            grossSocial: 2_500,
            applicableMonthlyCeiling: 4_005,
            year: 2026,
            band: .under11,
            fnalTreatment: cappedFnal,
            trainingTreatment: nil
        )

        XCTAssertFalse(result.complete)
        XCTAssertEqual(result.fnalAmount ?? -1, 2.5, accuracy: 0.001)
        XCTAssertNil(result.trainingAmount)
        XCTAssertNil(result.totalEmployerAmount)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("formation") })
    }

    func testUncappedFnalDoesNotRequireIrrelevantCeiling() {
        let result = EmployerWorkforceContributionsV2.calculate(
            grossSocial: 5_000,
            applicableMonthlyCeiling: nil,
            year: 2026,
            band: .atLeast50,
            fnalTreatment: uncappedFnal,
            trainingTreatment: standardTraining
        )

        XCTAssertTrue(result.complete)
        XCTAssertEqual(result.fnalAmount ?? -1, 25, accuracy: 0.001)
    }

    func testMissingBandBlocksStandardTrainingWithoutErasingKnownFnal() {
        let result = EmployerWorkforceContributionsV2.calculate(
            grossSocial: 2_500,
            applicableMonthlyCeiling: 4_005,
            year: 2026,
            band: nil,
            fnalTreatment: cappedFnal,
            trainingTreatment: standardTraining
        )

        XCTAssertFalse(result.complete)
        XCTAssertEqual(result.fnalAmount ?? -1, 2.5, accuracy: 0.001)
        XCTAssertNil(result.trainingAmount)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("effectif") })
    }

    func testInvalidGrossNeverProducesWorkforceEmployerAmounts() {
        for gross in [-1.0, .nan, .infinity, -.infinity] {
            let result = EmployerWorkforceContributionsV2.calculate(
                grossSocial: gross,
                applicableMonthlyCeiling: 4_005,
                year: 2026,
                band: .under11,
                fnalTreatment: cappedFnal,
                trainingTreatment: standardTraining
            )

            XCTAssertFalse(result.complete)
            XCTAssertNil(result.fnalAmount)
            XCTAssertNil(result.trainingAmount)
            XCTAssertNil(result.totalEmployerAmount)
            XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("assiette") })
        }
    }

    func testResolvedEmployerFactsRequireBothExplicitTreatments() {
        let completeRecord = EmployerWorkforceContributionsV2.Record(
            id: "a",
            band: .atLeast50,
            effectiveFrom: .init(year: 2026, month: 1),
            source: "DSN",
            fnalTreatment: cappedFnal,
            trainingTreatment: standardTraining
        )
        let complete = EmployerWorkforceContributionsV2.resolve(
            records: [completeRecord],
            period: .init(year: 2026, month: 9)
        )

        XCTAssertTrue(complete.reliable)
        XCTAssertEqual(complete.fnalTreatment, cappedFnal)
        XCTAssertEqual(complete.trainingTreatment, standardTraining)
        XCTAssertTrue(complete.warnings.isEmpty)
    }

    func testLegacyRecordKeepsKnownFactsButStaysFailClosed() {
        let legacyRecord = EmployerWorkforceContributionsV2.Record(
            id: "legacy",
            band: .atLeast50,
            effectiveFrom: .init(year: 2026, month: 1),
            source: "DSN"
        )
        let migratedUnknown = EmployerWorkforceContributionsV2.resolve(
            records: [legacyRecord],
            period: .init(year: 2026, month: 9)
        )

        XCTAssertFalse(migratedUnknown.reliable)
        XCTAssertEqual(migratedUnknown.band, .atLeast50)
        XCTAssertNil(migratedUnknown.fnalTreatment)
        XCTAssertNil(migratedUnknown.trainingTreatment)
        XCTAssertTrue(migratedUnknown.warnings.contains { $0.localizedCaseInsensitiveContains("FNAL") })
        XCTAssertTrue(migratedUnknown.warnings.contains { $0.localizedCaseInsensitiveContains("formation") })
    }

    func testPartialResolvedFactsRemainVisibleWithoutClaimingReliability() {
        let record = EmployerWorkforceContributionsV2.Record(
            id: "partial",
            band: .under11,
            effectiveFrom: .init(year: 2026, month: 1),
            source: "DSN",
            fnalTreatment: cappedFnal,
            trainingTreatment: nil
        )
        let resolved = EmployerWorkforceContributionsV2.resolve(
            records: [record],
            period: .init(year: 2026, month: 9)
        )

        XCTAssertFalse(resolved.reliable)
        XCTAssertEqual(resolved.fnalTreatment, cappedFnal)
        XCTAssertNil(resolved.trainingTreatment)
        XCTAssertTrue(resolved.warnings.contains { $0.localizedCaseInsensitiveContains("formation") })
    }

    func testOverlappingWorkforceFactsBlockResolution() {
        let first = EmployerWorkforceContributionsV2.Record(
            id: "a",
            band: .under11,
            effectiveFrom: .init(year: 2026, month: 1),
            source: "DSN",
            fnalTreatment: cappedFnal,
            trainingTreatment: standardTraining
        )
        let second = EmployerWorkforceContributionsV2.Record(
            id: "b",
            band: .from11To49,
            effectiveFrom: .init(year: 2026, month: 6),
            source: "DSN",
            fnalTreatment: cappedFnal,
            trainingTreatment: standardTraining
        )
        let result = EmployerWorkforceContributionsV2.resolve(
            records: [first, second],
            period: .init(year: 2026, month: 9)
        )

        XCTAssertFalse(result.reliable)
        XCTAssertNil(result.band)
        XCTAssertNil(result.fnalTreatment)
        XCTAssertNil(result.trainingTreatment)
        XCTAssertTrue(result.warnings.contains { $0.localizedCaseInsensitiveContains("chevauchent") })
    }
}
