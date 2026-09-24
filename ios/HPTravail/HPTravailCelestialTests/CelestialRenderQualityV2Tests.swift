import XCTest
@testable import CelestialV2Contract

final class CelestialRenderQualityV2Tests: XCTestCase {
    func testCriticalConstraintsReduceOnlyVisualQuality() {
        XCTAssertEqual(
            CelestialRenderQualityPolicyV2.resolve(
                lowPowerMode: true,
                thermalConstrained: false,
                physicalMemoryBytes: 8 * 1_073_741_824
            ),
            .reduced
        )
        XCTAssertEqual(
            CelestialRenderQualityPolicyV2.resolve(
                lowPowerMode: false,
                thermalConstrained: true,
                physicalMemoryBytes: 8 * 1_073_741_824
            ),
            .reduced
        )
    }

    func testMemoryTiersAreStable() {
        XCTAssertEqual(
            CelestialRenderQualityPolicyV2.resolve(
                lowPowerMode: false,
                thermalConstrained: false,
                physicalMemoryBytes: 2 * 1_073_741_824
            ),
            .reduced
        )
        XCTAssertEqual(
            CelestialRenderQualityPolicyV2.resolve(
                lowPowerMode: false,
                thermalConstrained: false,
                physicalMemoryBytes: 4 * 1_073_741_824
            ),
            .balanced
        )
        XCTAssertEqual(
            CelestialRenderQualityPolicyV2.resolve(
                lowPowerMode: false,
                thermalConstrained: false,
                physicalMemoryBytes: 6 * 1_073_741_824
            ),
            .high
        )
    }

    func testReducedTierStillKeepsAtmosphericAnimation() {
        XCTAssertEqual(CelestialRenderQualityV2.reduced.maxCloudClusters, 5)
        XCTAssertEqual(
            CelestialRenderQualityV2.reduced.cloudAnimationInterval,
            10,
            accuracy: 0
        )
        XCTAssertGreaterThan(CelestialRenderQualityV2.reduced.cloudBlurScale, 0)
    }
}
