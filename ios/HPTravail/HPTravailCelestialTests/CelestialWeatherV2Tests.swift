import XCTest
@testable import CelestialV2Contract

final class CelestialWeatherV2Tests: XCTestCase {
    func testParsesCloudCoverageAndWeather() throws {
        let data = """
        {
          "current": {
            "cloud_cover": 73,
            "cloud_cover_low": 45,
            "cloud_cover_mid": 20,
            "cloud_cover_high": 55,
            "weather_code": 3,
            "precipitation": 0.2,
            "visibility": 12000
          }
        }
        """.data(using: .utf8)!

        let state = try CelestialWeatherParserV2.parse(
            data: data,
            fetchedAt: Date(timeIntervalSince1970: 1_000),
            latitudeDegrees: 46.67,
            longitudeDegrees: -1.63,
            source: "test"
        )

        XCTAssertEqual(state.cloudCover, 0.73, accuracy: 1e-12)
        XCTAssertEqual(state.cloudCoverLow ?? -1, 0.45, accuracy: 1e-12)
        XCTAssertEqual(state.weatherCode, 3)
        XCTAssertEqual(state.precipitationMillimeters ?? -1, 0.2, accuracy: 1e-12)
        XCTAssertLessThan(state.cloudTransmission, 0.4)
    }
}
