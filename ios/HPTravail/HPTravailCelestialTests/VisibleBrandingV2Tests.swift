import XCTest

final class VisibleBrandingV2Tests: XCTestCase {
    func testVisibleSettingsCopyUsesAGKGMGBrand() throws {
        let source = try String(
            contentsOf: URL(fileURLWithPath: #filePath)
                .deletingLastPathComponent()
                .deletingLastPathComponent()
                .appendingPathComponent("HPTravail/ContentView.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(source.contains("AGKGMG n'enregistre aucun temps payé"))
        XCTAssertTrue(source.contains("AGKGMG ne déduit jamais une pause"))
        XCTAssertTrue(source.contains("L'historique AGKGMG est illisible"))
        XCTAssertFalse(source.contains("HoraTrack n'enregistre aucun temps payé"))
        XCTAssertFalse(source.contains("HoraTrack ne déduit jamais une pause"))
        XCTAssertFalse(source.contains("L'historique HoraTrack est illisible"))
    }
}
