import Foundation
import XCTest

final class SettingsSectionOrderV2Tests: XCTestCase {
    private func source(_ relativePath: String) throws -> String {
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while directory.path != "/" {
            let candidate = directory.appendingPathComponent(relativePath)
            if FileManager.default.fileExists(atPath: candidate.path) {
                return try String(contentsOf: candidate, encoding: .utf8)
            }
            directory.deleteLastPathComponent()
        }
        throw NSError(
            domain: "SettingsSectionOrderV2",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: "Missing repository source: \(relativePath)"]
        )
    }

    func testSettingsKeepV2FunctionalOrder() throws {
        let content = try source("ios/HPTravail/HPTravail/ContentView.swift")
        let settings = try XCTUnwrap(content.range(of: "private var settingsView: some View"))
        let tail = String(content[settings.lowerBound...])

        let application = try XCTUnwrap(tail.range(of: "Section(\"Application\")"))
        let account = try XCTUnwrap(tail.range(of: "Section(\"Compte & sécurité\")"))
        let pointage = try XCTUnwrap(tail.range(of: "Section(\"Pointage & lieux\")"))
        let celestial = try XCTUnwrap(tail.range(of: "Section(\"Système céleste\")"))
        let appearance = try XCTUnwrap(tail.range(of: "Section(\"Apparence\")"))

        XCTAssertLessThan(application.lowerBound, account.lowerBound)
        XCTAssertLessThan(account.lowerBound, pointage.lowerBound)
        XCTAssertLessThan(pointage.lowerBound, celestial.lowerBound)
        XCTAssertLessThan(celestial.lowerBound, appearance.lowerBound)
        XCTAssertTrue(tail.contains("CFBundleShortVersionString"))
        XCTAssertTrue(tail.contains("CFBundleVersion"))

        let project = try source("ios/HPTravail/project.yml")
        XCTAssertTrue(project.contains("MARKETING_VERSION: \"1.9\""))
        XCTAssertTrue(project.contains("CURRENT_PROJECT_VERSION: \"9\""))
    }
}
