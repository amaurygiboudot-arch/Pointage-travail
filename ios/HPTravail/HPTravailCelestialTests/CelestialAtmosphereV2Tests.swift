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
}
