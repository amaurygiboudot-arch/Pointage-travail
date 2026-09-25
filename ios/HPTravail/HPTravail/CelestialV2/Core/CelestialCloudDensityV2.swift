import Foundation

// UNRESOLVED is deliberately not inferred to be a low/mid/high altitude.
enum CloudAltitudeV2: Equatable, Sendable { case low, mid, high, unresolved }

struct CloudBandV2: Equatable, Sendable {
    let altitude: CloudAltitudeV2
    let coverage: Double
}

// Qualified weather inputs, not a reconstruction of individual real clouds.
struct CloudAtmosphereStateV2: Equatable, Sendable {
    let totalCoverage: Double
    let lowCoverage: Double?
    let midCoverage: Double?
    let highCoverage: Double?
    let fog: Bool
    let visibilityMeters: Double?

    let bands: [CloudBandV2]

    fileprivate init(
        totalCoverage: Double, lowCoverage: Double?, midCoverage: Double?,
        highCoverage: Double?, fog: Bool, visibilityMeters: Double?
    ) {
        self.totalCoverage = totalCoverage
        self.lowCoverage = lowCoverage
        self.midCoverage = midCoverage
        self.highCoverage = highCoverage
        self.fog = fog
        self.visibilityMeters = visibilityMeters
        let known = [
            highCoverage.map { CloudBandV2(altitude: .high, coverage: $0) },
            midCoverage.map { CloudBandV2(altitude: .mid, coverage: $0) },
            lowCoverage.map { CloudBandV2(altitude: .low, coverage: $0) }
        ].compactMap { $0 }
        self.bands = known.isEmpty ? [CloudBandV2(altitude: .unresolved, coverage: totalCoverage)] : known
    }

    var hasCompleteAltitudeCoverage: Bool {
        lowCoverage != nil && midCoverage != nil && highCoverage != nil
    }
}

enum CelestialCloudAtmosphereV2 {
    // Missing/stale/foreign weather and corrupt percentages are NOT clear sky.
    static func resolve(
        totalCoverage: Double?,
        lowCoverage: Double? = nil,
        midCoverage: Double? = nil,
        highCoverage: Double? = nil,
        fog: Bool = false,
        visibilityMeters: Double? = nil,
        usable: Bool = true
    ) -> CloudAtmosphereStateV2? {
        guard usable, let totalCoverage, validCoverage(totalCoverage) else { return nil }
        for value in [lowCoverage, midCoverage, highCoverage].compactMap({ $0 }) {
            guard validCoverage(value) else { return nil }
        }
        if let visibilityMeters, !visibilityMeters.isFinite || visibilityMeters < 0 { return nil }
        return CloudAtmosphereStateV2(
            totalCoverage: totalCoverage, lowCoverage: lowCoverage,
            midCoverage: midCoverage, highCoverage: highCoverage,
            fog: fog, visibilityMeters: visibilityMeters
        )
    }

    private static func validCoverage(_ value: Double) -> Bool {
        value.isFinite && (0...1).contains(value)
    }
}

// Portable V5 density kernel. Recipes, optical weights and drift are visual
// interpretations, NOT measured microphysics, real cloud positions or wind.
// Callers cache textures and supply a continuous time coordinate. No clock/GPS/API.
// x01 is periodic at the panorama seam; y01 goes from zenith/top to horizon/bottom.
enum CelestialCloudDensityV2 {
    static func sample(
        state: CloudAtmosphereStateV2?,
        x01: Double,
        y01: Double,
        elapsedSeconds: Double,
        octaves: Int = 4
    ) -> Double? {
        guard let state, validCoordinates(x01, y01, elapsedSeconds, octaves) else { return nil }
        var transmission = 1.0
        for band in state.bands {
            guard let alpha = sampleBand(
                band: band, x01: x01, y01: y01,
                elapsedSeconds: elapsedSeconds, octaves: octaves
            ) else { return nil }
            transmission *= 1 - alpha
        }
        if !state.hasCompleteAltitudeCoverage && !state.bands.contains(where: { $0.altitude == .unresolved }) {
            // A partial profile cannot turn aggregate cloudiness into clear sky.
            // Graphical fallback only: no missing altitude is filled or inferred.
            guard let aggregate = sampleBand(
                band: CloudBandV2(altitude: .unresolved, coverage: state.totalCoverage),
                x01: x01, y01: y01, elapsedSeconds: elapsedSeconds, octaves: octaves
            ) else { return nil }
            transmission = min(transmission, 1 - aggregate)
        }
        if state.fog {
            // Fog remains a low continuous veil even with zero total cloud cover.
            let strength = state.visibilityMeters.map { 1 - smooth(100, 2_000, $0) } ?? 0.65
            let veil = (0.15 + 0.70 * strength) * smooth(0.20, 1, y01)
            transmission *= 1 - veil
        }
        return clamp(1 - transmission)
    }

    static func sampleBand(
        band: CloudBandV2,
        x01: Double,
        y01: Double,
        elapsedSeconds: Double,
        octaves: Int = 4
    ) -> Double? {
        guard validCoordinates(x01, y01, elapsedSeconds, octaves),
              band.coverage.isFinite, (0...1).contains(band.coverage) else { return nil }
        if band.coverage == 0 { return 0 }
        let seed: Int
        let stretch: Double
        let opacity: Double
        let drift: Double
        switch band.altitude {
        case .high: seed = 113; stretch = 9; opacity = 0.38; drift = 0.000009
        case .mid: seed = 227; stretch = 2.8; opacity = 0.70; drift = 0.000012
        case .low: seed = 349; stretch = 1.4; opacity = 0.88; drift = 0.000016
        case .unresolved: seed = 491; stretch = 2; opacity = 0.74; drift = 0.000012
        }
        // No short modulo-time reset; translation and deformation stay continuous.
        let x = fract(x01 + elapsedSeconds * drift)
        let y = y01 * stretch + elapsedSeconds * 0.000003
        let warp = noise(x * 4, y * 1.7, 4, seed + 17) - 0.5
        var value = 0.0
        var weight = 0.5
        var totalWeight = 0.0
        var period = 4
        for _ in 0..<octaves {
            value += weight * noise(
                fract(x + warp * 0.08) * Double(period),
                (y + warp * 0.22) * Double(period), period, seed
            )
            totalWeight += weight
            weight *= 0.5
            period *= 2
        }
        let field = clamp((value / totalWeight - 0.5) * 1.65 + 0.5)
        let threshold = 1 - band.coverage
        let softMask = smooth(threshold - 0.18, threshold + 0.18, field)
        let overcastFloor = 0.70 * smooth(0.85, 1, band.coverage)
        return clamp(max(overcastFloor, softMask) * opacity)
    }

    private static func validCoordinates(_ x: Double, _ y: Double, _ seconds: Double, _ octaves: Int) -> Bool {
        x.isFinite && y.isFinite && seconds.isFinite &&
            (-1.0e12...1.0e12).contains(x) && (0...1).contains(y) &&
            (-1.0e12...1.0e12).contains(seconds) && (1...6).contains(octaves)
    }

    private static func noise(_ x: Double, _ y: Double, _ period: Int, _ seed: Int) -> Double {
        let x0 = Int(floor(x))
        let wrappedY = y - floor(y / 1_048_576) * 1_048_576
        let y0 = Int(floor(wrappedY))
        let tx = smooth(0, 1, x - floor(x))
        let ty = smooth(0, 1, wrappedY - floor(wrappedY))
        func h(_ ix: Int, _ iy: Int) -> Double {
            hash((ix % period + period) % period, (iy % 1_048_576 + 1_048_576) % 1_048_576, seed)
        }
        let top = lerp(h(x0, y0), h(x0 + 1, y0), tx)
        let bottom = lerp(h(x0, y0 + 1), h(x0 + 1, y0 + 1), tx)
        return lerp(top, bottom, ty)
    }

    private static func hash(_ x: Int, _ y: Int, _ seed: Int) -> Double {
        // Same wrapping UInt32 hash as the JVM implementation; never Swift Hasher.
        var bits = UInt32(truncatingIfNeeded: x) &* 374761393
        bits = bits &+ (UInt32(truncatingIfNeeded: y) &* 668265263)
        bits = bits &+ (UInt32(truncatingIfNeeded: seed) &* 982451653)
        bits = (bits ^ (bits >> 13)) &* 1274126177
        bits = bits ^ (bits >> 16)
        return Double(bits) / 4294967295
    }

    private static func clamp(_ value: Double) -> Double { min(1, max(0, value)) }
    private static func fract(_ value: Double) -> Double { value - floor(value) }
    private static func lerp(_ a: Double, _ b: Double, _ t: Double) -> Double { a + (b - a) * t }
    private static func smooth(_ a: Double, _ b: Double, _ value: Double) -> Double {
        let t = clamp((value - a) / (b - a))
        return t * t * (3 - 2 * t)
    }
}
