import XCTest
@testable import CelestialV2Contract

final class CelestialAcceptanceMatrixV2Tests: XCTestCase {
    private let noAmbient = CelestialAmbientLightStateV2(
        lux: nil,
        quality: .unavailable,
        measuredAt: nil
    )

    func testEquinoxDayAndNightRemainAstronomicallyDistinct() throws {
        let day = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 0,
            longitudeDegrees: 0,
            date: Date(timeIntervalSince1970: 1_774_008_000)
        )
        let night = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 0,
            longitudeDegrees: 0,
            date: Date(timeIntervalSince1970: 1_773_964_800)
        )

        XCTAssertGreaterThan(day.sun.altitudeDegrees, 60)
        XCTAssertLessThan(night.sun.altitudeDegrees, -60)
        XCTAssertFalse(day.isNight)
        XCTAssertTrue(night.isNight)

        let dayAtmosphere = CelestialAtmosphereV2.resolve(
            snapshot: day,
            weather: nil,
            ambient: noAmbient
        )
        let nightAtmosphere = CelestialAtmosphereV2.resolve(
            snapshot: night,
            weather: nil,
            ambient: noAmbient
        )
        XCTAssertGreaterThan(dayAtmosphere.solarLightLevel, 0.95)
        XCTAssertLessThan(dayAtmosphere.starsVisibility, 0.01)
        XCTAssertGreaterThan(nightAtmosphere.nightLevel, 0.95)
        XCTAssertGreaterThan(nightAtmosphere.starsVisibility, 0.95)
    }

    func testPassageOfMidnightHasNoArtificialAstronomicalJump() throws {
        let before = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 46.67,
            longitudeDegrees: -1.63,
            date: Date(timeIntervalSince1970: 1_798_761_540)
        )
        let after = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 46.67,
            longitudeDegrees: -1.63,
            date: Date(timeIntervalSince1970: 1_798_761_660)
        )

        XCTAssertLessThan(
            abs(after.sun.altitudeDegrees - before.sun.altitudeDegrees),
            1
        )
        XCTAssertLessThan(
            abs(after.moon.altitudeDegrees - before.moon.altitudeDegrees),
            1
        )
        XCTAssertLessThan(
            abs(
                after.moonPhase.illuminatedFraction -
                    before.moonPhase.illuminatedFraction
            ),
            0.01
        )
    }

    func testSupportedLatitudeSamplesProduceFiniteRealSky() throws {
        let latitudes = [-60.0, 0.0, 46.67, 69.0]
        let dates = [
            Date(timeIntervalSince1970: 1_768_514_400),
            Date(timeIntervalSince1970: 1_784_152_800)
        ]

        for latitude in latitudes {
            for date in dates {
                let snapshot = try DefaultCelestialEngineV2.snapshot(
                    latitudeDegrees: latitude,
                    longitudeDegrees: -1.63,
                    date: date
                )
                XCTAssertTrue(snapshot.sun.azimuthDegrees.isFinite)
                XCTAssertTrue(snapshot.sun.altitudeDegrees.isFinite)
                XCTAssertTrue(snapshot.moon.azimuthDegrees.isFinite)
                XCTAssertTrue(snapshot.moon.altitudeDegrees.isFinite)
                XCTAssertTrue((0...1).contains(snapshot.moonPhase.illuminatedFraction))
            }
        }
    }

    func testRealConstellationGeometryChangesWithSeasonWithoutDecoration() {
        let star = BrightStarV2(
            id: "acceptance-star",
            rightAscensionJ2000Degrees: 101.287,
            declinationJ2000Degrees: -16.716,
            visualMagnitude: -1.46,
            constellation: "CMa",
            commonName: "Sirius"
        )
        let winter = StarSkyProjectionV2.horizontal(
            star: star,
            latitudeDegrees: 46.67,
            longitudeDegrees: -1.63,
            date: Date(timeIntervalSince1970: 1_768_514_400)
        )
        let summer = StarSkyProjectionV2.horizontal(
            star: star,
            latitudeDegrees: 46.67,
            longitudeDegrees: -1.63,
            date: Date(timeIntervalSince1970: 1_784_152_800)
        )

        XCTAssertTrue(winter.azimuthDegrees.isFinite)
        XCTAssertTrue(summer.azimuthDegrees.isFinite)
        XCTAssertTrue(
            abs(winter.azimuthDegrees - summer.azimuthDegrees) > 5 ||
                abs(
                    winter.apparentAltitudeDegrees -
                        summer.apparentAltitudeDegrees
                ) > 5
        )
    }

    func testUnknownSensorsNeverCreateDayNightTruth() throws {
        let snapshot = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 46.67,
            longitudeDegrees: -1.63,
            date: Date(timeIntervalSince1970: 1_768_514_400)
        )
        let atmosphere = CelestialAtmosphereV2.resolve(
            snapshot: snapshot,
            weather: nil,
            ambient: noAmbient
        )

        let expectedNight = snapshot.sun.altitudeDegrees < -0.833
        XCTAssertEqual(snapshot.isNight, expectedNight)
        XCTAssertTrue((0...1).contains(atmosphere.solarLightLevel))
        XCTAssertTrue((0...1).contains(atmosphere.nightLevel))
    }
}
