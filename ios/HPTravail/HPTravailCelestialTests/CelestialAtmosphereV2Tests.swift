import XCTest
@testable import CelestialV2Contract

final class CelestialAtmosphereV2Tests: XCTestCase {
    private func ambient(
        _ lux: Double? = nil,
        quality: CelestialAmbientLightQualityV2 = .unavailable
    ) -> CelestialAmbientLightStateV2 {
        CelestialAmbientLightStateV2(
            lux: lux,
            quality: quality,
            measuredAt: Date(timeIntervalSince1970: 1_000)
        )
    }

    func testAmbientDarknessCannotTurnDayIntoAstronomicalNight() throws {
        let snapshot = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 46.67,
            longitudeDegrees: -1.63,
            date: Date(timeIntervalSince1970: 1_789_128_000)
        )
        let state = CelestialAtmosphereV2.resolve(
            snapshot: snapshot,
            weather: nil,
            ambient: ambient(0, quality: .valid)
        )

        if snapshot.sun.altitudeDegrees > 8 {
            XCTAssertGreaterThan(state.solarLightLevel, 0.95)
            XCTAssertEqual(state.starsVisibility, 0, accuracy: 1e-9)
        }
    }

    func testFogAndCloudReduceVisibilityWithoutChangingAstronomy() throws {
        let snapshot = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 46.67,
            longitudeDegrees: -1.63,
            date: Date(timeIntervalSince1970: 1_789_171_200)
        )
        let data = """
        {"current":{"cloud_cover":95,"weather_code":45,"visibility":300}}
        """.data(using: .utf8)!
        let weather = try CelestialWeatherParserV2.parse(
            data: data,
            fetchedAt: snapshot.date,
            latitudeDegrees: snapshot.latitudeDegrees,
            longitudeDegrees: snapshot.longitudeDegrees,
            source: "test"
        )

        let clear = CelestialAtmosphereV2.resolve(
            snapshot: snapshot,
            weather: nil,
            ambient: ambient()
        )
        let foggy = CelestialAtmosphereV2.resolve(
            snapshot: snapshot,
            weather: weather,
            ambient: ambient()
        )

        XCTAssertLessThanOrEqual(foggy.starsVisibility, clear.starsVisibility)
        XCTAssertLessThan(foggy.weatherTransmission, 0.2)
    }

    func testSharedWeatherCodeCategories() {
        XCTAssertEqual(CelestialAtmosphereV2.weatherType(0), .clear)
        XCTAssertEqual(CelestialAtmosphereV2.weatherType(45), .fog)
        XCTAssertEqual(CelestialAtmosphereV2.weatherType(81), .rain)
        XCTAssertEqual(CelestialAtmosphereV2.weatherType(86), .snow)
        XCTAssertEqual(CelestialAtmosphereV2.weatherType(95), .thunderstorm)
        XCTAssertEqual(CelestialAtmosphereV2.weatherType(nil), .unknown)
    }
    func testTwilightAndNightLevelsRemainContinuous() throws {
        let base = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 0,
            longitudeDegrees: 0,
            date: Date(timeIntervalSince1970: 1_700_000_000)
        )
        let altitudes = [-20.0, -18.0, -12.0, -6.0, 0.0, 8.0, 15.0]
        let states = altitudes.map { altitude -> CelestialAtmosphereStateV2 in
            let snapshot = CelestialSnapshotV2(
                date: base.date,
                latitudeDegrees: base.latitudeDegrees,
                longitudeDegrees: base.longitudeDegrees,
                sun: CelestialBodyV2(
                    azimuthDegrees: base.sun.azimuthDegrees,
                    altitudeDegrees: altitude,
                    distanceKilometers: base.sun.distanceKilometers,
                    apparentScale: base.sun.apparentScale
                ),
                moon: base.moon,
                moonPhase: base.moonPhase,
                lunarEclipse: base.lunarEclipse,
                isNight: altitude < -0.833
            )
            return CelestialAtmosphereV2.resolve(
                snapshot: snapshot,
                weather: nil,
                ambient: ambient()
            )
        }

        for state in states {
            XCTAssertTrue((0...1).contains(state.solarLightLevel))
            XCTAssertTrue((0...1).contains(state.twilightLevel))
            XCTAssertTrue((0...1).contains(state.nightLevel))
            XCTAssertTrue((0...1).contains(state.starsVisibility))
        }
        for index in 1..<states.count {
            XCTAssertGreaterThanOrEqual(
                states[index].solarLightLevel,
                states[index - 1].solarLightLevel
            )
            XCTAssertLessThanOrEqual(
                states[index].nightLevel,
                states[index - 1].nightLevel
            )
            XCTAssertLessThanOrEqual(
                states[index].starsVisibility,
                states[index - 1].starsVisibility
            )
        }
    }

    func testBrightAmbientLightReducesStarsAndMoonWithoutChangingNightState() throws {
        let base = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 0,
            longitudeDegrees: 0,
            date: Date(timeIntervalSince1970: 1_700_000_000)
        )
        let night = CelestialSnapshotV2(
            date: base.date,
            latitudeDegrees: base.latitudeDegrees,
            longitudeDegrees: base.longitudeDegrees,
            sun: CelestialBodyV2(
                azimuthDegrees: base.sun.azimuthDegrees,
                altitudeDegrees: -20,
                distanceKilometers: base.sun.distanceKilometers,
                apparentScale: base.sun.apparentScale
            ),
            moon: CelestialBodyV2(
                azimuthDegrees: base.moon.azimuthDegrees,
                altitudeDegrees: 35,
                distanceKilometers: base.moon.distanceKilometers,
                apparentScale: base.moon.apparentScale
            ),
            moonPhase: base.moonPhase,
            lunarEclipse: base.lunarEclipse,
            isNight: true
        )
        let dark = CelestialAtmosphereV2.resolve(
            snapshot: night,
            weather: nil,
            ambient: ambient(0.2, quality: .valid)
        )
        let bright = CelestialAtmosphereV2.resolve(
            snapshot: night,
            weather: nil,
            ambient: ambient(100_000, quality: .valid)
        )

        XCTAssertEqual(dark.nightLevel, bright.nightLevel, accuracy: 0)
        XCTAssertLessThan(bright.starsVisibility, dark.starsVisibility)
        XCTAssertLessThan(bright.moonVisibility, dark.moonVisibility)
        XCTAssertGreaterThanOrEqual(bright.starsVisibility, 0)
        XCTAssertGreaterThan(bright.moonVisibility, 0)
    }

}
