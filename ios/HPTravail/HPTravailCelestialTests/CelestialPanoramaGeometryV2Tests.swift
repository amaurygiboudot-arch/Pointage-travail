import XCTest
@testable import CelestialV2Contract

final class CelestialPanoramaGeometryV2Tests: XCTestCase {
    func testHorizonAndZenithMapToViewportEdges() {
        let horizon = CelestialPanoramaGeometryV2.normalized(
            position: LocalStarPositionV2(
                azimuthDegrees: 90,
                geometricAltitudeDegrees: 0,
                apparentAltitudeDegrees: 0
            )
        )!
        let zenith = CelestialPanoramaGeometryV2.normalized(
            position: LocalStarPositionV2(
                azimuthDegrees: 90,
                geometricAltitudeDegrees: 90,
                apparentAltitudeDegrees: 90
            )
        )!

        XCTAssertEqual(horizon.x01, 0.25, accuracy: 1e-12)
        XCTAssertEqual(horizon.y01, 1, accuracy: 1e-12)
        XCTAssertEqual(zenith.y01, 0, accuracy: 1e-12)
    }

    func testSelectedHeadingIsAlwaysAtScreenCenter() {
        for heading in [0.0, 45.0, 180.0, 359.0] {
            XCTAssertEqual(
                CelestialPanoramaGeometryV2.screenFraction(
                    skyX01: CelestialPanoramaGeometryV2.headingFraction(
                        centerAzimuthDegrees: heading
                    ),
                    heading: CelestialPanoramaGeometryV2.headingFraction(
                        centerAzimuthDegrees: heading
                    )
                ),
                0.5,
                accuracy: 1e-12
            )
        }
    }

    func testWrapAroundKeepsNearbyAzimuthsNearbyOnScreen() {
        let heading = CelestialPanoramaGeometryV2.headingFraction(
            centerAzimuthDegrees: 0
        )
        XCTAssertEqual(
            CelestialPanoramaGeometryV2.screenFraction(
                skyX01: 359.0 / 360.0,
                heading: heading
            ),
            0.4972222222,
            accuracy: 1e-8
        )
        XCTAssertEqual(
            CelestialPanoramaGeometryV2.screenFraction(
                skyX01: 1.0 / 360.0,
                heading: heading
            ),
            0.5027777778,
            accuracy: 1e-8
        )
    }

    func testPreprojectedGeometryMatchesCanonicalPanoramaProjection() {
        let headings = [0.0, 40.0, 180.0, 300.0]
        let positions = [
            LocalStarPositionV2(
                azimuthDegrees: 5,
                geometricAltitudeDegrees: 20,
                apparentAltitudeDegrees: 20
            ),
            LocalStarPositionV2(
                azimuthDegrees: 120,
                geometricAltitudeDegrees: 45,
                apparentAltitudeDegrees: 45
            ),
            LocalStarPositionV2(
                azimuthDegrees: 275,
                geometricAltitudeDegrees: 80,
                apparentAltitudeDegrees: 80
            )
        ]

        for headingDegrees in headings {
            let heading = CelestialPanoramaGeometryV2.headingFraction(
                centerAzimuthDegrees: headingDegrees
            )
            for position in positions {
                let canonical = StarSkyProjectionV2.projectToPanorama(
                    position: position,
                    centerAzimuthDegrees: headingDegrees
                )!
                let coordinate = CelestialPanoramaGeometryV2.normalized(
                    position: position
                )!

                XCTAssertEqual(
                    0.5 + canonical.x * 0.5,
                    CelestialPanoramaGeometryV2.screenFraction(
                        skyX01: coordinate.x01,
                        heading: heading
                    ),
                    accuracy: 1e-12
                )
                XCTAssertEqual(
                    0.5 + canonical.y * 0.5,
                    coordinate.y01,
                    accuracy: 1e-12
                )
            }
        }
    }

    func testBelowHorizonIsRejected() {
        XCTAssertNil(
            CelestialPanoramaGeometryV2.normalized(
                position: LocalStarPositionV2(
                    azimuthDegrees: 0,
                    geometricAltitudeDegrees: -5,
                    apparentAltitudeDegrees: -5
                )
            )
        )
    }
}
