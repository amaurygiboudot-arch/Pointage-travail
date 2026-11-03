#if !SWIFT_PACKAGE
import XCTest

/// The native test target compiles the exact app Combine facade and UIKit exporter sources.
final class SalaryAccountLifecycleV2Tests: XCTestCase {
    @MainActor
    func testFacadeImmediatelyMasksCachedDraftsAndRejectsMutationsBeforeUIRefresh() {
        var identity: String? = "uid:synthetic-A"
        SalaryAccountSessionV2.configure { identity }
        defer { SalaryAccountSessionV2.configure { nil } }
        let store = SalaryV2Store(companiesProvider: {
            .init(companies: [.init(id: "same-company", name: "Synthetic A", siret: "12345678901234")],
                  reliable: true, repairedFromBackup: false, warnings: [])
        })
        store.contractHourlyRateText = "16"
        store.incomeTaxSource = "Synthetic private source"
        XCTAssertEqual(store.companies.companies.count, 1)
        XCTAssertEqual(store.contractHourlyRateText, "16")
        identity = "uid:synthetic-B"
        // No reload, notification or onChange has run. Every public cached getter is guarded.
        XCTAssertTrue(store.companies.companies.isEmpty)
        XCTAssertFalse(store.companies.reliable)
        XCTAssertNil(store.selectedCompany)
        XCTAssertEqual(store.contractHourlyRateText, "")
        XCTAssertEqual(store.incomeTaxSource, "")
        XCTAssertFalse(store.snapshot.sourceReady)
        XCTAssertFalse(store.confirmEmploymentContract())
        XCTAssertFalse(store.confirmIncomeTaxRate())
        identity = "uid:synthetic-A"
        XCTAssertEqual(store.contractHourlyRateText, "")
        XCTAssertFalse(store.accountIsCurrent)
    }

    @MainActor
    func testExporterRejectsCapturedLeaseAfterInvalidationAndPurgesTemporaryFiles() throws {
        SalaryAccountSessionV2.configure { "uid:synthetic-A" }
        defer {
            SalaryV2PdfExporter.clearTemporaryExports()
            SalaryAccountSessionV2.configure { nil }
        }
        let lease = SalaryAccountSessionV2.capture()
        let period = YearMonthV2(year: 2026, month: 10)!
        let snapshot = SalaryWorkspaceResolverV2.resolve(period: period, reference: nil)
        let file = try SalaryV2PdfExporter.export(snapshot: snapshot, company: nil, lease: lease)
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        SalaryAccountSessionV2.invalidateSession()
        XCTAssertThrowsError(try SalaryV2PdfExporter.export(snapshot: snapshot, company: nil, lease: lease))
        SalaryV2PdfExporter.clearTemporaryExports()
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
    }
}
#endif
