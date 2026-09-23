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

    func testSixSiderealHoursWestPlacesEquatorialStarOnWesternHorizon() {
        let lst = StarSkyProjectionV2.localSiderealDegrees(date: date, longitudeDegrees: 0)
        let star = BrightStarV2(
            id: "test",
            rightAscensionJ2000Degrees: lst - 90,
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
        XCTAssertLessThan(abs(position.geometricAltitudeDegrees), 0.6)
        XCTAssertGreaterThan(position.azimuthDegrees, 260)
        XCTAssertLessThan(position.azimuthDegrees, 280)
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

    func testTrueNorthDeviceFrameMapsPortraitAndLandscapeAxes() {
        let identity = StarAttitudeMatrixV2(
            m11: 1, m12: 0, m13: 0,
            m21: 0, m22: 1, m23: 0,
            m31: 0, m32: 0, m33: 1
        )

        let portrait = StarDeviceFrameFactoryV2.trueNorthFrame(
            matrix: identity,
            gravityX: 0,
            gravityY: 0,
            gravityZ: -1,
            orientation: .portrait
        )
        XCTAssertEqual(portrait?.rightEast ?? 9, 0, accuracy: 1e-12)
        XCTAssertEqual(portrait?.rightNorth ?? 9, 1, accuracy: 1e-12)
        XCTAssertEqual(portrait?.topEast ?? 9, -1, accuracy: 1e-12)
        XCTAssertEqual(portrait?.topNorth ?? 9, 0, accuracy: 1e-12)
        XCTAssertEqual(portrait?.normalUp ?? 9, 1, accuracy: 1e-12)

        let landscape = StarDeviceFrameFactoryV2.trueNorthFrame(
            matrix: identity,
            gravityX: 0,
            gravityY: 0,
            gravityZ: -1,
            orientation: .landscapeLeft
        )
        XCTAssertEqual(landscape?.rightEast ?? 9, -1, accuracy: 1e-12)
        XCTAssertEqual(landscape?.rightNorth ?? 9, 0, accuracy: 1e-12)
        XCTAssertEqual(landscape?.topEast ?? 9, 0, accuracy: 1e-12)
        XCTAssertEqual(landscape?.topNorth ?? 9, -1, accuracy: 1e-12)
        XCTAssertEqual(landscape?.normalUp ?? 9, 1, accuracy: 1e-12)
    }

    func testTrueNorthFrameUsesCoreMotionColumnConvention() {
        let matrix = StarAttitudeMatrixV2(
            m11: 1, m12: 0, m13: 0,
            m21: 0, m22: 0, m23: -1,
            m31: 0, m32: 1, m33: 0
        )
        let frame = StarDeviceFrameFactoryV2.trueNorthFrame(
            matrix: matrix,
            gravityX: 0,
            gravityY: -1,
            gravityZ: 0,
            orientation: .portrait
        )

        XCTAssertEqual(frame?.topUp ?? 9, 1, accuracy: 1e-12)
        XCTAssertEqual(frame?.rightNorth ?? 9, 1, accuracy: 1e-12)
        XCTAssertEqual(frame?.normalEast ?? 9, 1, accuracy: 1e-12)
    }

    func testPhysicalFrameFailsClosedWhenGravityDisagreesWithAttitude() {
        let matrix = StarAttitudeMatrixV2(
            m11: 1, m12: 0, m13: 0,
            m21: 0, m22: 0, m23: -1,
            m31: 0, m32: 1, m33: 0
        )

        XCTAssertNil(
            StarDeviceFrameFactoryV2.trueNorthFrame(
                matrix: matrix,
                gravityX: 0,
                gravityY: 1,
                gravityZ: 0,
                orientation: .portrait
            )
        )
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
