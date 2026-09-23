import XCTest
@testable import CelestialV2Contract

final class StarSkyProjectionV2Tests: XCTestCase {
    private let date = Date(timeIntervalSince1970: 1_799_587_200)

    func testStarOnLocalMeridianAtObserverDeclinationReachesZenith() {
        let lst = StarSkyProjectionV2.localSiderealDegrees(date: date, longitudeDegrees: 0)
        let star = BrightStarV2(
            id: "test",
            rightAscensionJ2000Degrees: lst,
            declinationJ2000Degrees: 0,
            visualMagnitude: 1,
            constellation: nil,
            commonName: nil
        )
        let position = StarSkyProjectionV2.horizontal(
            star: star,
            latitudeDegrees: 0,
            longitudeDegrees: 0,
            date: date
        )
        XCTAssertGreaterThan(position.geometricAltitudeDegrees, 89.5)
    }

    func testPhysicalProjectionCentersStarAlongDisplayNormal() {
        let frame = StarDeviceFrameV2(
            rightEast: 1, rightNorth: 0, rightUp: 0,
            topEast: 0, topNorth: 1, topUp: 0,
            normalEast: 0, normalNorth: 0, normalUp: 1
        )
        let zenith = LocalStarPositionV2(
            azimuthDegrees: 0,
            geometricAltitudeDegrees: 90,
            apparentAltitudeDegrees: 90
        )

        let projected = StarSkyProjectionV2.projectToDevice(position: zenith, frame: frame)
        XCTAssertNotNil(projected)
        XCTAssertEqual(projected?.x ?? 1, 0, accuracy: 1e-12)
        XCTAssertEqual(projected?.y ?? 1, 0, accuracy: 1e-12)
        XCTAssertEqual(projected?.depth ?? 0, 1, accuracy: 1e-12)
    }

    func testPhysicalProjectionRejectsStarBehindPhone() {
        let frame = StarDeviceFrameV2(
            rightEast: 1, rightNorth: 0, rightUp: 0,
            topEast: 0, topNorth: 1, topUp: 0,
            normalEast: 0, normalNorth: 0, normalUp: 1
        )
        let nadir = LocalStarPositionV2(
            azimuthDegrees: 0,
            geometricAltitudeDegrees: -90,
            apparentAltitudeDegrees: -90
        )

        XCTAssertNil(StarSkyProjectionV2.projectToDevice(position: nadir, frame: frame))
    }

    func testNightSkyOpacityFollowsSolarAltitude() {
        XCTAssertEqual(
            StarSkyProjectionV2.nightSkyOpacity(sunGeometricAltitudeDegrees: -3),
            0,
            accuracy: 1e-12
        )
        XCTAssertEqual(
            StarSkyProjectionV2.nightSkyOpacity(sunGeometricAltitudeDegrees: -8),
            0.5,
            accuracy: 0.05
        )
        XCTAssertEqual(
            StarSkyProjectionV2.nightSkyOpacity(sunGeometricAltitudeDegrees: -12),
            1,
            accuracy: 1e-12
        )
    }
}
