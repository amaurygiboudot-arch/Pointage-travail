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
