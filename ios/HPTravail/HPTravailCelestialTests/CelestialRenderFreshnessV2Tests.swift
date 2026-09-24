import XCTest
@testable import CelestialV2Contract

final class CelestialRenderFreshnessV2Tests: XCTestCase {
    func testFreshSourcesKeepTheirAgeAndOrigin() throws {
        let snapshot = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 46.67,
            longitudeDegrees: -1.63,
            date: Date(timeIntervalSince1970: 1_700_000_000)
        )
        let data = """
        {"current":{"cloud_cover":20,"weather_code":1}}
        """.data(using: .utf8)!
        let weather = try CelestialWeatherParserV2.parse(
            data: data,
            fetchedAt: snapshot.date.addingTimeInterval(1),
            latitudeDegrees: 46.67,
            longitudeDegrees: -1.63,
            source: "https://weather.test"
        )
        let ambient = CelestialAmbientLightStateV2(
            lux: 120,
            quality: .valid,
            measuredAt: snapshot.date.addingTimeInterval(1)
        )

        let state = CelestialRenderStateFactoryV2.build(
            snapshot: snapshot,
            weather: weather,
            ambient: ambient,
            orientationQuality: .valid,
            locationQuality: .valid,
            locationAge: 0.8,
            locationSource: "CoreLocation",
            headingAge: 0.3,
            now: snapshot.date.addingTimeInterval(2)
        )

        let freshness = state.dataFreshness
        XCTAssertEqual(freshness.astronomyStatus, .fresh)
        XCTAssertEqual(freshness.locationStatus, .fresh)
        XCTAssertEqual(freshness.headingStatus, .fresh)
        XCTAssertEqual(freshness.weatherStatus, .fresh)
        XCTAssertEqual(freshness.ambientStatus, .fresh)
        XCTAssertEqual(freshness.locationSource, "CoreLocation")
        XCTAssertEqual(freshness.weatherSource, "https://weather.test")
        XCTAssertEqual(freshness.weatherAge ?? -1, 1, accuracy: 1e-12)
        XCTAssertEqual(freshness.ambientAge ?? -1, 1, accuracy: 1e-12)
    }

    func testStaleAndInvalidInputsAreExplicitlyQualified() throws {
        let snapshot = try DefaultCelestialEngineV2.snapshot(
            latitudeDegrees: 46.67,
            longitudeDegrees: -1.63,
            date: Date(timeIntervalSince1970: 1_700_000_000)
        )
        let data = """
        {"current":{"cloud_cover":80,"weather_code":45}}
        """.data(using: .utf8)!
        let weather = try CelestialWeatherParserV2.parse(
            data: data,
            fetchedAt: snapshot.date.addingTimeInterval(-3_600),
            latitudeDegrees: 46.67,
            longitudeDegrees: -1.63,
            source: "test"
        )
        let ambient = CelestialAmbientLightStateV2(
            lux: nil,
            quality: .stale,
            measuredAt: snapshot.date.addingTimeInterval(-20)
        )

        let state = CelestialRenderStateFactoryV2.build(
            snapshot: snapshot,
            weather: weather,
            ambient: ambient,
            orientationQuality: .inaccurate,
            locationQuality: .stale,
            locationAge: 120,
            locationSource: "CoreLocation",
            headingAge: 10,
            now: snapshot.date.addingTimeInterval(10)
        )

        let freshness = state.dataFreshness
        XCTAssertEqual(freshness.astronomyStatus, .stale)
        XCTAssertEqual(freshness.locationStatus, .stale)
        XCTAssertEqual(freshness.headingStatus, .invalid)
        XCTAssertEqual(freshness.weatherStatus, .stale)
        XCTAssertEqual(freshness.ambientStatus, .stale)
        XCTAssertTrue(state.warnings.contains(.weatherStale))
        XCTAssertTrue(state.warnings.contains(.locationStale))
        XCTAssertTrue(state.warnings.contains(.astronomyStale))
        XCTAssertTrue(state.warnings.contains(.orientationUnqualified))
    }
}
