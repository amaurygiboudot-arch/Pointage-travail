import Foundation

// A bounded, immutable recipe. Colours and density remain a visual interpretation.
struct CloudTextureRecipeV2: Equatable, Sendable {
    let atmosphere: CloudAtmosphereStateV2
    let weatherType: CelestialWeatherTypeV2
    let width: Int
    let height: Int
    let octaves: Int
    let seconds: Double
    let day: Double
    let twilight: Double
    let night: Double
}

// CPU work only on a worker; adapters upload the resulting straight-alpha ARGB pixels.
enum CelestialCloudTextureV2 {
    static let maxWidth = 128
    static let maxHeight = 256

    static func rasterize(
        recipe: CloudTextureRecipeV2,
        cancelled: () -> Bool = { false }
    ) -> [UInt32]? {
        guard (2...maxWidth).contains(recipe.width), (2...maxHeight).contains(recipe.height),
              (1...4).contains(recipe.octaves), recipe.seconds.isFinite,
              (-1.0e12...1.0e12).contains(recipe.seconds),
              [recipe.day, recipe.twilight, recipe.night].allSatisfy({ $0.isFinite && (0...1).contains($0) }),
              recipe.day + recipe.twilight + recipe.night > 0 else { return nil }
        let a = recipe.atmosphere
        guard CelestialCloudAtmosphereV2.resolve(totalCoverage: a.totalCoverage,
            lowCoverage: a.lowCoverage, midCoverage: a.midCoverage, highCoverage: a.highCoverage,
            fog: a.fog, visibilityMeters: a.visibilityMeters) != nil else { return nil }
        var pixels = [UInt32](repeating: 0, count: recipe.width * recipe.height)
        let rainy = recipe.weatherType == .rain || recipe.weatherType == .drizzle
        let storm = recipe.weatherType == .thunderstorm
        let snow = recipe.weatherType == .snow
        let dayTop: [Double] = storm ? [124, 132, 146] : rainy ? [174, 182, 192] : snow ? [242, 245, 247] : [238, 242, 246]
        let dayBottom: [Double] = storm ? [82, 90, 104] : rainy ? [132, 143, 156] : snow ? [210, 218, 225] : [190, 201, 212]
        let duskChannels = [137.0, 129.0, 139.0]
        let nightChannels = [30.0, 37.0, 49.0]
        let shadingBand = CloudBandV2(altitude: .unresolved, coverage: 0.5)
        let totalLight = recipe.day + recipe.twilight + recipe.night
        for y in 0..<recipe.height {
            if cancelled() { return nil }
            let y01 = Double(y) / Double(recipe.height - 1)
            for x in 0..<recipe.width {
                guard let density = CelestialCloudDensityV2.sample(state: a,
                    x01: Double(x) / Double(recipe.width - 1), y01: y01,
                    elapsedSeconds: recipe.seconds, octaves: recipe.octaves) else { return nil }
                let alpha = byte(density * 255)
                if alpha == 0 { continue }
                // Internal shading, never an outlined edge. Night stays dark and subdued.
                // Preserve internal volume even when 100% coverage saturates alpha.
                guard let interior = CelestialCloudDensityV2.sampleBand(band: shadingBand,
                    x01: Double(x) / Double(recipe.width - 1), y01: y01,
                    elapsedSeconds: recipe.seconds, octaves: 2) else { return nil }
                let shade = min(1, max(0, 0.25 * y01 + 0.25 * density + 0.30 * interior / 0.74))
                func channel(_ i: Int) -> UInt32 {
                    let daylight = dayTop[i] + (dayBottom[i] - dayTop[i]) * shade
                    let dusk = duskChannels[i] - 30 * shade
                    let dark = nightChannels[i] - 12 * shade
                    return byte((daylight * recipe.day + dusk * recipe.twilight + dark * recipe.night) / totalLight)
                }
                pixels[y * recipe.width + x] = (alpha << 24) | (channel(0) << 16) |
                    (channel(1) << 8) | channel(2)
            }
        }
        return pixels
    }

    private static func byte(_ value: Double) -> UInt32 {
        UInt32(min(255, max(0, floor(value + 0.5))))
    }
}
