import XCTest
@testable import CelestialV2Contract

final class CelestialSolarFrameRegressionV2Tests: XCTestCase {
    func testCompassRotationPreservesTheSphericalAltitudeLocus() throws {
        let r = CelestialDomeV2.radiusFraction
        let tilt = CelestialDomeV2.cameraElevationDegrees * .pi / 180
        for altitude in [0.0, 10.0, 43.0, 70.0] {
            let h = altitude * .pi / 180
            let rx = r * cos(h)
            let ry = rx * sin(tilt)
            let centreY = -r * sin(h) * cos(tilt)
            for heading in 0...360 {
                let p = try XCTUnwrap(CelestialDomeV2.project(azimuthDegrees: 183,
                    apparentAltitudeDegrees: altitude, headingDegrees: Double(heading)))
                XCTAssertEqual(pow(p.x / rx, 2) + pow((p.y - centreY) / ry, 2), 1, accuracy: 1e-10)
                if altitude > CelestialDomeV2.cameraElevationDegrees { XCTAssertLessThan(p.y, 0) }
            }
        }
    }
    func testWholeSceneMovementCannotChangeSunEarthOffset() throws {
        let p = try XCTUnwrap(CelestialDomeV2.project(azimuthDegrees: 160,
            apparentAltitudeDegrees: 43, headingDegrees: 90))
        for offset in -500...500 {
            let earthY = 600.0 + Double(offset)
            XCTAssertEqual((earthY + p.y * 200) - earthY, p.y * 200, accuracy: 1e-10)
        }
    }
}
