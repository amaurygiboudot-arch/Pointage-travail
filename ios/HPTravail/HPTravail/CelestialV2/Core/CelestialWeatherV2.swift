import Foundation

struct CelestialWeatherStateV2: Equatable, Sendable {
    let cloudCover: Double
    let cloudCoverLow: Double?
    let cloudCoverMid: Double?
    let cloudCoverHigh: Double?
    let weatherCode: Int?
    let precipitationMillimeters: Double?
    let visibilityMeters: Double?
    let fetchedAt: Date
    let roundedLatitudeDegrees: Double
    let roundedLongitudeDegrees: Double
    let source: String

    var cloudTransmission: Double {
        min(1, max(0.08, 1 - min(1, max(0, cloudCover)) * 0.90))
    }

    func isFresh(at date: Date) -> Bool {
        let age = date.timeIntervalSince(fetchedAt)
        return age >= 0 && age <= Self.maxRenderAge
    }

    private static let maxRenderAge: TimeInterval = 45 * 60
    var renderExpiresAt: Date { fetchedAt.addingTimeInterval(Self.maxRenderAge) }

    func matches(snapshot: CelestialSnapshotV2) -> Bool {
        let latitude = (snapshot.latitudeDegrees * 100).rounded() / 100
        let longitude = (snapshot.longitudeDegrees * 100).rounded() / 100
        return roundedLatitudeDegrees == latitude &&
            roundedLongitudeDegrees == longitude
    }
}

enum CelestialWeatherParserV2 {
    private struct Response: Decodable {
        let current: Current
    }

    private struct Current: Decodable {
        let cloudCover: Double
        let cloudCoverLow: Double?
        let cloudCoverMid: Double?
        let cloudCoverHigh: Double?
        let weatherCode: Int?
        let precipitation: Double?
        let visibility: Double?

        enum CodingKeys: String, CodingKey {
            case cloudCover = "cloud_cover"
            case cloudCoverLow = "cloud_cover_low"
            case cloudCoverMid = "cloud_cover_mid"
            case cloudCoverHigh = "cloud_cover_high"
            case weatherCode = "weather_code"
            case precipitation
            case visibility
        }
    }

    static func parse(
        data: Data,
        fetchedAt: Date,
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        source: String
    ) throws -> CelestialWeatherStateV2 {
        let decoded = try JSONDecoder().decode(Response.self, from: data)

        func percent(_ value: Double?) -> Double? {
            guard let value, value.isFinite else { return nil }
            return min(1, max(0, value / 100))
        }

        return CelestialWeatherStateV2(
            cloudCover: min(1, max(0, decoded.current.cloudCover / 100)),
            cloudCoverLow: percent(decoded.current.cloudCoverLow),
            cloudCoverMid: percent(decoded.current.cloudCoverMid),
            cloudCoverHigh: percent(decoded.current.cloudCoverHigh),
            weatherCode: decoded.current.weatherCode,
            precipitationMillimeters: decoded.current.precipitation,
            visibilityMeters: decoded.current.visibility,
            fetchedAt: fetchedAt,
            roundedLatitudeDegrees: latitudeDegrees,
            roundedLongitudeDegrees: longitudeDegrees,
            source: source
        )
    }
}

