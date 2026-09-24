import Foundation

enum CelestialWeatherTypeV2: Equatable, Sendable {
    case clear
    case partlyCloudy
    case overcast
    case fog
    case drizzle
    case rain
    case snow
    case thunderstorm
    case unknown
}

struct CelestialAtmosphereStateV2: Equatable, Sendable {
    let solarLightLevel: Double
    let twilightLevel: Double
    let nightLevel: Double
    let starsVisibility: Double
    let constellationsVisibility: Double
    let sunVisibility: Double
    let moonVisibility: Double
    let atmosphereOpacity: Double
    let cloudCoverage: Double?
    let weatherType: CelestialWeatherTypeV2
    let weatherTransmission: Double
    let ambientStarFactor: Double
    let ambientMoonFactor: Double
}

enum CelestialAtmosphereV2 {
    static func resolve(
        snapshot: CelestialSnapshotV2,
        weather: CelestialWeatherStateV2?,
        ambient: CelestialAmbientLightStateV2
    ) -> CelestialAtmosphereStateV2 {
        let sunAltitude = snapshot.sun.altitudeDegrees
        let solarLight = smoothStep(-6, 8, sunAltitude)
        let night = 1 - smoothStep(-18, -6, sunAltitude)
        let twilight = min(1, max(0, 1 - max(solarLight, night)))

        let astronomicalStarLevel = 1 - smoothStep(-14, -4, sunAltitude)
        let type = weatherType(weather?.weatherCode)
        let transmission = weatherTransmission(weather, type)
        let ambientFactor = ambientStarFactor(ambient)
        let ambientMoonFactor = ambientMoonFactor(ambient)
        let stars = min(
            1,
            max(0, astronomicalStarLevel * transmission * ambientFactor)
        )

        let sunCloudTransmission = weather.map {
            min(1, max(0.18, 1 - min(1, max(0, $0.cloudCover)) * 0.72))
        } ?? 1
        let moonCloudTransmission = weather.map {
            min(1, max(0.08, 1 - min(1, max(0, $0.cloudCover)) * 0.88))
        } ?? 1

        let sunVisibility = min(
            1,
            max(
                0,
                CelestialHorizonTransitionV2.diskOpacity(
                    altitudeDegrees: snapshot.sun.altitudeDegrees
                ) * sunCloudTransmission * phenomenonTransmission(type)
            )
        )
        let moonVisibility = min(
            1,
            max(
                0,
                CelestialHorizonTransitionV2.diskOpacity(
                    altitudeDegrees: snapshot.moon.altitudeDegrees
                ) * moonCloudTransmission * phenomenonTransmission(type) *
                    ambientMoonFactor
            )
        )

        return CelestialAtmosphereStateV2(
            solarLightLevel: solarLight,
            twilightLevel: twilight,
            nightLevel: night,
            starsVisibility: stars,
            constellationsVisibility: stars,
            sunVisibility: sunVisibility,
            moonVisibility: moonVisibility,
            atmosphereOpacity: min(1, max(0, 1 - transmission)),
            cloudCoverage: weather?.cloudCover,
            weatherType: type,
            weatherTransmission: transmission,
            ambientStarFactor: ambientFactor,
            ambientMoonFactor: ambientMoonFactor
        )
    }

    static func weatherType(_ code: Int?) -> CelestialWeatherTypeV2 {
        switch code {
        case 0: return .clear
        case 1, 2: return .partlyCloudy
        case 3: return .overcast
        case 45, 48: return .fog
        case 51, 53, 55, 56, 57: return .drizzle
        case 61, 63, 65, 66, 67, 80, 81, 82: return .rain
        case 71, 73, 75, 77, 85, 86: return .snow
        case 95, 96, 99: return .thunderstorm
        default: return .unknown
        }
    }

    private static func weatherTransmission(
        _ weather: CelestialWeatherStateV2?,
        _ type: CelestialWeatherTypeV2
    ) -> Double {
        guard let weather else { return 1 }

        let cloud = min(1, max(0.08, 1 - min(1, max(0, weather.cloudCover)) * 0.90))
        let visibility = weather.visibilityMeters.map {
            smoothStep(300, 10_000, $0)
        } ?? 1
        return min(
            1,
            max(0.03, cloud * visibility * phenomenonTransmission(type))
        )
    }

    private static func phenomenonTransmission(_ type: CelestialWeatherTypeV2) -> Double {
        switch type {
        case .fog: return 0.36
        case .drizzle: return 0.74
        case .rain: return 0.60
        case .snow: return 0.58
        case .thunderstorm: return 0.42
        case .overcast: return 0.82
        default: return 1
        }
    }

    private static func ambientStarFactor(_ ambient: CelestialAmbientLightStateV2) -> Double {
        guard ambient.quality == .valid,
              let lux = ambient.lux,
              lux.isFinite,
              lux >= 0 else {
            return 1
        }

        let factor: Double
        if lux <= 1 {
            factor = 1.08
        } else if lux <= 50 {
            factor = lerp(1.08, 1, (lux - 1) / 49)
        } else if lux <= 1_000 {
            factor = lerp(1, 0.75, (lux - 50) / 950)
        } else if lux <= 10_000 {
            factor = lerp(0.75, 0.35, (lux - 1_000) / 9_000)
        } else if lux <= 100_000 {
            factor = lerp(0.35, 0.12, (lux - 10_000) / 90_000)
        } else {
            factor = 0.12
        }
        return min(1.08, max(0.12, factor))
    }

    private static func ambientMoonFactor(_ ambient: CelestialAmbientLightStateV2) -> Double {
        guard ambient.quality == .valid,
              let lux = ambient.lux,
              lux.isFinite,
              lux >= 0 else {
            return 1
        }

        let factor: Double
        if lux <= 50 {
            factor = 1
        } else if lux <= 1_000 {
            factor = lerp(1, 0.92, (lux - 50) / 950)
        } else if lux <= 10_000 {
            factor = lerp(0.92, 0.75, (lux - 1_000) / 9_000)
        } else if lux <= 100_000 {
            factor = lerp(0.75, 0.60, (lux - 10_000) / 90_000)
        } else {
            factor = 0.60
        }
        return min(1, max(0.60, factor))
    }

    private static func smoothStep(_ edge0: Double, _ edge1: Double, _ value: Double) -> Double {
        guard value.isFinite else { return 0 }
        if edge0 == edge1 { return value < edge0 ? 0 : 1 }
        let t = min(1, max(0, (value - edge0) / (edge1 - edge0)))
        return t * t * (3 - 2 * t)
    }

    private static func lerp(_ a: Double, _ b: Double, _ t: Double) -> Double {
        a + (b - a) * min(1, max(0, t))
    }
}
