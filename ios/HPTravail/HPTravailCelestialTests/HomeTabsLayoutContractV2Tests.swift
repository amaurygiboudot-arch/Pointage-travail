import Foundation
import XCTest

/// Structural adapter contracts, not a recording or measurement of device frames.
final class HomeTabsLayoutContractV2Tests: XCTestCase {
    private func source(_ relativePath: String) throws -> String {
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while directory.path != "/" {
            let candidate = directory.appendingPathComponent(relativePath)
            if FileManager.default.fileExists(atPath: candidate.path) {
                return try String(contentsOf: candidate, encoding: .utf8)
            }
            directory.deleteLastPathComponent()
        }
        throw NSError(domain: "HomeTabsLayoutContractV2", code: 1,
            userInfo: [NSLocalizedDescriptionKey: "Missing repository source: \(relativePath)"])
    }
    private var homePath: String { "ios/HPTravail/HPTravail/CelestialV2/CelestialHomeView.swift" }
    private var fadePath: String { "ios/HPTravail/HPTravail/CelestialV2/Visual/HomeTabBarFadeV2.swift" }

    func testHomeKeepsNativeToolbarSlot() throws {
        let home = try source(homePath)
        XCTAssertTrue(home.contains(".toolbar(.visible, for: .tabBar)"))
        XCTAssertTrue(home.contains(".background(HomeTabBarFadeV2(isVisible: tabBarVisible))"))
        XCTAssertFalse(home.contains(".toolbar(tabBarVisible ? .visible : .hidden"))
    }
    func testFadeDoesNotMutateLayout() throws {
        let fade = try source(fadePath)
        XCTAssertTrue(fade.contains("bar.alpha = targetAlpha"))
        for mutation in ["bar.isHidden =", "bar.frame =", "bar.bounds =", "additionalSafeAreaInsets ="] {
            XCTAssertFalse(fade.contains(mutation), mutation)
        }
    }
    func testHiddenControlsAndLifecycleHaveExplicitHandling() throws {
        let fade = try source(fadePath)
        XCTAssertTrue(fade.contains("bar.isUserInteractionEnabled = requestedVisible && originalInteraction"))
        XCTAssertTrue(fade.contains("bar.accessibilityElementsHidden = !requestedVisible || originalAccessibilityHidden"))
        XCTAssertTrue(fade.contains("UIAccessibility.isReduceMotionEnabled"))
        XCTAssertTrue(fade.contains("override func viewWillDisappear"))
        XCTAssertTrue(fade.contains("static func dismantleUIViewController"))
        XCTAssertTrue(fade.contains("controller.stopAndRestore()"))
        XCTAssertTrue(fade.contains("bar.alpha = originalAlpha"))
    }
    func testAndroidAlsoKeepsMeasuredSpace() throws {
        let main = try source("app/src/main/java/com/amaury/pointage/MainActivity.kt")
        XCTAssertTrue(main.contains("navigationTabs.visibility = View.INVISIBLE"))
        XCTAssertFalse(main.contains("navigationTabs.visibility = View.GONE"))
    }
}
