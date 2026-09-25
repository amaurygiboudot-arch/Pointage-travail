import XCTest
@testable import CelestialV2Contract

final class CelestialSphericalDialV2Tests: XCTestCase {
    func testAdapterUsesApparentAltitudeExactlyOnce() throws {
        let actual = try XCTUnwrap(CelestialDialProjectionV2.projectSpherical(
            azimuthDegrees: 90, altitudeDegrees: 0, trueHeadingDegrees: 0))
        let apparent = AtmosphericRefractionV2.apparentAltitudeDegrees(geometricAltitudeDegrees: 0) * .pi / 180
        XCTAssertEqual(actual.x,0.76*cos(apparent),accuracy: 1e-12)
        XCTAssertEqual(actual.y,-0.76*sin(apparent)*cos(35 * .pi / 180),accuracy: 1e-12)
    }
    func testDiskAndHorizonFadeHaveNoMismatchedVisibilityCutoff() {
        for i in -1000...3000 {
            let h=Double(i)/1000
            let alpha=CelestialHorizonTransitionV2.diskOpacity(altitudeDegrees: h)
            if alpha>0 {
                XCTAssertNotNil(CelestialDialProjectionV2.projectSpherical(
                    azimuthDegrees: 90,altitudeDegrees: h,trueHeadingDegrees: 0))
            }
            XCTAssertLessThan(abs(alpha-CelestialHorizonTransitionV2.diskOpacity(altitudeDegrees: h+0.001)),0.001)
        }
        XCTAssertNil(CelestialDialProjectionV2.projectSpherical(azimuthDegrees: 90,altitudeDegrees: -0.84,trueHeadingDegrees: 0))
    }
    func testHeadingCannotCullBodyBehindPhone() {
        for heading in stride(from: 0.0,through: 360.0,by: 5) {
            XCTAssertNotNil(CelestialDialProjectionV2.projectSpherical(
                azimuthDegrees: 17,altitudeDegrees: 10,trueHeadingDegrees: heading))
        }
    }
    func testSphereReservesExistingIconMarginInsideDial() throws {
        for az in stride(from: 0.0,through: 360.0,by: 5) {
            for alt in stride(from: 0.0,through: 90.0,by: 3) {
                let p=try XCTUnwrap(CelestialDialProjectionV2.projectSpherical(
                    azimuthDegrees: az,altitudeDegrees: alt,trueHeadingDegrees: 0))
                XCTAssertLessThan(hypot(p.x,p.y)+0.22,1)
            }
        }
    }
}
