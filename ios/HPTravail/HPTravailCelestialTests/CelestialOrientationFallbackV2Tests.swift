import XCTest
@testable import CelestialV2Contract

final class CelestialOrientationFallbackV2Tests: XCTestCase {
    func testInvalidHeadingUsesStableNorthInsteadOfInventingDirection() {
        let qualities: [CelestialHeadingQualityV2] = [
            .unknownAccuracy,
            .inaccurate,
            .unreliable,
            .stale,
            .unavailable
        ]

        for quality in qualities {
            XCTAssertEqual(
                CelestialHeadingPolicyV2.renderingHeadingDegrees(
                    headingDegrees: 123,
                    quality: quality
                ),
                0,
                accuracy: 0
            )
            XCTAssertTrue(
                CelestialHeadingPolicyV2.usesNeutralNorthMode(quality)
            )
        }
    }

    func testQualifiedHeadingIsNormalizedAndPreserved() {
        XCTAssertEqual(
            CelestialHeadingPolicyV2.renderingHeadingDegrees(
                headingDegrees: -10,
                quality: .valid
            ),
            350,
            accuracy: 1e-12
        )
        XCTAssertFalse(
            CelestialHeadingPolicyV2.usesNeutralNorthMode(.valid)
        )
    }
}
