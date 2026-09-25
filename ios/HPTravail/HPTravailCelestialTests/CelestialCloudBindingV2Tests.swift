import XCTest
@testable import CelestialV2Contract

final class CelestialCloudBindingV2Tests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_700_000_000)
    private func weather(age: Double = 0, latitude: Double = 46.67, total: Double = 0.8,
                         low: Double = 0.7, visibility: Double = 10_000, code: Int = 3) -> CelestialWeatherStateV2 {
        CelestialWeatherStateV2(cloudCover: total, cloudCoverLow: low, cloudCoverMid: nil, cloudCoverHigh: 0,
            weatherCode: code, precipitationMillimeters: nil, visibilityMeters: visibility,
            fetchedAt: now.addingTimeInterval(-age), roundedLatitudeDegrees: latitude,
            roundedLongitudeDegrees: -1.63, source: "test")
    }
    private func build(_ weather: CelestialWeatherStateV2?, location: CelestialLocationQualityV2 = .valid) throws -> CelestialRenderStateV2 {
        CelestialRenderStateFactoryV2.build(
            snapshot: try DefaultCelestialEngineV2.snapshot(latitudeDegrees: 46.67, longitudeDegrees: -1.63, date: now),
            weather: weather, ambient: CelestialAmbientLightStateV2(lux: nil, quality: .unavailable, measuredAt: now),
            orientationQuality: .valid, locationQuality: location, now: now)
    }

    func testFreshWeatherReachesTheSingleCanonicalCloudState() throws {
        let result = try build(weather())
        XCTAssertEqual(result.dataFreshness.weatherStatus, .fresh)
        XCTAssertEqual(result.cloudsFetchedAt, now)
        XCTAssertEqual(result.cloudsExpiresAt, now.addingTimeInterval(45 * 60))
        XCTAssertEqual(result.clouds?.lowCoverage, 0.7)
        XCTAssertNil(result.clouds?.midCoverage)
        XCTAssertEqual(result.clouds?.highCoverage, 0)
        XCTAssertEqual(result.cloudCoverage, result.clouds?.totalCoverage)
    }

    func testUnusableWeatherCannotAttenuateAstronomicalBodies() throws {
        let neutral = try build(nil)
        let rejected: [(CelestialWeatherStateV2, CelestialDataStatusV2)] = [
            (weather(age: -1), .invalid), (weather(age: 45 * 60 + 1), .stale),
            (weather(latitude: 48.85), .invalid), (weather(low: .nan), .invalid),
            (weather(visibility: -1), .invalid)]
        for (input, status) in rejected {
            let result = try build(input)
            XCTAssertEqual(result.dataFreshness.weatherStatus, status)
            XCTAssertNil(result.clouds)
            XCTAssertNil(result.cloudsExpiresAt)
            XCTAssertNil(result.cloudCoverage)
            XCTAssertEqual(result.weatherType, .unknown)
            XCTAssertEqual(result.starsVisibility, neutral.starsVisibility)
            XCTAssertEqual(result.sunVisibility, neutral.sunVisibility)
            XCTAssertEqual(result.moonVisibility, neutral.moonVisibility)
            XCTAssertEqual(result.sunAzimuthDegrees, neutral.sunAzimuthDegrees)
        }
    }

    func testUnqualifiedLocationDoesNotAuthorizeACloudTexture() throws {
        for quality: CelestialLocationQualityV2 in [.stale, .inaccurate, .unavailable, .noPermission] {
            XCTAssertNil(try build(weather(), location: quality).clouds)
        }
    }

    func testExpiryBoundaryAndKnownZeroAreExplicit() throws {
        XCTAssertNotNil(try build(weather(age: 45 * 60)).clouds)
        XCTAssertNil(try build(weather(age: 45 * 60 + 1)).clouds)
        let clear = try build(weather(total: 0, low: 0, code: 0))
        XCTAssertNotNil(clear.clouds)
        XCTAssertEqual(clear.clouds?.totalCoverage, 0)
        let fog = try build(weather(total: 0, low: 0, code: 45))
        XCTAssertEqual(fog.clouds?.fog, true)
    }
}
