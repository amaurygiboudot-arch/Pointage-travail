import Foundation
import SwiftUI

@MainActor
final class CelestialWeatherClientV2: ObservableObject {
    @Published private(set) var state: CelestialWeatherStateV2?

    private var requestKey: String?
    private var lastAttempt = Date.distantPast

    func refreshIfNeeded(snapshot: CelestialSnapshotV2) async {
        guard let endpoint = Bundle.main.object(
            forInfoDictionaryKey: "CelestialWeatherEndpoint"
        ) as? String else {
            return
        }
        let trimmed = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("https://") else { return }

        let latitude = Self.roundedCoordinate(snapshot.latitudeDegrees)
        let longitude = Self.roundedCoordinate(snapshot.longitudeDegrees)
        let key = String(format: "%.2f:%.2f", latitude, longitude)
        let now = Date()

        if let state,
           state.roundedLatitudeDegrees == latitude,
           state.roundedLongitudeDegrees == longitude,
           now.timeIntervalSince(state.fetchedAt) < 15 * 60 {
            return
        }
        if requestKey == key { return }
        if now.timeIntervalSince(lastAttempt) < 60 { return }

        requestKey = key
        lastAttempt = now
        defer { requestKey = nil }

        var components = URLComponents(string: trimmed)
        components?.queryItems = [
            URLQueryItem(name: "latitude", value: String(format: "%.2f", latitude)),
            URLQueryItem(name: "longitude", value: String(format: "%.2f", longitude)),
            URLQueryItem(
                name: "current",
                value: "cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high,weather_code,precipitation,visibility"
            ),
            URLQueryItem(name: "timezone", value: "UTC")
        ]
        guard let url = components?.url else { return }

        var request = URLRequest(url: url)
        request.timeoutInterval = 10
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("HoraTrack-Celeste/2", forHTTPHeaderField: "User-Agent")

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse,
                  (200...299).contains(http.statusCode) else {
                return
            }
            let parsed = try CelestialWeatherParserV2.parse(
                data: data,
                fetchedAt: Date(),
                latitudeDegrees: latitude,
                longitudeDegrees: longitude,
                source: trimmed
            )
            state = parsed
        } catch {
            // Fail closed: no weather layer is rendered when current weather is unknown.
        }
    }

    var freshState: CelestialWeatherStateV2? {
        state?.isFresh(at: Date()) == true ? state : nil
    }

    private static func roundedCoordinate(_ value: Double) -> Double {
        (value * 100).rounded() / 100
    }
}
